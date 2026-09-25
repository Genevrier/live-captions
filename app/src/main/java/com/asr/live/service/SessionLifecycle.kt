package com.asr.live.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class WorkerStartupFailure(val worker: String, val cause: Throwable)

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

    @Synchronized fun failures(): List<WorkerStartupFailure> = failures
        .map { WorkerStartupFailure(it.key, it.value) }

    @Synchronized fun reportedWorkers(): Set<String> = completed.toSet()
}

/** State shared by workers during graceful drain and immediate cancellation. */
internal class SessionLifecycle(correctionEnabled: Boolean) {
    private val cancelled = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val asrFinished = AtomicBoolean(false)
    private val correctionFinished = AtomicBoolean(!correctionEnabled)

    fun requestStop(): Boolean = !cancelled.get() && stopping.compareAndSet(false, true)

    fun cancel(): Boolean {
        val changed = cancelled.compareAndSet(false, true)
        stopping.set(true)
        return changed
    }

    fun isCancelled() = cancelled.get()
    fun isStopping() = stopping.get()
    fun markAsrFinished() { asrFinished.set(true) }
    fun markCorrectionFinished() { correctionFinished.set(true) }

    fun shouldFinishAsr(hasQueuedAudio: Boolean, captureFinished: Boolean, inferenceBusy: Boolean = false): Boolean =
        stopping.get() && captureFinished && !hasQueuedAudio && !inferenceBusy

    fun shouldRunCorrection(hasQueuedWork: Boolean, busy: Boolean): Boolean =
        !cancelled.get() && (!stopping.get() || !asrFinished.get() || hasQueuedWork || busy)

    fun shouldRunHyTranslation(hasQueuedWork: Boolean): Boolean =
        !cancelled.get() && (!stopping.get() || !asrFinished.get() || !correctionFinished.get() || hasQueuedWork)

    fun shouldRunOpusTranslation(hasQueuedWork: Boolean): Boolean =
        !cancelled.get() && (!stopping.get() || !asrFinished.get() || hasQueuedWork)
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
