package com.asr.live.service

import android.content.Context
import android.os.SystemClock
import com.asr.live.asr.EngineFactory
import com.asr.live.audio.AudioCapture
import com.asr.live.i18n.MlKitTranslator
import com.asr.live.i18n.LocalTranslator
import com.asr.live.i18n.NativeTranslator
import com.asr.live.model.*
import com.asr.live.pipeline.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class AudioChunk(val samples: FloatArray, val capturedAt: Long, val sequence: Long)
internal data class CorrectionRequest(val request: TranslationRequest, val samples: FloatArray)
internal data class TranslationRequest(val key: SegmentKey, val text: String, val endpointAt: Long? = null)

/** Each native object has a single owning worker. Cancellation never releases an in-use object. */
class CaptionSession(
    private val ctx: Context,
    val generation: Long,
    private val config: SessionConfig,
    private val onFailure: (String) -> Unit,
) {
    private val active = AtomicBoolean(true)
    private val audioQueue = BoundedMailbox<AudioChunk>(16)
    private val provisional = BoundedMailbox<TranslationRequest>(1)
    private val finals = BoundedMailbox<TranslationRequest>(6)
    private val correctionQueue = BoundedMailbox<CorrectionRequest>(1)
    private val correctionBusy = AtomicBoolean(false)
    @Volatile private var correctionReady = false
    @Volatile private var correctionRtf = 0.0
    private val correctionEnabled = config.correction && config.profile.correctionSupported && config.modelId == ModelCatalog.NEMOTRON.id
    private val corrector = Thread(::correctLoop, "endpoint-correction")
    private val sequence = AtomicLong()
    @Volatile private var fastTranslator: LocalTranslator? = null
    @Volatile private var finalTranslator: LocalTranslator? = null
    private val fastEnabled = config.quality == TranslationQuality.ML_KIT || config.profile.source == "nl"
    private val pcm = PcmBuffer()
    private var lastProvisionalAt = 0L
    private var lastProvisionalText = ""
    private val info = ModelCatalog.byId(config.modelId) ?: ModelCatalog.DEFAULT
    private val capture = AudioCapture(ctx,
        onChunk = { samples ->
            if (active.get()) {
                val dropped = audioQueue.offer(AudioChunk(samples, now(), sequence.incrementAndGet()))
                CaptionState.metrics(generation) { it.copy(audioDepth = audioQueue.size(),
                    droppedAudioMs = it.droppedAudioMs + (dropped?.samples?.size ?: 0) / 16) }
            }
        },
        onStarted = { if (active.get()) CaptionState.listening(generation) },
        onError = { fail("Microphone: ${it.message}") })
    private val asr = Thread(::recognize, "recognition")
    private val fast = Thread({ translateLoop(false) }, "provisional-translation")
    private val final = Thread({ translateLoop(true) }, "final-translation")
    fun start() { fast.start(); final.start(); corrector.start(); asr.start() }
    fun cancel() {
        active.set(false); capture.cancel()
        fastTranslator?.cancel(); finalTranslator?.cancel()
        audioQueue.close(); provisional.close(); finals.close(); correctionQueue.close().forEach { it.samples.fill(0f) }
        asr.interrupt(); fast.interrupt(); final.interrupt(); corrector.interrupt()
    }
    fun join() { asr.join(); capture.join(); fast.join(); final.join(); corrector.join() }
    private fun fail(message: String) {
        if (active.compareAndSet(true, false)) { onFailure(message); cancel() }
    }
    private fun recognize() {
        var engine: com.asr.live.asr.AsrEngine? = null
        var expectedSequence = 0L
        var audioMs = 0L
        var computeMs = 0L
        try {
            require(info.supports(config.profile.source)) { "${info.shortName} does not support ${config.profile.source}" }
            ModelStore.verify(ctx, info)
            if (!active.get()) return
            fun create() = EngineFactory.create(ctx, info, config.profile.source, "transcribe", ::partial, ::endpoint, config.threads)
            engine = create()
            if (!active.get()) return
            capture.start()
            while (active.get()) {
                val chunk = audioQueue.poll()
                if (chunk == null) { Thread.sleep(10); continue }
                if (expectedSequence != 0L && chunk.sequence != expectedSequence + 1) {
                    // Audio loss is a discontinuity: do not join speech from either side into one hypothesis.
                    checkNotNull(engine).release(); engine = null; engine = create()
                    pcm.take(); lastProvisionalText = ""
                    CaptionState.discontinuity(generation)
                }
                expectedSequence = chunk.sequence
                pcm.append(chunk.samples)
                val start = now()
                checkNotNull(engine).accept(chunk.samples)
                val duration = now() - start
                computeMs += duration; audioMs += chunk.samples.size / 16
                CaptionState.metrics(generation) { it.copy(asrMs = duration,
                    asrRtf = computeMs.toDouble() / maxOf(1, audioMs), audioDepth = audioQueue.size(),
                    backlogMs = (now() - chunk.capturedAt).coerceAtLeast(0)) }
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) { if (active.get()) fail("Recognition: ${t.message}") }
        finally { engine?.release(); capture.cancel(); pcm.take() }
    }
    private fun partial(text: String) {
        if (!active.get() || text.isBlank()) return
        val time = now()
        val row = CaptionState.source(generation, text, false, time) ?: return
        if (!fastEnabled || row.stableSource.length < 4 || row.stableSource == lastProvisionalText || time - lastProvisionalAt < 650) return
        lastProvisionalAt = time; lastProvisionalText = row.stableSource
        provisional.offer(TranslationRequest(row.key, row.stableSource))
        depths()
    }
    private fun endpoint(text: String) {
        val audio = pcm.take()
        if (!active.get() || text.isBlank()) return
        val at = now()
        val row = CaptionState.source(generation, text, true, at) ?: return
        lastProvisionalText = ""
        // Endpoint translation also gets a fast pass. A final result has a higher rank.
        if (fastEnabled) provisional.offer(TranslationRequest(row.key, text, at))
        val request = TranslationRequest(row.key, text, at)
        val admitted = correctionEnabled && correctionReady && audio != null &&
            CorrectionPolicy.admit(config.profile, audio.size, audioQueue.size(), finals.size(), correctionBusy.get(), correctionRtf) &&
            correctionBusy.compareAndSet(false, true)
        enqueueFinal(request)
        if (admitted) correctionQueue.offer(CorrectionRequest(request, audio!!))
        else {
            audio?.fill(0f)
            if (correctionEnabled) CaptionState.metrics(generation) { it.copy(skippedCorrections = it.skippedCorrections + 1) }
        }
        depths()
    }
    private fun correctLoop() {
        if (!correctionEnabled) return
        var engine: com.asr.live.asr.EndpointCorrector? = null
        try {
            ModelStore.verify(ctx, ModelCatalog.PARAKEET)
            if (!active.get()) return
            engine = com.asr.live.asr.EndpointCorrector(ModelStore.dir(ctx, ModelCatalog.PARAKEET.id))
            correctionReady = true
            while (active.get()) {
                val job = correctionQueue.poll()
                if (job == null) { Thread.sleep(20); continue }
                try {
                    if (!CaptionState.current(job.request.key)) continue
                    val started = now()
                    val candidate = engine.decode(job.samples)
                    val elapsed = now() - started
                    correctionRtf = elapsed.toDouble() / maxOf(1, job.samples.size / 16)
                    CaptionState.metrics(generation) { it.copy(correctionMs = elapsed, correctionRtf = correctionRtf) }
                    if (active.get() && finals.size() == 0 && CorrectionPolicy.accept(job.request.text, candidate,
                            now() - (job.request.endpointAt ?: started), audioQueue.size())) {
                        CaptionState.revise(job.request.key, candidate)?.let {
                            enqueueFinal(job.request.copy(key = it.key, text = candidate)); depths()
                        }
                    } else CaptionState.metrics(generation) { it.copy(skippedCorrections = it.skippedCorrections + 1) }
                } finally { job.samples.fill(0f); correctionBusy.set(false) }
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            if (active.get()) CaptionState.error(generation, "Optional correction disabled: ${t.message}")
        } finally { correctionReady = false; engine?.close() }
    }
    private fun enqueueFinal(request: TranslationRequest) {
        finals.offer(request)?.let {
            CaptionState.skip(it.key, "Translation skipped: queue full")
            CaptionState.metrics(generation) { m -> m.copy(skippedTranslations = m.skippedTranslations + 1) }
        }
    }
    private fun createTranslator(isFinal: Boolean): LocalTranslator {
        if (config.quality == TranslationQuality.ML_KIT) return MlKitTranslator(config.profile.source, config.profile.target)
        val bundle = if (isFinal) config.quality.bundleId!! else "opus-nl-en"
        val directory = TranslationModels.verify(ctx, bundle)
        return NativeTranslator(if (isFinal) java.io.File(directory, "model.gguf") else directory,
            if (isFinal) minOf(config.threads, 4) else 2, !isFinal, config.profile, config.glossary)
    }
    private fun translateLoop(isFinal: Boolean) {
        if (!isFinal && !fastEnabled) return
        var translator: LocalTranslator? = null
        val queue = if (isFinal) finals else provisional
        try {
            translator = createTranslator(isFinal)
            if (isFinal) finalTranslator = translator else fastTranslator = translator
            while (active.get()) {
                val request = queue.poll()
                if (request == null) { Thread.sleep(20); continue }
                depths()
                if (!CaptionState.current(request.key)) continue
                if (request.endpointAt != null && now() - request.endpointAt > 15_000) {
                    if (isFinal) CaptionState.skip(request.key, "Translation skipped: stale backlog")
                    continue
                }
                val started = now()
                try {
                    val result = translator.translate(request.text)
                    if (!active.get()) break
                    val accepted = CaptionState.translated(request.key, result, if (isFinal) 2 else 0, isFinal)
                    val elapsed = now() - started
                    CaptionState.metrics(generation) { m -> m.copy(translationMs = elapsed,
                        provisionalLatencyMs = if (accepted && !isFinal && request.endpointAt != null) now() - request.endpointAt else m.provisionalLatencyMs,
                        finalLatencyMs = if (accepted && isFinal && request.endpointAt != null) now() - request.endpointAt else m.finalLatencyMs) }
                } catch (_: InterruptedException) { break }
                catch (t: Exception) { if (isFinal && active.get()) CaptionState.skip(request.key, "Translation failed: ${t.message}") }
            }
        } catch (t: Throwable) { if (active.get()) fail("Translator: ${t.message}") }
        finally { translator?.close(); if (isFinal) finalTranslator = null else fastTranslator = null }
    }
    private fun depths() = CaptionState.metrics(generation) {
        it.copy(provisionalDepth = provisional.size(), finalDepth = finals.size())
    }
    private fun now() = SystemClock.elapsedRealtime()
}
