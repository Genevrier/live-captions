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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class AudioChunk(val samples: FloatArray, val capturedAtNs: Long, val sequence: Long)
internal data class CorrectionRequest(val request: TranslationRequest, val samples: FloatArray)
internal data class TranslationRequest(
    val key: SegmentKey,
    val text: String,
    val endpointAt: Long? = null,
    val sourceAudioAtNs: Long? = null,
    val stableSourceAtNs: Long? = null,
    val benchmarkOnly: Boolean = false,
    val isFinal: Boolean = false,
    val correctionSource: String? = null,
)

/** Each native object has a single owning worker. Cancellation never releases an in-use object. */
class CaptionSession(
    private val ctx: Context,
    val generation: Long,
    private val config: SessionConfig,
    private val onFailure: (String) -> Unit,
) {
    private val active = AtomicBoolean(true)
    private val stopping = AtomicBoolean(false)
    private val asrFinished = AtomicBoolean(false)
    private val correctionEnabled = config.correction && config.profile.correctionSupported && ModelCatalog.byId(config.modelId)?.kind == EngineKind.NEMOTRON
    private val correctionFinished = AtomicBoolean(!correctionEnabled)
    private val audioQueue = BoundedMailbox<AudioChunk>(16)
    private val provisional = BoundedMailbox<TranslationRequest>(1)
    private val qualityProvisional = BoundedMailbox<TranslationRequest>(1)
    private val finals = BoundedMailbox<TranslationRequest>(2)
    private val correctionQueue = BoundedMailbox<CorrectionRequest>(1)
    private val correctionBusy = AtomicBoolean(false)
    @Volatile private var correctionReady = false
    @Volatile private var correctionRtf = 0.0
    private val corrector = Thread(::correctLoop, "endpoint-correction")
    private val sequence = AtomicLong()
    @Volatile private var activeFinalEndpoint: Long? = null
    @Volatile private var segmentAudioStartedAtNs: Long? = null
    @Volatile private var opusTranslator: LocalTranslator? = null
    @Volatile private var finalTranslator: LocalTranslator? = null
    private val opusBenchmarkEnabled = config.opusBenchmarkEnabled
    private val workersReady = CountDownLatch(1 + (if (opusBenchmarkEnabled) 1 else 0) + (if (correctionEnabled) 1 else 0))
    private val pcm = PcmBuffer()
    private var lastProvisionalAt = 0L
    private var lastProvisionalText = ""
    private val info = ModelCatalog.byId(config.modelId) ?: ModelCatalog.DEFAULT
    private val capture = AudioCapture(ctx,
        onChunk = { samples ->
            if (active.get()) {
                val dropped = audioQueue.offer(AudioChunk(samples, SystemClock.elapsedRealtimeNanos(), sequence.incrementAndGet()))
                CaptionState.metrics(generation) { it.copy(audioDepth = audioQueue.size(),
                    droppedAudioMs = it.droppedAudioMs + (dropped?.samples?.size ?: 0) / 16) }
            }
        },
        onStarted = { if (active.get()) CaptionState.listening(generation) },
        onFinished = { if (stopping.get()) audioQueue.closeForDrain() },
        onError = { fail("Microphone: ${it.message}") })
    private val asr = Thread(::recognize, "recognition")
    private val opus = Thread(::opusLoop, "opus-ab-translation")
    private val final = Thread(::translateLoop, "hy-translation")
    fun start() { if (opusBenchmarkEnabled) opus.start(); final.start(); corrector.start(); asr.start() }
    /** Gracefully stop input, drain queued PCM, flush ASR and finish caption translations. */
    fun stop() {
        if (active.get() && stopping.compareAndSet(false, true)) capture.stopCapturing()
    }
    fun cancel() {
        active.set(false); capture.cancel()
        opusTranslator?.cancel(); finalTranslator?.cancel()
        audioQueue.close(); provisional.close(); qualityProvisional.close(); finals.close(); correctionQueue.close().forEach { it.samples.fill(0f) }
        asr.interrupt(); opus.interrupt(); final.interrupt(); corrector.interrupt()
    }
    fun join() { asr.join(); capture.join(); if (opusBenchmarkEnabled) opus.join(); final.join(); corrector.join() }
    private fun fail(message: String) {
        if (active.compareAndSet(true, false)) { onFailure(message); cancel() }
    }
    private fun recognize() {
        var engine: com.asr.live.asr.AsrEngine? = null
        // AudioCapture sends one 100 ms (1600 samples at 16 kHz) unit per
        // worker iteration. Nemotron's model chunk profile is not the ADPF
        // workload duration: it can contain several microphone callbacks.
        val performanceHint = WorkerPerformanceHint(ctx, AudioCapture.CHUNK_DURATION_MS)
        CaptionState.metrics(generation) { it.copy(adpfActive = performanceHint.enabled) }
        val continuity = AudioContinuity()
        var qnnFailed = false
        var audioMs = 0L
        var computeMs = 0L
        try {
            require(info.supports(config.profile.source)) { "${info.shortName} does not support ${config.profile.source}" }
            require(!config.qnn || info.kind != EngineKind.NEMOTRON || info.id == ModelCatalog.NEMOTRON.id) { "QNN currently supports only the separate 560 ms context" }
            ModelStore.verify(ctx, info)
            if (!active.get()) return
            fun create(): com.asr.live.asr.AsrEngine = if (config.qnn && !qnnFailed && info.kind == EngineKind.NEMOTRON)
                com.asr.live.asr.QnnEngine(ctx, config.profile.source, config.threads, ::partial, ::endpoint,
                    { backend -> if (backend.startsWith("CPU")) qnnFailed = true; CaptionState.metrics(generation) { it.copy(backend = backend) } },
                    { pcm.invalidate(); CaptionState.discontinuity(generation) })
            else EngineFactory.create(ctx, info, config.profile.source, "transcribe", ::partial, ::endpoint, config.threads)
            engine = create()
            if (!active.get()) return
            while (active.get() && !workersReady.await(250, TimeUnit.MILLISECONDS)) { }
            if (!active.get()) return
            capture.start()
            while (active.get()) {
                val chunk = audioQueue.poll()
                if (chunk == null) {
                    if (stopping.get()) {
                        checkNotNull(engine).finish()
                        break
                    }
                    Thread.sleep(10); continue
                }
                if (continuity.gap(chunk.sequence)) {
                    // Audio loss is a discontinuity: do not join speech from either side into one hypothesis.
                    checkNotNull(engine).release(); engine = null; engine = create()
                    pcm.take(); lastProvisionalText = ""
                    segmentAudioStartedAtNs = null
                    CaptionState.discontinuity(generation)
                    val discarded = chunk.samples.size + audioQueue.drain().sumOf { it.samples.size }
                    CaptionState.metrics(generation) { it.copy(droppedAudioMs = it.droppedAudioMs + discarded / 16) }
                    continuity.reset()
                    continue
                }
                if (segmentAudioStartedAtNs == null) segmentAudioStartedAtNs = chunk.capturedAtNs
                pcm.append(chunk.samples)
                val start = now()
                checkNotNull(engine).accept(chunk.samples)
                val duration = now() - start
                performanceHint.report(duration)
                computeMs += duration; audioMs += chunk.samples.size / 16
                CaptionState.metrics(generation) { it.copy(asrMs = duration,
                    asrRtf = computeMs.toDouble() / maxOf(1, audioMs), audioDepth = audioQueue.size(),
                    backlogMs = ((nowNs() - chunk.capturedAtNs) / 1_000_000L).coerceAtLeast(0), captionBacklogMs = captionAge()) }
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) { if (active.get()) fail("Recognition: ${t.message}") }
        finally {
            performanceHint.close(); engine?.release(); capture.cancel(); pcm.take()
            asrFinished.set(true)
        }
    }
    private fun partial(text: String) {
        if (!active.get() || text.isBlank()) return
        val time = now()
        val row = CaptionState.source(generation, text, false, time) ?: return
        if (!ProvisionalTranslationPolicy.shouldTranslate(lastProvisionalText, row.stableSource, time, lastProvisionalAt)) return
        lastProvisionalAt = time; lastProvisionalText = row.stableSource
        val request = TranslationRequest(row.key, row.stableSource,
            sourceAudioAtNs = segmentAudioStartedAtNs ?: nowNs(), stableSourceAtNs = nowNs())
        provisional.offer(request)
        if (opusBenchmarkEnabled) qualityProvisional.offer(request.copy(benchmarkOnly = true))
        depths()
    }
    private fun endpoint(text: String) {
        val audio = pcm.take()
        if (!active.get() || text.isBlank()) return
        val at = now()
        val row = CaptionState.source(generation, text, true, at) ?: return
        lastProvisionalText = ""
        // Endpoints use Hy once; OPUS is only a live-prefix A/B translator.
        val request = TranslationRequest(row.key, text, endpointAt = at,
            sourceAudioAtNs = segmentAudioStartedAtNs ?: nowNs(), stableSourceAtNs = nowNs(), isFinal = true)
        segmentAudioStartedAtNs = null
        val admitted = correctionEnabled && correctionReady && audio != null &&
            CorrectionPolicy.admit(config.profile, audio.size, audioQueue.size(), finals.size(),
                correctionBusy.get() || activeFinalEndpoint != null, correctionRtf) &&
            correctionBusy.compareAndSet(false, true)
        if (admitted) {
            val dropped = correctionQueue.offer(CorrectionRequest(request, audio!!))
            if (dropped != null) {
                dropped.samples.fill(0f)
                correctionBusy.set(false)
                enqueueFinal(request)
            }
        } else {
            audio?.fill(0f)
            enqueueFinal(request)
            if (correctionEnabled) CaptionState.metrics(generation) { it.copy(skippedCorrections = it.skippedCorrections + 1) }
        }
        depths()
    }
    private fun correctLoop() {
        if (!correctionEnabled) return
        var engine: com.asr.live.asr.EndpointCorrector? = null
        val performanceHint = WorkerPerformanceHint(ctx, 1500)
        try {
            ModelStore.verify(ctx, ModelCatalog.PARAKEET)
            if (!active.get()) return
            engine = com.asr.live.asr.EndpointCorrector(ModelStore.dir(ctx, ModelCatalog.PARAKEET.id), config.correctionThreads)
            engine.warmUp()
            correctionReady = true
            while (active.get() || (stopping.get() && !asrFinished.get())) {
                val job = correctionQueue.poll()
                if (job == null) {
                    if (stopping.get() && asrFinished.get() && !correctionBusy.get()) break
                    Thread.sleep(20); continue
                }
                try {
                    if (!CaptionState.current(job.request.key)) continue
                    val started = now()
                    val candidate = engine.decode(job.samples)
                    val elapsed = now() - started
                    performanceHint.report(elapsed)
                    correctionRtf = elapsed.toDouble() / maxOf(1, job.samples.size / 16)
                    CaptionState.metrics(generation) { it.copy(correctionMs = elapsed, correctionRtf = correctionRtf) }
                    if (active.get() && finals.size() == 0 && activeFinalEndpoint == null && CorrectionPolicy.accept(job.request.text, candidate,
                            now() - (job.request.endpointAt ?: started), audioQueue.size())) {
                        // Keep the existing caption pair visible until Hy has translated the
                        // second hypothesis. The accepted source and translation are published
                        // together as one new revision.
                        enqueueFinal(job.request.copy(text = candidate, correctionSource = candidate)); depths()
                    } else {
                        CaptionState.metrics(generation) { it.copy(skippedCorrections = it.skippedCorrections + 1) }
                        if (active.get()) enqueueFinal(job.request)
                    }
                } catch (t: Throwable) {
                    if (t is InterruptedException) throw t
                    if (active.get()) {
                        CaptionState.error(generation, "Optional correction failed; using Nemotron text: ${t.message}")
                        enqueueFinal(job.request)
                    }
                } finally { job.samples.fill(0f); correctionBusy.set(false) }
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            if (active.get()) CaptionState.error(generation, "Optional correction disabled: ${t.message}")
        } finally {
            workersReady.countDown(); performanceHint.close(); correctionReady = false; engine?.close()
            correctionFinished.set(true)
        }
    }
    private fun enqueueFinal(request: TranslationRequest) {
        finals.offer(request)?.let {
            CaptionState.skip(it.key, "Translation skipped: queue full")
            CaptionState.metrics(generation) { m -> m.copy(skippedTranslations = m.skippedTranslations + 1) }
        }
    }
    private fun createTranslator(): LocalTranslator {
        if (config.quality == TranslationQuality.ML_KIT) return MlKitTranslator(config.profile.source, config.profile.target)
        val bundle = checkNotNull(config.quality.bundleId)
        val directory = TranslationModels.verify(ctx, bundle)
        val modelIds = listOfNotNull(info.id, bundle, if (opusBenchmarkEnabled) config.profile.fastBundle else null,
            if (correctionEnabled) ModelCatalog.PARAKEET.id else null,
            if (config.qnn) ModelCatalog.NEMOTRON_QNN.id else null).distinct()
        val estimatedFiles = modelIds.sumOf { id ->
            ModelStore.dir(ctx, id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        CaptionState.metrics(generation) { it.copy(estimatedModelsKb = estimatedFiles / 1024) }
        val maxModelBudget = 11L * 1024 * 1024 * 1024
        if (estimatedFiles > maxModelBudget)
            error("Selected resident models exceed the 11 GiB Max Quality budget")
        if (modelBytesFor(bundle) > 3_000_000_000L && !MemoryUsage.canLoad(ctx, estimatedFiles))
            error("Not enough available RAM to keep selected models resident with 2 GiB system headroom")
        val preferOpenCl = config.gpuTranslation && com.asr.live.BuildConfig.OPENCL_ENABLED
        return NativeTranslator(java.io.File(directory, "model.gguf"),
            minOf(config.threads, 4), false, config.profile, config.glossary,
            preferOpenCl, ctx.getDir("llama-opencl-cache", Context.MODE_PRIVATE),
            config.translationBatch, config.translationUbatch)
    }
    private fun createOpusTranslator(): LocalTranslator {
        val bundle = checkNotNull(config.profile.fastBundle)
        val directory = TranslationModels.verify(ctx, bundle)
        return NativeTranslator(directory, 2, true, config.profile, "", false,
            ctx.getDir("llama-opencl-cache", Context.MODE_PRIVATE),
            config.translationBatch, config.translationUbatch)
    }
    private fun modelBytesFor(id: String) = TranslationModels.bundle(ctx, id).size
    private fun translateLoop() {
        var translator: LocalTranslator? = null
        val performanceHint = WorkerPerformanceHint(ctx, 750)
        try {
            val engine = createTranslator()
            translator = engine
            finalTranslator = engine
            engine.warmUp()
            CaptionState.metrics(generation) { it.copy(translationBackend = engine.backend) }
            workersReady.countDown()
            while (active.get() || (stopping.get() && (!asrFinished.get() || !correctionFinished.get() ||
                    finals.size() > 0 || provisional.size() > 0 || qualityProvisional.size() > 0))) {
                // Endpoints have strict priority. Each live queue has capacity one and
                // replaces its obsolete prefix, so partial translation can never backlog.
                val request = finals.poll() ?: if (opusBenchmarkEnabled) qualityProvisional.poll() else provisional.poll()
                if (request == null) { Thread.sleep(20); continue }
                depths()
                if (!CaptionState.current(request.key)) continue
                if (request.endpointAt != null && now() - request.endpointAt > 15_000) {
                    if (!request.benchmarkOnly) CaptionState.skip(request.key, "Translation skipped: stale backlog")
                    continue
                }
                val started = now()
                val isFinal = request.isFinal
                if (isFinal) activeFinalEndpoint = request.endpointAt
                try {
                    val result = engine.translate(request.text)
                    if (!active.get()) break
                    val elapsed = now() - started
                    performanceHint.report(elapsed)
                    val timings = engine.timings
                    val accepted = if (request.benchmarkOnly) {
                        CaptionState.comparison(generation, request.key, request.text,
                            ComparisonEngine.HY_MT2, result, elapsed)
                        false
                    } else if (request.correctionSource != null) {
                        CaptionState.revisedTranslated(request.key, request.correctionSource, result) != null
                    } else CaptionState.translated(request.key, result, if (isFinal) 2 else 0, isFinal)
                    CaptionState.metrics(generation) { m -> m.copy(translationMs = elapsed,
                        translationPrefillMs = timings.prefillMs, translationDecodeMs = timings.decodeMs,
                        translationFirstTokenMs = timings.firstTokenMs,
                        translationTokensPerSecond = timings.tokensPerSecond,
                        translationBackend = engine.backend,
                        audioToProvisionalMs = if (accepted && !isFinal) elapsedSinceNs(request.sourceAudioAtNs) else m.audioToProvisionalMs,
                        stableToProvisionalMs = if (accepted && !isFinal) elapsedSinceNs(request.stableSourceAtNs) else m.stableToProvisionalMs,
                        audioToFinalMs = if (accepted && isFinal) elapsedSinceNs(request.sourceAudioAtNs) else m.audioToFinalMs,
                        finalLatencyMs = if (accepted && isFinal && request.endpointAt != null) now() - request.endpointAt else m.finalLatencyMs) }
                } catch (_: InterruptedException) { break }
                catch (t: Exception) { if (!request.benchmarkOnly && active.get()) CaptionState.skip(request.key, "Translation failed: ${t.message}") }
                finally { if (isFinal) activeFinalEndpoint = null }
            }
        } catch (t: Throwable) { if (active.get()) fail("Translator: ${t.message}") }
        finally { performanceHint.close(); translator?.close(); finalTranslator = null }
    }
    private fun opusLoop() {
        if (!opusBenchmarkEnabled) return
        var translator: LocalTranslator? = null
        var readySignaled = false
        val performanceHint = WorkerPerformanceHint(ctx, 750)
        try {
            val engine = createOpusTranslator()
            translator = engine
            opusTranslator = engine
            engine.warmUp()
            workersReady.countDown()
            readySignaled = true
            while (active.get() || (stopping.get() && (!asrFinished.get() || provisional.size() > 0))) {
                val request = provisional.poll()
                if (request == null) { Thread.sleep(20); continue }
                depths()
                if (!CaptionState.current(request.key)) continue
                val started = now()
                try {
                    val result = engine.translate(request.text)
                    if (!active.get()) break
                    val elapsed = now() - started
                    performanceHint.report(elapsed)
                    CaptionState.comparison(generation, request.key, request.text,
                        ComparisonEngine.OPUS, result, elapsed)
                    CaptionState.translated(request.key, result, 0, false)
                } catch (_: InterruptedException) { break }
                catch (t: Exception) { if (active.get()) CaptionState.error(generation, "OPUS A/B failed: ${t.message}") }
            }
        } catch (t: Throwable) {
            if (active.get()) CaptionState.error(generation, "OPUS A/B unavailable; Hy-MT2 captions continue: ${t.message}")
            if (!readySignaled) workersReady.countDown()
        }
        finally { performanceHint.close(); translator?.close(); opusTranslator = null }
    }

    private fun depths() = CaptionState.metrics(generation) {
        it.copy(provisionalDepth = provisional.size() + qualityProvisional.size(), finalDepth = finals.size(), captionBacklogMs = captionAge())
    }
    private fun captionAge(): Long = listOfNotNull(activeFinalEndpoint, finals.peek()?.endpointAt).minOrNull()?.let { (now() - it).coerceAtLeast(0) } ?: 0
    private fun now() = SystemClock.elapsedRealtime()
    private fun nowNs() = SystemClock.elapsedRealtimeNanos()
    private fun elapsedSinceNs(timestampNs: Long?): Long = timestampNs?.let { ((nowNs() - it) / 1_000_000L).coerceAtLeast(0) } ?: 0L
}
