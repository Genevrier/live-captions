package com.asr.live.service

import com.asr.live.model.ModelCatalog
import com.asr.live.pipeline.PerformanceMode
import com.asr.live.pipeline.Profile
import com.asr.live.pipeline.SessionConfig
import com.asr.live.pipeline.withMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class SessionLifecycleTest {
    @Test fun correctionEnabledStartupOpensAfterParakeetWarmupWhileWorkerKeepsRunning() {
        val config = SessionConfig(modelId = ModelCatalog.NEMOTRON.id, profile = Profile.DUTCH_ENGLISH)
            .withMode(PerformanceMode.MAX_QUALITY)
        assertTrue("Max Quality enables Dutch Parakeet correction", config.correction)
        val barrier = WorkerStartupBarrier(mapOf(
            "recognition" to true,
            "hy-translation" to true,
            "parakeet-correction" to false,
        ))
        val letCorrectionFinish = CountDownLatch(1)
        val correctionStillRunning = CountDownLatch(1)
        val modelsInitializedAndWarmed = AtomicBoolean(false)
        val correction = Thread {
            barrier.initialize("parakeet-correction") {
                // Stand-in for successful model construction plus native warm-up.
                modelsInitializedAndWarmed.set(true)
            }
            correctionStillRunning.countDown()
            letCorrectionFinish.await()
        }.apply { isDaemon = true }

        correction.start()
        barrier.initialize("recognition") { "ASR model initialized" }
        barrier.initialize("hy-translation") { "Hy model initialized and warmed" }
        assertTrue(barrier.await(1, TimeUnit.SECONDS))
        assertTrue(correctionStillRunning.await(1, TimeUnit.SECONDS))
        assertTrue(modelsInitializedAndWarmed.get())
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
        barrier.initialize("recognition") { "ASR model initialized" }
        try { barrier.initialize<String>("hy-translation") { throw failure } }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
        barrier.initialize("parakeet-correction") { "Parakeet model initialized and warmed" }

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
        barrier.initialize("recognition") { "ASR model initialized" }
        barrier.initialize("hy-translation") { "Hy model initialized and warmed" }
        try { barrier.initialize<String>("parakeet-correction") { throw failure } }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }

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

    @Test fun stopDuringMicrophoneReadDoesNotTreatAnEmptyQueueAsEndOfAudio() {
        val lifecycle = SessionLifecycle(correctionEnabled = false)
        val queue = com.asr.live.pipeline.BoundedMailbox<Int>(2)
        assertTrue(lifecycle.requestStop())

        // The producer is still inside its current microphone read; no frame has arrived yet.
        assertEquals(0, queue.size())
        assertFalse(lifecycle.shouldFinishAsr(queue.size() > 0, captureFinished = false))

        queue.offer(11)
        assertFalse(lifecycle.shouldFinishAsr(queue.size() > 0, captureFinished = true))
        assertEquals(11, queue.poll())
        assertTrue(lifecycle.shouldFinishAsr(queue.size() > 0, captureFinished = true))
    }

    @Test fun stopDuringAsrInferenceWaitsForInFlightChunkBeforeFinish() {
        val lifecycle = SessionLifecycle(correctionEnabled = false)
        val inFlight = CountDownLatch(1)
        val letInferenceReturn = CountDownLatch(1)
        val inferenceBusy = AtomicBoolean(false)
        val worker = Thread {
            inferenceBusy.set(true)
            inFlight.countDown()
            letInferenceReturn.await()
            inferenceBusy.set(false)
        }.apply { isDaemon = true }
        assertTrue(lifecycle.requestStop())
        worker.start()
        assertTrue(inFlight.await(1, TimeUnit.SECONDS))

        assertFalse(lifecycle.shouldFinishAsr(hasQueuedAudio = false, captureFinished = true,
            inferenceBusy = inferenceBusy.get()))
        letInferenceReturn.countDown()
        worker.join(1_000)
        assertTrue(lifecycle.shouldFinishAsr(hasQueuedAudio = false, captureFinished = true,
            inferenceBusy = inferenceBusy.get()))
    }

    @Test fun stopDuringTranslationLetsHyPublishThenDrainsAndExits() {
        val lifecycle = SessionLifecycle(correctionEnabled = false)
        val translationStarted = CountDownLatch(1)
        val translationCanReturn = CountDownLatch(1)
        val outputPublished = AtomicBoolean(false)
        val exitedAfterDrain = AtomicBoolean(false)
        val worker = Thread {
            // The queued final keeps Hy alive after ASR finishes; translation itself is in flight.
            if (lifecycle.shouldRunHyTranslation(hasQueuedWork = true)) {
                translationStarted.countDown()
                translationCanReturn.await()
                outputPublished.set(true)
            }
            exitedAfterDrain.set(!lifecycle.shouldRunHyTranslation(hasQueuedWork = false))
        }.apply { isDaemon = true }
        assertTrue(lifecycle.requestStop())
        lifecycle.markAsrFinished()
        worker.start()
        assertTrue(translationStarted.await(1, TimeUnit.SECONDS))
        assertTrue(lifecycle.shouldRunHyTranslation(hasQueuedWork = true))
        translationCanReturn.countDown()
        worker.join(1_000)
        assertTrue(outputPublished.get())
        assertTrue(exitedAfterDrain.get())
        assertFalse(worker.isAlive)
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

    @Test fun retiredNativeCorrectionClosesOnlyAfterItsActiveDecodeReturns() {
        val use = NativeResourceUseGate()
        val objectClosed = AtomicBoolean(false)
        assertTrue(use.beginCall())

        val retirement = use.retire()
        assertTrue(retirement.callInUse)
        assertFalse(retirement.closeNow)
        assertFalse(objectClosed.get())

        if (use.finishCall()) objectClosed.set(true)
        assertTrue(objectClosed.get())
        assertFalse(use.retire().closeNow)
    }

    @Test fun retirementBeforeDecodeStartsPreventsNativeAccess() {
        val use = NativeResourceUseGate()
        val retirement = use.retire()

        assertTrue(retirement.closeNow)
        assertFalse(retirement.callInUse)
        assertFalse(use.beginCall())
        assertFalse(use.finishCall())
    }
}
