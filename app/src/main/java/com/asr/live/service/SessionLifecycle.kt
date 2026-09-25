package com.asr.live.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class WorkerStartupFailure(val worker: String, val cause: Throwable)

internal data class NativeResourceRetirement(val closeNow: Boolean, val callInUse: Boolean)

/** Serializes native-object retirement against one active native call. */
internal class NativeResourceUseGate {
    private var callStarted = false
    private var callFinished = false
    private var retirementRequested = false
    private var closed = false

    @Synchronized fun beginCall(): Boolean {
        if (retirementRequested) return false
        callStarted = true
        return true
    }

    @Synchronized fun finishCall(): Boolean {
        callFinished = true
        if (retirementRequested && !closed) {
            closed = true
            return true
        }
        return false
    }

    @Synchronized fun retire(): NativeResourceRetirement {
        retirementRequested = true
        val inUse = callStarted && !callFinished
        val closeNow = !inUse && !closed
        if (closeNow) closed = true
        return NativeResourceRetirement(closeNow, inUse)
    }
}

/** Each configured worker reports exactly one startup outcome. */
internal class WorkerStartupBarrier(private val workers: Map<String, Boolean>) {
    private val remaining = CountDownLatch(workers.size)
    private val completed = mutableSetOf<String>()
    private val failures = mutableMapOf<String, Throwable>()

    init {
        require(workers.isNotEmpty())
        require(workers.keys.all(String::isNotBlank))
    }

    @Synchronized fun ready(worker: String): Boolean = report(worker, null)

    @Synchronized fun failed(worker: String, cause: Throwable): Boolean = report(worker, cause)

    /** Reports READY only after initialization completes, or FAILED before propagating an error. */
    fun <T> initialize(worker: String, initializer: () -> T): T {
        return try { initializer().also { ready(worker) } }
        catch (t: Throwable) { failed(worker, t); throw t }
    }

    private fun report(worker: String, cause: Throwable?): Boolean {
        require(worker in workers) { "Unknown startup worker: $worker" }
        if (!completed.add(worker)) return false
        if (cause != null) failures[worker] = cause
        remaining.countDown()
        return true
    }

    fun await() = remaining.await()

    fun await(timeout: Long, unit: TimeUnit): Boolean = remaining.await(timeout, unit)

    @Synchronized fun requiredFailures(): List<WorkerStartupFailure> = failures
        .filterKeys { workers[it] == true }
        .map { WorkerStartupFailure(it.key, it.value) }

    /** Allocation-light probe for workers that poll while doing real work. */
    @Synchronized fun requiredFailure(exceptWorker: String? = null): WorkerStartupFailure? = failures.entries
        .firstOrNull { workers[it.key] == true && it.key != exceptWorker }
        ?.let { WorkerStartupFailure(it.key, it.value) }

    @Synchronized fun failures(): List<WorkerStartupFailure> = failures
        .map { WorkerStartupFailure(it.key, it.value) }

    @Synchronized fun reportedWorkers(): Set<String> = completed.toSet()
}

/** State shared by workers during graceful drain and immediate cancellation. */
internal class SessionLifecycle(correctionEnabled: Boolean) {
    private val cancelled = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val translationsAbandoned = AtomicBoolean(false)
    private val asrFinished = AtomicBoolean(false)
    private val correctionFinished = AtomicBoolean(!correctionEnabled)

    fun requestStop(): Boolean = !cancelled.get() && stopping.compareAndSet(false, true)

    fun cancel(): Boolean {
        val changed = cancelled.compareAndSet(false, true)
        stopping.set(true)
        return changed
    }

    /**
     * First shutdown escalation: give up on every remaining translation while leaving the ASR
     * flush running, so trailing speech still reaches the caption line as recognized source.
     */
    fun abandonTranslations(): Boolean = translationsAbandoned.compareAndSet(false, true)

    fun isCancelled() = cancelled.get()
    fun isStopping() = stopping.get()
    fun markAsrFinished() { asrFinished.set(true) }
    fun markCorrectionFinished() { correctionFinished.set(true) }

    fun shouldFinishAsr(hasQueuedAudio: Boolean, captureFinished: Boolean, inferenceBusy: Boolean = false): Boolean =
        stopping.get() && captureFinished && !hasQueuedAudio && !inferenceBusy

    fun shouldRunCorrection(hasQueuedWork: Boolean, busy: Boolean): Boolean =
        !cancelled.get() && (!stopping.get() || !asrFinished.get() || hasQueuedWork || busy)

    fun shouldRunHyTranslation(hasQueuedWork: Boolean): Boolean =
        shouldTranslate() && (!stopping.get() || !asrFinished.get() || !correctionFinished.get() || hasQueuedWork)

    /** Any translation at all: false once the session is cancelled or past the grace window. */
    fun shouldTranslate(): Boolean = !cancelled.get() && !translationsAbandoned.get()

    /**
     * Provisional translations only ever refresh the live caption line. Once Stop is requested
     * nobody will read them, so they are abandoned instead of extending shutdown.
     */
    fun shouldTranslateProvisional(): Boolean = shouldTranslate() && !stopping.get()

    /** The OPUS A/B translator produces benchmark-only output; Stop discards it outright. */
    fun shouldRunOpusTranslation(): Boolean = shouldTranslate() && !stopping.get()

    /** Model warm-up is pure preparation for future work; Stop makes it pointless. */
    fun shouldWarmUp(): Boolean = shouldTranslate() && !stopping.get()
}

/**
 * Bounds how long the UI may stay in STOPPING, in three steps.
 *
 *  1. Grace window: the ASR flush and one queued final translation get to finish normally.
 *  2. Abandon translations: everything translation-side is dropped, but the ASR flush keeps
 *     running so trailing speech still reaches the caption line as recognized source text.
 *  3. Force cancel, then release the UI: after the hard deadline STOPPED is reported even if a
 *     native call is still unwinding. Workers own and free their own native objects, so the
 *     session is still allowed to finish that cleanup on its own threads.
 */
internal class ShutdownDeadline(
    val graceMs: Long = GRACE_MS,
    val cancelMs: Long = CANCEL_MS,
    val hardMs: Long = HARD_MS,
    private val startedAtNs: Long,
    private val nowNs: () -> Long = System::nanoTime,
) {
    init {
        require(graceMs >= 0 && cancelMs >= graceMs && hardMs >= cancelMs) {
            "Each shutdown step must come after the grace window"
        }
    }

    fun elapsedMs(): Long = TimeUnit.NANOSECONDS.toMillis((nowNs() - startedAtNs).coerceAtLeast(0))
    fun abandonTranslationsDue(): Boolean = elapsedMs() >= graceMs
    fun forceCancelDue(): Boolean = elapsedMs() >= cancelMs
    fun releaseUiDue(): Boolean = elapsedMs() >= hardMs
    fun remainingUntilAbandonTranslationsMs(): Long = (graceMs - elapsedMs()).coerceAtLeast(0)
    fun remainingUntilForceCancelMs(): Long = (cancelMs - elapsedMs()).coerceAtLeast(0)
    fun remainingUntilReleaseUiMs(): Long = (hardMs - elapsedMs()).coerceAtLeast(0)

    companion object {
        /** Long enough for the ASR flush plus one queued final translation on device. */
        const val GRACE_MS = 1_500L
        /** Translation is already gone by now; only a wedged native call reaches this. */
        const val CANCEL_MS = 2_500L
        /** Upper bound on STOPPING: after this the UI is released regardless of native state. */
        const val HARD_MS = 4_000L
    }
}

/**
 * Stop policy for translation work.
 *
 * Provisional requests only ever refresh the live caption line, so after an explicit Stop they
 * are dropped and an in-flight one is cancelled by request id. Committed finals are deliberately
 * left queued: they carry recognized speech and still drain within the graceful window.
 *
 * Returns the number of discarded requests.
 */
internal fun <T> discardProvisionalTranslations(
    provisionalQueues: List<com.asr.live.pipeline.BoundedMailbox<T>>,
    activeProvisionalRequestId: Long?,
    cancelRequest: (Long) -> Unit,
    onDiscarded: (T) -> Unit = {},
): Int {
    var discarded = 0
    for (queue in provisionalQueues) queue.close().forEach { onDiscarded(it); discarded++ }
    activeProvisionalRequestId?.let(cancelRequest)
    return discarded
}

/** Join a worker set against one deadline; callers can retry asynchronously if work is still live. */
internal fun joinThreadsWithin(threads: Collection<Thread?>, timeoutMs: Long): Boolean {
    require(timeoutMs >= 0)
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    for (thread in threads.filterNotNull()) {
        if (thread === Thread.currentThread()) return false
        while (thread.isAlive) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
            val nanos = (remaining - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
            thread.join(millis, nanos)
        }
    }
    return true
}
