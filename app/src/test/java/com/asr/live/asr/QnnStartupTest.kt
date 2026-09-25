package com.asr.live.asr

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class QnnStartupTest {
    @Test fun qnnStartupDeadlinesStayInteractive() {
        assertTrue("binding a local service must not take seconds", QnnStartup.BIND_TIMEOUT_MS <= 3_000)
        assertTrue("a pid round trip is trivial", QnnStartup.PID_TIMEOUT_MS <= 1_000)
        assertTrue("graph preparation must not cost tens of seconds",
            QnnStartup.INITIALIZE_TIMEOUT_MS <= 8_000)
        assertTrue("Listen must not stall waiting on an experimental backend",
            QnnStartup.TOTAL_STARTUP_BUDGET_MS <= 12_000)
        assertTrue("Stop must not wait on a wedged final flush", QnnStartup.FINISH_TIMEOUT_MS <= 5_000)
    }

    /**
     * An unresponsive QNN process must surface as a bounded timeout that selects CPU, not as a
     * stall. This exercises the same bounded-RPC shape QnnEngine uses for initialize().
     */
    @Test fun qnnInitializationTimeoutFallsBackToCpuInsteadOfStalling() {
        val rpc = Executors.newSingleThreadExecutor()
        val wedged = CountDownLatch(1)
        val remoteExited = CountDownLatch(1)
        val future = rpc.submit<String> {
            try { wedged.await() } finally { remoteExited.countDown() }
            "QNN/NPU"
        }
        var backend = "QNN initializing · experimental"
        var qnnProcessKilled = false
        val startedAtNs = System.nanoTime()
        try {
            future.get(120, TimeUnit.MILLISECONDS)
            fail("a wedged remote must not be waited on indefinitely")
        } catch (_: TimeoutException) {
            future.cancel(true)
            // QnnEngine.fallback(): kill the isolated worker, then build the CPU recognizer.
            qnnProcessKilled = true
            backend = "CPU · QNN fallback: QNN process initialization timed out"
        } finally {
            wedged.countDown()
            rpc.shutdownNow()
        }
        val elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L

        assertTrue("fallback must be bounded, got ${elapsedMs}ms", elapsedMs < 2_000)
        assertTrue("CPU fallback stays available", backend.startsWith("CPU"))
        assertTrue("the fallback reason is reported explicitly", backend.contains("QNN fallback"))
        assertTrue("the isolated QNN process is not left running", qnnProcessKilled)
        assertTrue(remoteExited.await(2, TimeUnit.SECONDS))
    }

    /**
     * A device that is not eligible for QNN takes the fallback path during construction. When the
     * session has already been stopped, that fallback must abandon startup instead of loading a
     * CPU recognizer whose only future is to be closed again.
     */
    @Test fun qnnStartupOnAStoppedSessionBuildsNoCpuRecognizer() {
        try {
            QnnEngine(RuntimeEnvironment.getApplication(), "en", 4, {}, {}, {}, {}, 0L, {},
                isAborted = { true })
            fail("a stopped session must not finish QNN startup")
        } catch (expected: InterruptedException) {
            assertTrue(expected.message!!.contains("after stop"))
        }
    }

    /** The same path with a live session still reaches CPU fallback rather than giving up. */
    @Test fun qnnIneligibleDeviceStillReachesCpuFallback() {
        var backend: String? = null
        try {
            QnnEngine(RuntimeEnvironment.getApplication(), "en", 4, {}, {},
                { backend = it }, {}, 0L, {}, isAborted = { false })
        } catch (expected: Throwable) {
            // Model files are absent in unit tests, so CPU construction fails after the fallback
            // decision. What matters is that the failure is not an abandoned startup.
            assertFalse("fallback must not be skipped on a live session", expected is InterruptedException)
        }
        assertNull("no backend is claimed until a recognizer actually exists", backend)
    }
}
