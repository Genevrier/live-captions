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
    /**
     * Mirrors CaptionSession.recognize(): the microphone starts on recognition readiness alone.
     * `held` names the workers kept inside their initializer for the duration of the check.
     */
    private fun assertCaptureStartsWhileWorkersStillLoading(vararg held: String) {
        val barrier = WorkerStartupBarrier(mapOf(
            "recognition" to true,
            "hy-translation" to true,
            "parakeet-correction" to false,
        ))
        val lifecycle = SessionLifecycle(correctionEnabled = true)
        val loading = CountDownLatch(held.size)
        val letWorkersFinish = CountDownLatch(1)
        val captureStarted = CountDownLatch(1)
        val blocked = held.map { worker ->
            Thread {
                barrier.initialize(worker) { loading.countDown(); letWorkersFinish.await() }
            }.apply { isDaemon = true }
        }
        val free = setOf("hy-translation", "parakeet-correction") - held.toSet()

        blocked.forEach { it.start() }
        assertTrue("held workers must reach their initializer", loading.await(2, TimeUnit.SECONDS))
        val recognition = Thread {
            barrier.initialize("recognition") { "recognizer created" }
            if (!lifecycle.isCancelled()) captureStarted.countDown()
        }.apply { isDaemon = true }
        recognition.start()

        assertTrue("Microphone must not wait for ${held.joinToString()}",
            captureStarted.await(2, TimeUnit.SECONDS))
        assertFalse("startup is deliberately still incomplete", barrier.await(50, TimeUnit.MILLISECONDS))
        assertEquals(setOf("recognition"), barrier.reportedWorkers())
        assertNull("no required worker has failed", barrier.requiredFailure())

        free.forEach { barrier.initialize(it) { "ready" } }
        letWorkersFinish.countDown()
        blocked.forEach { it.join(2_000) }
        assertTrue(barrier.await(2, TimeUnit.SECONDS))
        assertTrue(barrier.requiredFailures().isEmpty())
    }

    @Test fun listenDoesNotWaitForOptionalCorrectionStartup() {
        assertCaptureStartsWhileWorkersStillLoading("parakeet-correction")
    }

    @Test fun listenDoesNotWaitForTranslationModelWarmUpBeforeMicrophoneStart() {
        assertCaptureStartsWhileWorkersStillLoading("hy-translation")
    }

    @Test fun listenDoesNotWaitForAnyHeavyWorkerBeforeMicrophoneStart() {
        assertCaptureStartsWhileWorkersStillLoading("hy-translation", "parakeet-correction")
    }

    @Test fun aRequiredWorkerThatFailsAfterCaptureStartedStillTerminatesTheSession() {
        val barrier = WorkerStartupBarrier(mapOf("recognition" to true, "hy-translation" to true))
        barrier.initialize("recognition") { "recognizer created" }
        // Capture is already running at this point; the failure must still be visible to it.
        assertNull(barrier.requiredFailure(exceptWorker = "recognition"))
        val failure = IllegalStateException("Hy model load failed")
        try { barrier.initialize<String>("hy-translation") { throw failure } }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }

        val observed = barrier.requiredFailure(exceptWorker = "recognition")
        assertEquals("hy-translation", observed?.worker)
        assertSame(failure, observed?.cause)
    }

    @Test fun aStopCancelledModelLoadDoesNotAbortTheAsrFlush() {
        val barrier = WorkerStartupBarrier(mapOf("recognition" to true, "hy-translation" to true))
        val lifecycle = SessionLifecycle(correctionEnabled = false)
        barrier.initialize("recognition") { "recognizer created" }
        assertTrue(lifecycle.requestStop())

        // Stop aborts the in-flight GGUF load, so the translator reports a required failure.
        try {
            barrier.initialize<String>("hy-translation") {
                throw java.util.concurrent.CancellationException("Hy-MT2 model load cancelled")
            }
        } catch (_: java.util.concurrent.CancellationException) { }
        assertNotNull(barrier.requiredFailure(exceptWorker = "recognition"))

        // Recognition must read that as teardown, not as a session error to cancel on.
        val treatAsSessionFailure = !lifecycle.isStopping() &&
            barrier.requiredFailure(exceptWorker = "recognition") != null
        assertFalse("a stop-cancelled load must not abort the flush", treatAsSessionFailure)
        assertFalse(lifecycle.isCancelled())
        assertTrue("queued PCM still flushes through ASR",
            lifecycle.shouldFinishAsr(hasQueuedAudio = false, captureFinished = true))
    }

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

    @Test fun stopWhileMicrophoneReadIsInFlightStillDeliversTheTrailingPcmFrame() {
        val lifecycle = SessionLifecycle(correctionEnabled = false)
        val queue = com.asr.live.pipeline.BoundedMailbox<FloatArray>(16)
        val captureFinished = AtomicBoolean(false)
        val insideDeviceRead = CountDownLatch(1)
        val readMayReturn = CountDownLatch(1)
        // Models AudioCapture: the partial frame collected before stop is still emitted, and
        // only then does onFinished close the queue for drain.
        val producer = Thread {
            insideDeviceRead.countDown()
            readMayReturn.await()
            queue.offer(FloatArray(700) { 1f })
            captureFinished.set(true)
            queue.closeForDrain()
        }.apply { isDaemon = true }

        producer.start()
        assertTrue(insideDeviceRead.await(1, TimeUnit.SECONDS))
        assertTrue(lifecycle.requestStop())
        assertFalse("an empty queue mid-read is not end of audio",
            lifecycle.shouldFinishAsr(queue.size() > 0, captureFinished.get()))

        readMayReturn.countDown()
        producer.join(1_000)
        assertFalse(producer.isAlive)
        assertEquals(1, queue.size())
        assertFalse("the trailing frame must be recognized before finish",
            lifecycle.shouldFinishAsr(queue.size() > 0, captureFinished.get()))
        assertEquals(700, queue.poll()!!.size)
        assertTrue(lifecycle.shouldFinishAsr(queue.size() > 0, captureFinished.get()))
    }

    @Test fun stopDuringActiveHyTranslationAbortsItCooperatively() {
        val lifecycle = SessionLifecycle(correctionEnabled = false)
        val provisional = com.asr.live.pipeline.BoundedMailbox<Long>(1)
        val inNativeGeneration = CountDownLatch(1)
        val abortedRequest = java.util.concurrent.atomic.AtomicLong(-1)
        val cancelledRequest = java.util.concurrent.atomic.AtomicLong(-1)
        val activeRequestId = 41L
        // A cooperative native backend polls its cancel flag between decoded tokens.
        val translation = Thread {
            inNativeGeneration.countDown()
            while (cancelledRequest.get() != activeRequestId) Thread.sleep(1)
            abortedRequest.set(activeRequestId)
        }.apply { isDaemon = true }

        provisional.offer(77L)
        translation.start()
        assertTrue(inNativeGeneration.await(1, TimeUnit.SECONDS))
        assertTrue(lifecycle.requestStop())
        assertFalse("no new provisional work is admitted after stop", lifecycle.shouldTranslateProvisional())

        val discarded = discardProvisionalTranslations(
            provisionalQueues = listOf(provisional),
            activeProvisionalRequestId = activeRequestId,
            cancelRequest = { cancelledRequest.set(it) })

        assertEquals(1, discarded)
        translation.join(1_000)
        assertEquals(activeRequestId, abortedRequest.get())
        assertFalse(translation.isAlive)
    }

    @Test fun stopWithQueuedTranslationsDiscardsProvisionalWorkAndKeepsFinalsDraining() {
        val lifecycle = SessionLifecycle(correctionEnabled = false)
        val provisional = com.asr.live.pipeline.BoundedMailbox<String>(2)
        val qualityProvisional = com.asr.live.pipeline.BoundedMailbox<String>(2)
        val finals = com.asr.live.pipeline.BoundedMailbox<String>(2)
        provisional.offer("live prefix")
        qualityProvisional.offer("live prefix A/B")
        finals.offer("committed sentence")
        val rejected = mutableListOf<String>()
        assertTrue(lifecycle.requestStop())

        val discarded = discardProvisionalTranslations(
            provisionalQueues = listOf(provisional, qualityProvisional),
            activeProvisionalRequestId = null,
            cancelRequest = { fail("nothing was in flight") },
            onDiscarded = { rejected += it })

        assertEquals(2, discarded)
        assertEquals(listOf("live prefix", "live prefix A/B"), rejected)
        assertEquals(0, provisional.size())
        assertEquals(0, qualityProvisional.size())
        assertNull("a closed provisional queue accepts nothing new", provisional.poll())
        assertEquals("live again", provisional.offer("live again"))

        // The committed final is untouched and still keeps Hy alive until it has drained.
        assertEquals(1, finals.size())
        lifecycle.markAsrFinished()
        assertTrue(lifecycle.shouldRunHyTranslation(hasQueuedWork = finals.size() > 0))
        assertEquals("committed sentence", finals.poll())
        assertFalse(lifecycle.shouldRunHyTranslation(hasQueuedWork = finals.size() > 0))
        assertFalse("benchmark-only OPUS work is dropped outright", lifecycle.shouldRunOpusTranslation())
    }

    @Test fun stopSuppressesWarmUpAndFurtherProvisionalWork() {
        val lifecycle = SessionLifecycle(correctionEnabled = false)
        assertTrue(lifecycle.shouldWarmUp())
        assertTrue(lifecycle.shouldTranslateProvisional())
        assertTrue(lifecycle.shouldRunOpusTranslation())

        assertTrue(lifecycle.requestStop())

        assertFalse("a stopped session has no future translation to prepare for", lifecycle.shouldWarmUp())
        assertFalse(lifecycle.shouldTranslateProvisional())
        assertFalse(lifecycle.shouldRunOpusTranslation())
    }
}
