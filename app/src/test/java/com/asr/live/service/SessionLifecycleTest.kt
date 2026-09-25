package com.asr.live.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class SessionLifecycleTest {
    @Test fun correctionEnabledStartupOpensAfterParakeetWarmupWhileWorkerKeepsRunning() {
        val barrier = WorkerStartupBarrier(mapOf(
            "recognition" to true,
            "hy-translation" to true,
            "parakeet-correction" to false,
        ))
        val letCorrectionFinish = CountDownLatch(1)
        val correctionStillRunning = CountDownLatch(1)
        val correction = Thread {
            // Readiness is reported after construction and warm-up, before the decode loop exits.
            barrier.ready("parakeet-correction")
            correctionStillRunning.countDown()
            letCorrectionFinish.await()
        }.apply { isDaemon = true }

        correction.start()
        assertTrue(barrier.ready("recognition"))
        assertTrue(barrier.ready("hy-translation"))
        assertTrue(barrier.await(1, TimeUnit.SECONDS))
        assertTrue(correctionStillRunning.await(1, TimeUnit.SECONDS))
        assertTrue("Parakeet must still be available to correct endpoints", correction.isAlive)
        assertTrue(barrier.requiredFailures().isEmpty())

        letCorrectionFinish.countDown()
        correction.join(1_000)
        assertFalse(correction.isAlive)
    }

    @Test fun requiredWorkerInitializationFailureReleasesStartupAndIsVisible() {
        val failure = IllegalStateException("Hy model initialization failed")
        val barrier = WorkerStartupBarrier(mapOf(
            "recognition" to true,
            "hy-translation" to true,
            "parakeet-correction" to false,
        ))
        assertTrue(barrier.ready("recognition"))
        assertTrue(barrier.failed("hy-translation", failure))
        assertTrue(barrier.ready("parakeet-correction"))

        assertTrue("Failure must release the waiting recognition worker", barrier.await(1, TimeUnit.SECONDS))
        assertEquals("hy-translation", barrier.requiredFailures().single().worker)
        assertSame(failure, barrier.requiredFailures().single().cause)
    }

    @Test fun correctionInitializationFailureIsReportedAndDoesNotBlockCaptureStartup() {
        val failure = IllegalStateException("Parakeet model initialization failed")
        val barrier = WorkerStartupBarrier(mapOf(
            "recognition" to true,
            "hy-translation" to true,
            "parakeet-correction" to false,
        ))
        barrier.ready("recognition")
        barrier.ready("hy-translation")
        barrier.failed("parakeet-correction", failure)

        assertTrue(barrier.await(1, TimeUnit.SECONDS))
        assertTrue(barrier.requiredFailures().isEmpty())
        assertEquals("parakeet-correction", barrier.failures().single().worker)
        assertSame(failure, barrier.failures().single().cause)
    }

    @Test fun duplicateStartupSignalsAreIgnored() {
        val barrier = WorkerStartupBarrier(mapOf("hy-translation" to true))

        assertTrue(barrier.ready("hy-translation"))
        assertFalse(barrier.ready("hy-translation"))
        assertFalse(barrier.failed("hy-translation", IllegalStateException("late failure")))

        assertTrue(barrier.await(1, TimeUnit.SECONDS))
        assertEquals(setOf("hy-translation"), barrier.reportedWorkers())
        assertTrue(barrier.failures().isEmpty())
    }

    @Test fun gracefulStopWaitsForCaptureDrainAndFinishesCorrectionAndHyQueues() {
        val lifecycle = SessionLifecycle(correctionEnabled = true)
        assertTrue(lifecycle.requestStop())

        assertFalse(lifecycle.shouldFinishAsr(hasQueuedAudio = false, captureFinished = false))
        assertFalse(lifecycle.shouldFinishAsr(hasQueuedAudio = true, captureFinished = true))
        assertTrue(lifecycle.shouldFinishAsr(hasQueuedAudio = false, captureFinished = true))

        assertTrue(lifecycle.shouldRunCorrection(hasQueuedWork = true, busy = false))
        lifecycle.markAsrFinished()
        assertTrue(lifecycle.shouldRunCorrection(hasQueuedWork = true, busy = true))
        assertFalse(lifecycle.shouldRunCorrection(hasQueuedWork = false, busy = false))
        lifecycle.markCorrectionFinished()

        assertTrue("Hy must drain the queued final translation", lifecycle.shouldRunHyTranslation(hasQueuedWork = true))
        assertFalse(lifecycle.shouldRunHyTranslation(hasQueuedWork = false))
    }

    @Test fun cancelStopsWorkerLoopsAndThreadWaitIsBoundedUntilWorkerCleanup() {
        val lifecycle = SessionLifecycle(correctionEnabled = true)
        val inNativeCall = CountDownLatch(1)
        val nativeCallCanExit = CountDownLatch(1)
        val interruptObserved = CountDownLatch(1)
        val nativeCallActive = AtomicBoolean(false)
        val objectClosedByOwner = AtomicBoolean(false)
        val objectFreedWhileInUse = AtomicBoolean(false)
        val closeNativeObject = {
            if (nativeCallActive.get()) objectFreedWhileInUse.set(true)
            objectClosedByOwner.set(true)
        }
        val worker = Thread {
            nativeCallActive.set(true)
            inNativeCall.countDown()
            while (true) {
                try {
                    nativeCallCanExit.await()
                    break
                } catch (_: InterruptedException) {
                    // Model a native call that observes cancellation only after it returns.
                    interruptObserved.countDown()
                }
            }
            nativeCallActive.set(false)
            closeNativeObject()
        }.apply { isDaemon = true }
        worker.start()
        assertTrue(inNativeCall.await(1, TimeUnit.SECONDS))

        val startedAt = System.nanoTime()
        assertFalse(joinThreadsWithin(listOf(worker), 80))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 1_000)
        assertFalse(objectClosedByOwner.get())
        assertTrue(lifecycle.cancel())
        assertFalse(lifecycle.shouldRunCorrection(hasQueuedWork = true, busy = true))
        assertFalse(lifecycle.shouldRunHyTranslation(hasQueuedWork = true))

        worker.interrupt()
        assertTrue(interruptObserved.await(1, TimeUnit.SECONDS))
        assertFalse(joinThreadsWithin(listOf(worker), 80))
        assertFalse(objectClosedByOwner.get())
        nativeCallCanExit.countDown()
        assertTrue(joinThreadsWithin(listOf(worker), 1_000))
        assertTrue(objectClosedByOwner.get())
        assertFalse(objectFreedWhileInUse.get())
    }
}
