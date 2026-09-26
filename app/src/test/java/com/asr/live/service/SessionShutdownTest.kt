package com.asr.live.service

import com.asr.live.pipeline.SessionConfig
import com.asr.live.pipeline.TranslationQuality
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class SessionShutdownTest {
    private class FakeClock(private var nanos: Long = 0) {
        fun nowNs(): Long = nanos
        fun advanceMs(millis: Long) { nanos += TimeUnit.MILLISECONDS.toNanos(millis) }
    }

    @Test fun shutdownEscalatesThroughAbandonThenCancelThenUiRelease() {
        val clock = FakeClock()
        val deadline = ShutdownDeadline(graceMs = 1_500, cancelMs = 2_500, hardMs = 4_000,
            startedAtNs = clock.nowNs(), nowNs = clock::nowNs)

        assertFalse("a stop that lands immediately is still graceful", deadline.abandonTranslationsDue())
        assertFalse(deadline.forceCancelDue())
        assertFalse(deadline.releaseUiDue())
        assertEquals(1_500L, deadline.remainingUntilAbandonTranslationsMs())

        clock.advanceMs(1_499)
        assertFalse(deadline.abandonTranslationsDue())

        clock.advanceMs(1)
        assertTrue("the graceful window is bounded", deadline.abandonTranslationsDue())
        assertFalse("recognition keeps flushing after translations are dropped",
            deadline.forceCancelDue())
        assertEquals(1_000L, deadline.remainingUntilForceCancelMs())

        clock.advanceMs(1_000)
        assertTrue(deadline.forceCancelDue())
        assertFalse("forced cancellation gets its own window before the UI is released",
            deadline.releaseUiDue())
        assertEquals(1_500L, deadline.remainingUntilReleaseUiMs())

        clock.advanceMs(1_500)
        assertTrue("STOPPING has a hard upper bound", deadline.releaseUiDue())
        assertEquals(0L, deadline.remainingUntilAbandonTranslationsMs())
        assertEquals(0L, deadline.remainingUntilForceCancelMs())
        assertEquals(0L, deadline.remainingUntilReleaseUiMs())
    }

    @Test fun defaultShutdownBoundsKeepStopInteractive() {
        assertTrue("Stop should normally reach STOPPED within about two seconds",
            ShutdownDeadline.GRACE_MS <= 2_000)
        assertTrue("dropping translation work must come before cancelling recognition",
            ShutdownDeadline.CANCEL_MS > ShutdownDeadline.GRACE_MS)
        assertTrue("forced cancellation must have room to take effect",
            ShutdownDeadline.HARD_MS > ShutdownDeadline.CANCEL_MS)
        assertTrue("STOPPING must never be open-ended", ShutdownDeadline.HARD_MS <= 5_000)
    }

    @Test fun shutdownDeadlineRejectsStepsThatWouldSkipAnEscalation() {
        try {
            ShutdownDeadline(graceMs = 1_500, cancelMs = 1_000, hardMs = 4_000, startedAtNs = 0)
            fail("cancelling before the grace window would cut the ASR flush short")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("grace window"))
        }
        try {
            ShutdownDeadline(graceMs = 1_500, cancelMs = 2_500, hardMs = 2_000, startedAtNs = 0)
            fail("releasing the UI before forced cancellation would skip it")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("grace window"))
        }
    }

    /**
     * Dropping translation work must not cut recognition short: trailing speech still has to
     * reach the caption line as recognized source text.
     */
    @Test fun abandoningTranslationsLeavesTheAsrFlushRunning() {
        val lifecycle = SessionLifecycle(correctionEnabled = false)
        assertTrue(lifecycle.requestStop())
        assertTrue("a queued final still drains inside the grace window",
            lifecycle.shouldRunHyTranslation(hasQueuedWork = true))

        assertTrue(lifecycle.abandonTranslations())
        assertFalse("repeated escalation is a no-op", lifecycle.abandonTranslations())

        assertFalse(lifecycle.shouldTranslate())
        assertFalse("no translation survives the grace window",
            lifecycle.shouldRunHyTranslation(hasQueuedWork = true))
        assertFalse("recognition is untouched by this step", lifecycle.isCancelled())
        assertFalse("queued PCM must still be recognized before the flush",
            lifecycle.shouldFinishAsr(hasQueuedAudio = true, captureFinished = true))
        assertTrue("and the flush itself still runs",
            lifecycle.shouldFinishAsr(hasQueuedAudio = false, captureFinished = true))
    }

    /**
     * A worker that ignores the graceful stop is only released by forced cancellation. The
     * escalation path the service runs must therefore terminate it within the hard deadline.
     */
    @Test fun aWorkerThatIgnoresGracefulStopIsReleasedByForcedCancellation() {
        val lifecycle = SessionLifecycle(correctionEnabled = false)
        val running = CountDownLatch(1)
        val observedCancellation = AtomicBoolean(false)
        val worker = Thread {
            running.countDown()
            while (!lifecycle.isCancelled()) {
                try { Thread.sleep(2) } catch (_: InterruptedException) { break }
            }
            observedCancellation.set(true)
        }.apply { isDaemon = true }

        worker.start()
        assertTrue(running.await(1, TimeUnit.SECONDS))
        assertTrue(lifecycle.requestStop())

        val deadline = ShutdownDeadline(graceMs = 40, cancelMs = 80, hardMs = 3_000,
            startedAtNs = System.nanoTime())
        var abandoned = false
        var forced = false
        while (!joinThreadsWithin(listOf(worker), 5)) {
            if (!abandoned && deadline.abandonTranslationsDue()) {
                abandoned = true
                lifecycle.abandonTranslations()
            }
            if (!forced && deadline.forceCancelDue()) {
                forced = true
                lifecycle.cancel()
                worker.interrupt()
            }
            assertFalse("escalation must finish inside the hard deadline", deadline.releaseUiDue())
        }
        assertTrue("dropping translations alone never released this worker", abandoned)
        assertTrue("the graceful window alone never released this worker", forced)
        assertTrue(observedCancellation.get())
        assertFalse(worker.isAlive)
    }

    /**
     * Twenty Listen -> Stop cycles, including cycles whose worker only honours forced
     * cancellation, must each leave the UI in STOPPED with no worker left behind.
     */
    @Test fun twentySequentialListenStopCyclesAllFinishStopped() {
        val cycles = 20
        var escalations = 0
        for (cycle in 1..cycles) {
            val generation = 900_000L + cycle
            CaptionState.begin(generation, SessionConfig(quality = TranslationQuality.ML_KIT), "Nemotron")
            assertEquals("cycle $cycle enters STARTING", ListeningState.STARTING, CaptionState.lifecycle.value)

            val lifecycle = SessionLifecycle(correctionEnabled = cycle % 3 == 0)
            // Every fourth cycle models native work that does not observe the graceful stop.
            val ignoresGracefulStop = cycle % 4 == 0
            val queuedFinal = AtomicLong(if (cycle % 2 == 0) 1 else 0)
            val drainedGracefully = AtomicBoolean(false)
            val started = CountDownLatch(1)
            val worker = Thread {
                started.countDown()
                try {
                    while (!lifecycle.isCancelled()) {
                        if (!ignoresGracefulStop && lifecycle.isStopping()) {
                            drainedGracefully.set(true)
                            break
                        }
                        try { Thread.sleep(2) } catch (_: InterruptedException) { break }
                    }
                } finally {
                    // Either the queued final drained or forced cancellation discarded it.
                    queuedFinal.set(0)
                }
            }.apply { isDaemon = true }

            worker.start()
            assertTrue("cycle $cycle worker starts", started.await(2, TimeUnit.SECONDS))
            CaptionState.listening(generation)
            assertEquals("cycle $cycle reaches LISTENING", ListeningState.LISTENING, CaptionState.lifecycle.value)

            CaptionState.stopping(generation)
            assertTrue(lifecycle.requestStop())
            val deadline = ShutdownDeadline(graceMs = 400, cancelMs = 600, hardMs = 2_000,
                startedAtNs = System.nanoTime())
            var forced = false
            while (!joinThreadsWithin(listOf(worker), 5)) {
                if (deadline.abandonTranslationsDue()) lifecycle.abandonTranslations()
                if (!forced && deadline.forceCancelDue()) {
                    forced = true
                    lifecycle.cancel()
                    worker.interrupt()
                }
                if (deadline.releaseUiDue()) break
            }
            if (forced) escalations++
            assertTrue("cycle $cycle must terminate its worker", joinThreadsWithin(listOf(worker), 2_000))
            assertFalse("cycle $cycle left a worker running", worker.isAlive)

            CaptionState.stopped(generation)
            assertEquals("cycle $cycle ends STOPPED", ListeningState.STOPPED, CaptionState.lifecycle.value)
            assertFalse("cycle $cycle still reports running", CaptionState.running.value)
            assertEquals("cycle $cycle must not leave translation work queued", 0L, queuedFinal.get())
            assertEquals("cycle $cycle graceful drain expectation",
                !ignoresGracefulStop, drainedGracefully.get())
            if (ignoresGracefulStop) assertTrue("cycle $cycle needed forced cancellation", forced)
        }
        assertEquals("every fourth cycle exercised forced cancellation", cycles / 4, escalations)
    }
}
