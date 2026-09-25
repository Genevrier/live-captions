package com.asr.live.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.asr.live.asr.EngineFactory
import com.asr.live.audio.AudioCapture
import com.asr.live.i18n.MlKitTranslator
import com.asr.live.i18n.LocalTranslator
import com.asr.live.i18n.NativeTranslator
import com.asr.live.model.*
import com.asr.live.pipeline.*
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class AudioChunk(val samples: FloatArray, val capturedAtNs: Long, val sequence: Long)
internal data class CorrectionRequest(val request: TranslationRequest, val samples: FloatArray, val enqueuedAtNs: Long)
internal data class TranslationRequest(
    val key: SegmentKey,
    val text: String,
    val portionIdentity: TranslationPortionIdentity = TranslationPortionIdentity(key.session, key.id, text),
    val requestId: Long = 0,
    val createdAtNs: Long = 0,
    val queuedAtNs: Long = 0,
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
    private val correctionEnabled = config.correction && config.profile.correctionSupported && ModelCatalog.byId(config.modelId)?.kind == EngineKind.NEMOTRON
    private val lifecycle = SessionLifecycle(correctionEnabled)
    private val failureReported = AtomicBoolean(false)
    private val audioQueue = BoundedMailbox<AudioChunk>(16)
    private val provisional = BoundedMailbox<TranslationRequest>(1)
    private val qualityProvisional = BoundedMailbox<TranslationRequest>(1)
    private val finals = BoundedMailbox<TranslationRequest>(2)
    private val correctionQueue = BoundedMailbox<CorrectionRequest>(1)
    private val correctionDecodeThreads = ConcurrentHashMap.newKeySet<Thread>()
    private val correctionBusy = AtomicBoolean(false)
    @Volatile private var correctionReady = false
    @Volatile private var correctionRtf = 0.0
    private val corrector = Thread(::correctLoop, "endpoint-correction")
    private val sequence = AtomicLong()
    private val requestSequence = AtomicLong()
    private val activeHyRequest = AtomicReference<TranslationRequest?>()
    private val hyScheduleLock = Any()
    private val sessionStartedAtNs = SystemClock.elapsedRealtimeNanos()
    private val captureFinished = AtomicBoolean(false)
    private val inferenceBusy = AtomicBoolean(false)
    @Volatile private var activeFinalEndpoint: Long? = null
    @Volatile private var segmentAudioStartedAtNs: Long? = null
    @Volatile private var lastPartialAtNs: Long? = null
    @Volatile private var opusTranslator: LocalTranslator? = null
    @Volatile private var finalTranslator: LocalTranslator? = null
    private val opusBenchmarkEnabled = config.opusBenchmarkEnabled
    private val startupWorkers = buildMap {
        put(WORKER_RECOGNITION, true)
        put(WORKER_HY_TRANSLATION, true)
        if (correctionEnabled) put(WORKER_CORRECTION, false)
        if (opusBenchmarkEnabled) put(WORKER_OPUS, false)
    }
    private val startupBarrier = WorkerStartupBarrier(startupWorkers)
    private val pcm = PcmBuffer()
    private var lastProvisionalAt = 0L
    private var lastProvisionalText = ""
    private val info = ModelCatalog.byId(config.modelId) ?: ModelCatalog.DEFAULT
    private val capture = AudioCapture(ctx,
        onChunk = { samples ->
            if (!lifecycle.isCancelled()) {
                val dropped = audioQueue.offer(AudioChunk(samples, SystemClock.elapsedRealtimeNanos(), sequence.incrementAndGet()))
                CaptionState.metrics(generation) { it.copy(audioDepth = audioQueue.size(),
                    droppedAudioMs = it.droppedAudioMs + (dropped?.samples?.size ?: 0) / 16) }
            }
        },
        onStarted = { if (!lifecycle.isCancelled()) { trace("microphone-started"); CaptionState.listening(generation) } },
        onFinished = {
            if (lifecycle.isStopping()) {
                audioQueue.closeForDrain()
                captureFinished.set(true)
                trace("microphone-finished", details = "queued=${audioQueue.size()}")
            }
        },
        onError = { fail("Microphone: ${it.message}") })
    private val asr = Thread(::recognize, "recognition")
    private val opus = Thread(::opusLoop, "opus-ab-translation")
    private val final = Thread(::translateLoop, "hy-translation")
    fun start() {
        trace("session-started")
        if (opusBenchmarkEnabled) opus.start()
        final.start(); corrector.start(); asr.start()
    }
    /** Gracefully stop input, drain queued PCM, flush ASR and finish caption translations. */
    fun stop() {
        if (lifecycle.requestStop()) { trace("stop-requested"); capture.stopCapturing() }
    }
    fun cancel() {
        if (!lifecycle.cancel()) return
        trace("cancel-requested")
        interruptWorkers()
    }
    private fun interruptWorkers() {
        runCatching { capture.cancel() }
        runCatching { opusTranslator?.cancel() }; runCatching { finalTranslator?.cancel() }
        audioQueue.close().forEach { it.samples.fill(0f) }
        provisional.close(); qualityProvisional.close(); finals.close()
        correctionQueue.close().forEach { it.samples.fill(0f) }
        listOf(asr, opus, final, corrector).forEach { runCatching { it.interrupt() } }
    }
    /** A short bounded wait. Service callers retry this from an IO coroutine. */
    fun join(timeoutMs: Long = 250): Boolean {
        val startedNs = System.nanoTime()
        val workersStopped = joinThreadsWithin(
            listOf(asr, capture.threadForJoin(), if (opusBenchmarkEnabled) opus else null, final, corrector), timeoutMs)
        if (!workersStopped) return false
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(
            TimeUnit.MILLISECONDS.toNanos(timeoutMs) - (System.nanoTime() - startedNs)).coerceAtLeast(0)
        return joinThreadsWithin(correctionDecodeThreads.toList(), remainingMs)
    }
    private fun fail(message: String) {
        if (failureReported.compareAndSet(false, true) && lifecycle.cancel()) {
            try { onFailure(message) } finally { interruptWorkers() }
        }
    }
    private fun recognize() {
        var engine: com.asr.live.asr.AsrEngine? = null
        var performanceHint: WorkerPerformanceHint? = null
        var startupReported = false
        // AudioCapture sends one 100 ms (1600 samples at 16 kHz) unit per
        // worker iteration. Nemotron's model chunk profile is not the ADPF
        // workload duration: it can contain several microphone callbacks.
        val continuity = AudioContinuity()
        val decodeStats = com.asr.live.asr.DecodeStatsAccumulator()
        var qnnFailed = false
        var audioMs = 0L
        var computeMs = 0L
        try {
            performanceHint = WorkerPerformanceHint(ctx, AudioCapture.CHUNK_DURATION_MS)
            CaptionState.metrics(generation) { it.copy(adpfActive = performanceHint?.enabled == true) }
            require(info.supports(config.profile.source)) { "${info.shortName} does not support ${config.profile.source}" }
            require(!config.qnn || info.kind != EngineKind.NEMOTRON || info.id == ModelCatalog.NEMOTRON.id) { "QNN currently supports only the separate 560 ms context" }
            ModelStore.verify(ctx, info)
            if (lifecycle.isCancelled()) return
            fun create(): com.asr.live.asr.AsrEngine = if (config.qnn && !qnnFailed && info.kind == EngineKind.NEMOTRON)
                com.asr.live.asr.QnnEngine(ctx, config.profile.source, config.threads, ::partial, ::endpoint,
                    { backend -> if (backend.startsWith("CPU")) qnnFailed = true; CaptionState.metrics(generation) { it.copy(backend = backend) } },
                    { pcm.invalidate(); CaptionState.discontinuity(generation) })
            else EngineFactory.create(ctx, info, config.profile.source, "transcribe", ::partial, ::endpoint, config.threads)
            engine = startupBarrier.initialize(WORKER_RECOGNITION) { create() }
            startupReported = true
            startupBarrier.await()
            startupBarrier.requiredFailures().firstOrNull()?.let {
                throw IllegalStateException("${it.worker} failed during startup: ${it.cause.message}", it.cause)
            }
            if (lifecycle.isCancelled()) return
            capture.start()
            while (!lifecycle.isCancelled()) {
                val chunk = audioQueue.poll()
                if (chunk == null) {
                    if (lifecycle.shouldFinishAsr(audioQueue.size() > 0, captureFinished.get(), inferenceBusy.get())) {
                        trace("asr-finish-started")
                        val finishStartedNs = nowNs()
                        checkNotNull(engine).finish()
                        val finishMs = (nowNs() - finishStartedNs) / 1_000_000L
                        computeMs += finishMs
                        val combinedDecodeStats = decodeStats.snapshot(checkNotNull(engine).decodeStats)
                        CaptionState.metrics(generation) { it.copy(asrMs = finishMs,
                            asrComputeMs = computeMs, asrRtf = computeMs.toDouble() / maxOf(1, audioMs),
                            decodeCalls = combinedDecodeStats.calls, decodeMeanMs = combinedDecodeStats.meanNanos / 1_000_000.0,
                            decodeP50Ms = combinedDecodeStats.p50Nanos / 1_000_000L,
                            decodeP95Ms = combinedDecodeStats.p95Nanos / 1_000_000L,
                            decodeMaxMs = combinedDecodeStats.maxNanos / 1_000_000L) }
                        trace("asr-finish-complete", details = "decodeCalls=${combinedDecodeStats.calls}")
                        break
                    }
                    Thread.sleep(10); continue
                }
                if (continuity.gap(chunk.sequence)) {
                    // Audio loss is a discontinuity: do not join speech from either side into one hypothesis.
                    decodeStats.retire(checkNotNull(engine).decodeStats)
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
                val startNs = nowNs()
                inferenceBusy.set(true)
                val activeEngine = checkNotNull(engine)
                val duration = try {
                    activeEngine.accept(chunk.samples)
                    (nowNs() - startNs) / 1_000_000L
                } finally { inferenceBusy.set(false) }
                performanceHint?.report(duration)
                computeMs += duration; audioMs += chunk.samples.size / 16
                val combinedDecodeStats = decodeStats.snapshot(activeEngine.decodeStats)
                CaptionState.metrics(generation) { it.copy(asrMs = duration,
                    asrComputeMs = computeMs, asrRtf = computeMs.toDouble() / maxOf(1, audioMs),
                    decodeCalls = combinedDecodeStats.calls, decodeMeanMs = combinedDecodeStats.meanNanos / 1_000_000.0,
                    decodeP50Ms = combinedDecodeStats.p50Nanos / 1_000_000L,
                    decodeP95Ms = combinedDecodeStats.p95Nanos / 1_000_000L,
                    decodeMaxMs = combinedDecodeStats.maxNanos / 1_000_000L, audioDepth = audioQueue.size(),
                    backlogMs = ((nowNs() - chunk.capturedAtNs) / 1_000_000L).coerceAtLeast(0), captionBacklogMs = captionAge()) }
            }
        } catch (e: InterruptedException) {
            if (!startupReported) { failStartupIfUnreported(WORKER_RECOGNITION, e); startupReported = true }
        } catch (t: Throwable) {
            if (!startupReported) { failStartupIfUnreported(WORKER_RECOGNITION, t); startupReported = true }
            if (!lifecycle.isCancelled()) fail("Recognition: ${t.message}")
        }
        finally {
            if (!startupReported) failStartupIfUnreported(WORKER_RECOGNITION,
                CancellationException("Recognition stopped before becoming ready"))
            runCatching { performanceHint?.close() }
            runCatching { engine?.release() }
            capture.cancel(); pcm.take()
            lifecycle.markAsrFinished()
        }
    }
    private fun partial(text: String) {
        if (lifecycle.isCancelled() || text.isBlank()) return
        val time = now()
        val atNs = nowNs()
        val row = CaptionState.source(generation, text, false, time) ?: return
        lastPartialAtNs = atNs
        trace("asr-partial", row.key, details = "atNs=$atNs")
        cancelHyIfIncompatible(row)
        if (!ProvisionalTranslationPolicy.shouldTranslate(lastProvisionalText, row.stableSource, time, lastProvisionalAt)) return
        lastProvisionalAt = time; lastProvisionalText = row.stableSource
        val request = TranslationRequest(row.key, row.stableSource,
            requestId = requestSequence.incrementAndGet(), createdAtNs = atNs, queuedAtNs = atNs,
            sourceAudioAtNs = segmentAudioStartedAtNs ?: atNs, stableSourceAtNs = atNs)
        trace("translation-requested", row.key, request.requestId,
            "branch=provisional createdAtNs=${request.createdAtNs} queuedAtNs=${request.queuedAtNs}")
        provisional.offer(request)?.let { rejectRequest(it, "provisional request replaced by newer text") }
        if (opusBenchmarkEnabled) qualityProvisional.offer(request.copy(benchmarkOnly = true))
        depths()
    }
    private fun endpoint(text: String) {
        val audio = pcm.take()
        if (lifecycle.isCancelled() || text.isBlank()) return
        val at = now()
        val atNs = nowNs()
        val row = CaptionState.source(generation, text, true, at) ?: return
        val endpointWaitMs = lastPartialAtNs?.let { ((atNs - it) / 1_000_000L).coerceAtLeast(0) }
        lastPartialAtNs = null
        CaptionState.metrics(generation) { it.copy(endpointWaitMs = endpointWaitMs) }
        trace("asr-endpoint", row.key, details = "atNs=$atNs endpointWaitMs=${endpointWaitMs ?: -1}")
        lastProvisionalText = ""
        // Endpoints use Hy once; OPUS is only a live-prefix A/B translator.
        val request = TranslationRequest(row.key, row.source,
            requestId = requestSequence.incrementAndGet(), createdAtNs = atNs, queuedAtNs = atNs,
            endpointAt = at, sourceAudioAtNs = segmentAudioStartedAtNs ?: atNs,
            stableSourceAtNs = atNs, isFinal = true)
        segmentAudioStartedAtNs = null
        val admitted = correctionEnabled && correctionReady && audio != null &&
            CorrectionPolicy.admit(config.profile, audio.size, audioQueue.size(), finals.size(),
                correctionBusy.get() || activeFinalEndpoint != null, correctionRtf) &&
            correctionBusy.compareAndSet(false, true)
        if (admitted) {
            trace("correction-queued", row.key, request.requestId, "queuedAtNs=$atNs")
            val dropped = correctionQueue.offer(CorrectionRequest(request, audio!!, atNs))
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
        var performanceHint: WorkerPerformanceHint? = null
        var startupReported = false
        var engineRetiredByDecode = false
        try {
            performanceHint = WorkerPerformanceHint(ctx, 1500)
            if (lifecycle.isCancelled()) return
            engine = startupBarrier.initialize(WORKER_CORRECTION) {
                ModelStore.verify(ctx, ModelCatalog.PARAKEET)
                com.asr.live.asr.EndpointCorrector(ModelStore.dir(ctx, ModelCatalog.PARAKEET.id), config.correctionThreads).also { it.warmUp() }
            }
            correctionReady = true
            startupReported = true
            while (lifecycle.shouldRunCorrection(correctionQueue.size() > 0, correctionBusy.get())) {
                val job = correctionQueue.poll()
                if (job == null) {
                    Thread.sleep(20); continue
                }
                var samplesOwnedByDecode = false
                try {
                    if (!CaptionState.current(job.request.key)) continue
                    val correctionWaitMs = ((nowNs() - job.enqueuedAtNs) / 1_000_000L).coerceAtLeast(0)
                    CaptionState.metrics(generation) { it.copy(correctionWaitMs = correctionWaitMs) }
                    trace("correction-started", job.request.key, job.request.requestId,
                        "queueWaitMs=$correctionWaitMs")
                    val started = now()
                    val candidate = decodeWithinDeadline(checkNotNull(engine), job,
                        CorrectionPolicy.remainingNanos(job.enqueuedAtNs, nowNs()),
                        onWorkerStarted = { samplesOwnedByDecode = true },
                        onEngineRetired = { engineRetiredByDecode = true })
                    val elapsed = now() - started
                    performanceHint?.report(elapsed)
                    correctionRtf = elapsed.toDouble() / maxOf(1, job.samples.size / 16)
                    CaptionState.metrics(generation) { it.copy(correctionMs = elapsed, correctionRtf = correctionRtf) }
                    trace("correction-complete", job.request.key, job.request.requestId, "computeMs=$elapsed")
                    if (!lifecycle.isCancelled() && finals.size() == 0 && activeFinalEndpoint == null && CorrectionPolicy.accept(job.request.text, candidate,
                            now() - (job.request.endpointAt ?: started), audioQueue.size())) {
                        // Keep the existing caption pair visible until Hy has translated the
                        // second hypothesis. The accepted source and translation are published
                        // together as one new revision.
                        enqueueFinal(job.request.copy(text = candidate, correctionSource = candidate,
                            portionIdentity = TranslationPortionIdentity(generation, job.request.key.id, candidate),
                            requestId = requestSequence.incrementAndGet(), queuedAtNs = nowNs())); depths()
                    } else {
                        CaptionState.metrics(generation) { it.copy(skippedCorrections = it.skippedCorrections + 1) }
                        if (!lifecycle.isCancelled()) enqueueFinal(job.request)
                    }
                } catch (t: Throwable) {
                    if (t is InterruptedException) throw t
                    if (t is TimeoutException) {
                        correctionReady = false
                        CaptionState.metrics(generation) { it.copy(skippedCorrections = it.skippedCorrections + 1) }
                        trace("correction-deadline-exceeded", job.request.key, job.request.requestId,
                            "deadlineMs=${CorrectionPolicy.MAX_LATENCY_MS}; fallback=unrevised")
                        if (!lifecycle.isCancelled()) enqueueFinal(job.request)
                        correctionQueue.drain().forEach { pending ->
                            pending.samples.fill(0f)
                            if (!lifecycle.isCancelled()) enqueueFinal(pending.request)
                        }
                        break
                    }
                    if (!lifecycle.isCancelled()) {
                        CaptionState.error(generation, "Optional correction failed; using Nemotron text: ${t.message}")
                        enqueueFinal(job.request)
                    }
                } finally {
                    if (!samplesOwnedByDecode) job.samples.fill(0f)
                    correctionBusy.set(false)
                }
            }
        } catch (e: InterruptedException) {
            if (!startupReported) { failStartupIfUnreported(WORKER_CORRECTION, e); startupReported = true }
        } catch (t: Throwable) {
            if (!startupReported) { failStartupIfUnreported(WORKER_CORRECTION, t); startupReported = true }
            if (!lifecycle.isCancelled()) CaptionState.error(generation, "Optional correction disabled: ${t.message}")
        } finally {
            if (!startupReported) failStartupIfUnreported(WORKER_CORRECTION,
                CancellationException("Correction worker stopped before becoming ready"))
            try {
                runCatching { performanceHint?.close() }
                correctionReady = false
                if (!engineRetiredByDecode) engine?.close()
            } finally { lifecycle.markCorrectionFinished() }
        }
    }

    /**
     * Enforce the Parakeet endpoint budget while keeping recognizer/audio ownership safe.
     * If native decode outlives its deadline, the caller queues the uncorrected final now;
     * the timed-out worker releases PCM and the recognizer only after decode actually exits.
     */
    private fun decodeWithinDeadline(
        engine: com.asr.live.asr.EndpointCorrector,
        job: CorrectionRequest,
        remainingNanos: Long,
        onWorkerStarted: () -> Unit,
        onEngineRetired: () -> Unit,
    ): String {
        if (remainingNanos <= 0) throw TimeoutException("Parakeet correction deadline elapsed in queue")
        val useGate = NativeResourceUseGate()
        fun closeAfterDecode() { runCatching { engine.close() } }
        val task = FutureTask<String> {
            if (!useGate.beginCall()) {
                job.samples.fill(0f)
                throw CancellationException("Parakeet decode cancelled before start")
            }
            try { engine.decode(job.samples) }
            finally {
                job.samples.fill(0f)
                if (useGate.finishCall()) closeAfterDecode()
            }
        }
        val worker = Thread({
            try { task.run() }
            finally { correctionDecodeThreads.remove(Thread.currentThread()) }
        }, "parakeet-decode-${job.request.requestId}").apply { isDaemon = true }
        correctionDecodeThreads.add(worker)
        try { worker.start() }
        catch (t: Throwable) {
            correctionDecodeThreads.remove(worker)
            job.samples.fill(0f)
            throw t
        }
        onWorkerStarted()
        try {
            return task.get(remainingNanos, TimeUnit.NANOSECONDS)
        } catch (timeout: TimeoutException) {
            onEngineRetired()
            val retirement = useGate.retire()
            task.cancel(retirement.callInUse)
            if (!retirement.callInUse) job.samples.fill(0f)
            if (retirement.closeNow) closeAfterDecode()
            throw timeout
        } catch (interrupted: InterruptedException) {
            onEngineRetired()
            val retirement = useGate.retire()
            task.cancel(retirement.callInUse)
            if (!retirement.callInUse) job.samples.fill(0f)
            if (retirement.closeNow) closeAfterDecode()
            throw interrupted
        } catch (failure: ExecutionException) {
            val cause = failure.cause ?: failure
            if (cause is Exception) throw cause
            throw RuntimeException(cause)
        }
    }

    private fun enqueueFinal(request: TranslationRequest) {
        val queued = request.copy(queuedAtNs = nowNs())
        trace("translation-requested", queued.key, queued.requestId,
            "branch=final createdAtNs=${queued.createdAtNs} queuedAtNs=${queued.queuedAtNs}")
        val dropped = synchronized(hyScheduleLock) {
            val lost = finals.offer(queued)
            activeHyRequest.get()?.takeIf { !it.isFinal }?.let { active ->
                // A final only preempts once it is ready to translate. Prefix extensions do
                // not cancel in-flight work, so useful provisional output is not starved.
                finalTranslator?.cancel(active.requestId)
                trace("hy-provisional-preempted", active.key, active.requestId,
                    "forFinalRequest=${queued.requestId}")
            }
            lost
        }
        dropped?.let {
            CaptionState.skip(it.key, "Translation skipped: queue full")
            CaptionState.metrics(generation) { m -> m.copy(skippedTranslations = m.skippedTranslations + 1) }
            rejectRequest(it, "final translation queue full")
        }
    }

    private fun cancelHyIfIncompatible(row: Caption) {
        synchronized(hyScheduleLock) {
            val active = activeHyRequest.get()?.takeIf { !it.isFinal } ?: return
            if (!CaptionState.canTranslatePortion(active.key, active.portionIdentity)) {
                finalTranslator?.cancel(active.requestId)
                trace("hy-provisional-invalidated", active.key, active.requestId,
                    "sourceRevision=${row.key.revision}")
            }
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
            config.translationThreads, false, config.profile, config.glossary,
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
        var performanceHint: WorkerPerformanceHint? = null
        var startupReported = false
        try {
            performanceHint = WorkerPerformanceHint(ctx, 750)
            val engine = startupBarrier.initialize(WORKER_HY_TRANSLATION) {
                createTranslator().also {
                    translator = it
                    finalTranslator = it
                    it.warmUp()
                }
            }
            translator = engine
            finalTranslator = engine
            CaptionState.metrics(generation) { it.copy(translationBackend = engine.backend) }
            startupReported = true
            while (lifecycle.shouldRunHyTranslation(finals.size() > 0 || provisional.size() > 0 || qualityProvisional.size() > 0)) {
                // Pick and publish the active request under the same lock used by final
                // admission. A final can therefore either win the poll or preempt the active
                // provisional; there is no race window in which it misses both.
                val request = synchronized(hyScheduleLock) {
                    (finals.poll() ?: if (opusBenchmarkEnabled) qualityProvisional.poll() else provisional.poll())
                        .also { activeHyRequest.set(it) }
                }
                if (request == null) { Thread.sleep(20); continue }
                depths()
                val requestCurrent = when {
                    request.benchmarkOnly || request.correctionSource != null -> CaptionState.current(request.key)
                    else -> CaptionState.canTranslatePortion(request.key, request.portionIdentity, request.isFinal)
                }
                if (!requestCurrent) {
                    rejectRequest(request, "caption portion incompatible before Hy translation")
                    clearActiveHyRequest(request)
                    continue
                }
                if (request.endpointAt != null && now() - request.endpointAt > 15_000) {
                    if (!request.benchmarkOnly) CaptionState.skip(request.key, "Translation skipped: stale backlog")
                    rejectRequest(request, "Hy request exceeded 15 second queue limit")
                    clearActiveHyRequest(request)
                    continue
                }
                val queueWaitMs = ((nowNs() - request.queuedAtNs) / 1_000_000L).coerceAtLeast(0)
                CaptionState.metrics(generation) { it.copy(hyWaitMs = queueWaitMs) }
                trace("hy-started", request.key, request.requestId, "queueWaitMs=$queueWaitMs")
                val startedNs = nowNs()
                val isFinal = request.isFinal
                if (isFinal) activeFinalEndpoint = request.endpointAt
                try {
                    val result = engine.translate(request.text, request.requestId, request.key.session,
                        request.key.id, request.key.revision) { progress ->
                        if (!request.benchmarkOnly && request.correctionSource == null &&
                            progress.provisional && progress.sessionId == request.key.session &&
                            progress.segmentId == request.key.id && progress.revision == request.key.revision &&
                            !lifecycle.isCancelled()) {
                            val shown = CaptionState.translatedPortion(request.key, request.portionIdentity,
                                progress.text, 0, false, request.requestId)
                            if (shown) trace("hy-progress-displayed", request.key, request.requestId,
                                "elapsedMs=${progress.elapsedMs}")
                        }
                    }
                    if (lifecycle.isCancelled()) break
                    val computedAtNs = nowNs()
                    val elapsed = (computedAtNs - startedNs) / 1_000_000L
                    performanceHint?.report(elapsed)
                    val timings = engine.timings
                    val outputKey = if (request.correctionSource != null) request.key.copy(revision = request.key.revision + 1) else request.key
                    val isCaptionResult = !request.benchmarkOnly
                    if (isCaptionResult) CaptionState.resultComputed(generation, outputKey, computedAtNs,
                        request.requestId, request.portionIdentity)
                    val displayStartedNs = nowNs()
                    val accepted = if (request.benchmarkOnly) {
                        CaptionState.comparison(generation, request.key, request.text,
                            ComparisonEngine.HY_MT2, result, elapsed)
                        false
                    } else if (request.correctionSource != null) {
                        CaptionState.revisedTranslated(request.key, request.correctionSource, result, request.requestId) != null
                    } else CaptionState.translatedPortion(request.key, request.portionIdentity, result,
                        if (isFinal) 2 else 0, isFinal, request.requestId)
                    val displayMs = (nowNs() - displayStartedNs) / 1_000_000L
                    if (isCaptionResult && !accepted)
                        rejectRequest(request, "caption portion incompatible before Hy result could publish", computedResult = true)
                    trace(if (accepted) "hy-displayed-to-state" else "hy-result-computed", outputKey,
                        request.requestId, "computeMs=$elapsed displayMs=$displayMs")
                    CaptionState.metrics(generation) { m -> m.copy(translationMs = elapsed,
                        hyComputeMs = elapsed, hyDisplayMs = displayMs, hyWaitMs = queueWaitMs,
                        translationPrefillMs = timings.prefillMs, translationDecodeMs = timings.decodeMs,
                        translationFirstTokenMs = timings.firstTokenMs,
                        translationInputTokens = timings.inputTokens, translationOutputTokens = timings.outputTokens,
                        translationCacheTokens = timings.cacheReusedTokens,
                        translationFirstVisibleMs = timings.firstVisibleMs, translationCompleteMs = timings.completeMs,
                        translationPrefillDecodeUs = timings.prefillDecodeUs,
                        translationPrefillSyncUs = timings.prefillSyncUs,
                        translationSamplingUs = timings.samplingUs,
                        translationDecodeComputeUs = timings.decodeComputeUs,
                        translationDecodeSyncUs = timings.decodeSyncUs,
                        translationDeviceBytes = timings.deviceBytesAllocated,
                        translationOffloadedLayers = timings.offloadedLayers,
                        translationTotalLayers = timings.totalLayers,
                        translationFallbackCount = timings.fallbackCount,
                        translationTokensPerSecond = timings.tokensPerSecond,
                        translationBackend = engine.backend,
                        audioToProvisionalMs = if (accepted && !isFinal) elapsedSinceNs(request.sourceAudioAtNs) else m.audioToProvisionalMs,
                        stableToProvisionalMs = if (accepted && !isFinal) elapsedSinceNs(request.stableSourceAtNs) else m.stableToProvisionalMs,
                        audioToFinalMs = if (accepted && isFinal) elapsedSinceNs(request.sourceAudioAtNs) else m.audioToFinalMs,
                        finalLatencyMs = if (accepted && isFinal && request.endpointAt != null) now() - request.endpointAt else m.finalLatencyMs) }
                } catch (_: InterruptedException) { break }
                catch (_: CancellationException) {
                    if (!lifecycle.isCancelled()) trace("hy-request-cancelled", request.key, request.requestId,
                        "provisional became obsolete or a final arrived")
                }
                catch (t: Exception) {
                    if (!request.benchmarkOnly && !lifecycle.isCancelled()) {
                        CaptionState.skip(request.key, "Translation failed: ${t.message}")
                        rejectRequest(request, "Hy translation failed")
                    }
                }
                finally {
                    if (isFinal) activeFinalEndpoint = null
                    clearActiveHyRequest(request)
                }
                }
        } catch (t: Throwable) {
            if (!startupReported) { failStartupIfUnreported(WORKER_HY_TRANSLATION, t); startupReported = true }
            if (!lifecycle.isCancelled()) fail("Translator: ${t.message}")
        } finally {
            if (!startupReported) failStartupIfUnreported(WORKER_HY_TRANSLATION,
                CancellationException("Translator stopped before becoming ready"))
            try {
                runCatching { performanceHint?.close() }
                translator?.close()
            } finally { finalTranslator = null }
        }
    }
    private fun opusLoop() {
        if (!opusBenchmarkEnabled) return
        var translator: LocalTranslator? = null
        var performanceHint: WorkerPerformanceHint? = null
        var startupReported = false
        try {
            performanceHint = WorkerPerformanceHint(ctx, 750)
            val engine = startupBarrier.initialize(WORKER_OPUS) {
                createOpusTranslator().also { it.warmUp() }
            }
            translator = engine
            opusTranslator = engine
            CaptionState.metrics(generation) { it.copy(opusBackend = engine.backend) }
            startupReported = true
            while (lifecycle.shouldRunOpusTranslation(provisional.size() > 0)) {
                val request = provisional.poll()
                if (request == null) { Thread.sleep(20); continue }
                depths()
                if (!CaptionState.current(request.key)) { rejectRequest(request, "caption superseded before OPUS translation"); continue }
                val queueWaitMs = ((nowNs() - request.queuedAtNs) / 1_000_000L).coerceAtLeast(0)
                CaptionState.metrics(generation) { it.copy(opusWaitMs = queueWaitMs) }
                trace("opus-started", request.key, request.requestId, "queueWaitMs=$queueWaitMs")
                val startedNs = nowNs()
                try {
                    val result = engine.translate(request.text, request.requestId)
                    if (lifecycle.isCancelled()) break
                    val computedAtNs = nowNs()
                    val elapsed = (computedAtNs - startedNs) / 1_000_000L
                    performanceHint?.report(elapsed)
                    CaptionState.comparison(generation, request.key, request.text,
                        ComparisonEngine.OPUS, result, elapsed)
                    CaptionState.resultComputed(generation, request.key, computedAtNs, request.requestId,
                        request.portionIdentity)
                    val displayStartedNs = nowNs()
                    val accepted = CaptionState.translatedPortion(request.key, request.portionIdentity,
                        result, 0, false, request.requestId)
                    val displayMs = (nowNs() - displayStartedNs) / 1_000_000L
                    if (!accepted) rejectRequest(request, "caption revision changed before OPUS result could publish")
                    val timings = engine.timings
                    trace(if (accepted) "opus-displayed-to-state" else "opus-result-computed",
                        request.key, request.requestId, "computeMs=$elapsed displayMs=$displayMs")
                    CaptionState.metrics(generation) { it.copy(opusComputeMs = elapsed,
                        opusWaitMs = queueWaitMs, opusPrefillMs = timings.prefillMs,
                        opusGenerationMs = timings.decodeMs, opusFirstTokenMs = timings.firstTokenMs,
                        opusTokensPerSecond = timings.tokensPerSecond) }
                } catch (_: InterruptedException) { break }
                catch (t: Exception) {
                    if (!lifecycle.isCancelled()) {
                        CaptionState.error(generation, "OPUS A/B failed: ${t.message}")
                        rejectRequest(request, "OPUS translation failed")
                    }
                }
            }
        } catch (t: Throwable) {
            if (!startupReported) { failStartupIfUnreported(WORKER_OPUS, t); startupReported = true }
            if (!lifecycle.isCancelled()) CaptionState.error(generation, "OPUS A/B unavailable; Hy-MT2 captions continue: ${t.message}")
        } finally {
            if (!startupReported) failStartupIfUnreported(WORKER_OPUS,
                CancellationException("OPUS worker stopped before becoming ready"))
            try {
                runCatching { performanceHint?.close() }
                translator?.close()
            } finally { opusTranslator = null }
        }
    }

    private fun depths() = CaptionState.metrics(generation) {
        it.copy(provisionalDepth = provisional.size() + qualityProvisional.size(), finalDepth = finals.size(), captionBacklogMs = captionAge())
    }
    private fun failStartupIfUnreported(worker: String, cause: Throwable) {
        if (worker !in startupBarrier.reportedWorkers()) startupBarrier.failed(worker, cause)
    }
    private fun clearActiveHyRequest(request: TranslationRequest) {
        synchronized(hyScheduleLock) { activeHyRequest.compareAndSet(request, null) }
    }
    private fun rejectRequest(request: TranslationRequest, reason: String, computedResult: Boolean = false) {
        CaptionState.resultRejected(generation, reason, computedResult)
        trace("result-rejected", request.key, request.requestId, reason)
    }
    private fun trace(event: String, key: SegmentKey? = null, requestId: Long = 0, details: String = "") {
        val atNs = nowNs()
        Log.d("CaptionPipeline", "session=$generation sessionStartNs=$sessionStartedAtNs atNs=$atNs " +
            "sessionElapsedNs=${(atNs - sessionStartedAtNs).coerceAtLeast(0)} segment=${key?.id ?: 0} " +
            "revision=${key?.revision ?: 0} request=$requestId event=$event $details")
    }
    private fun captionAge(): Long = listOfNotNull(activeFinalEndpoint, finals.peek()?.endpointAt).minOrNull()?.let { (now() - it).coerceAtLeast(0) } ?: 0
    private fun now() = SystemClock.elapsedRealtime()
    private fun nowNs() = SystemClock.elapsedRealtimeNanos()
    private fun elapsedSinceNs(timestampNs: Long?): Long = timestampNs?.let { ((nowNs() - it) / 1_000_000L).coerceAtLeast(0) } ?: 0L

    companion object {
        private const val WORKER_RECOGNITION = "recognition"
        private const val WORKER_HY_TRANSLATION = "hy-translation"
        private const val WORKER_CORRECTION = "parakeet-correction"
        private const val WORKER_OPUS = "opus-benchmark"
    }
}
