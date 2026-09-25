package com.asr.live.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeTelemetryTest {
    @Test fun callbackWithoutNativeDecodeDoesNotInventDecodeTiming() {
        val telemetry = DecodeTelemetry()
        assertEquals(0L, telemetry.snapshot().calls)
        assertEquals(0L, telemetry.snapshot().totalNanos)
    }

    @Test fun recordsActualDecodeCallCountAndLatencyDistribution() {
        val telemetry = DecodeTelemetry()
        repeat(20) { telemetry.measure { Thread.sleep(1) } }
        val stats = telemetry.snapshot()
        assertEquals(20L, stats.calls)
        assertTrue(stats.totalNanos > 0)
        assertTrue(stats.meanNanos > 0)
        assertTrue(stats.p50Nanos > 0)
        assertTrue(stats.p95Nanos >= stats.p50Nanos)
        assertTrue(stats.maxNanos >= stats.p95Nanos)
    }

    @Test fun sessionDistributionRetainsCallsAcrossEngineRebuilds() {
        val firstEngine = DecodeTelemetry()
        repeat(3) { firstEngine.measure { Thread.sleep(1) } }
        val accumulator = DecodeStatsAccumulator()
        accumulator.retire(firstEngine.snapshot())
        val secondEngine = DecodeTelemetry()
        repeat(2) { secondEngine.measure { Thread.sleep(1) } }

        val sessionStats = accumulator.snapshot(secondEngine.snapshot())
        assertEquals(5L, sessionStats.calls)
        assertTrue(sessionStats.totalNanos >= sessionStats.meanNanos * sessionStats.calls)
        assertTrue(sessionStats.p95Nanos >= sessionStats.p50Nanos)
    }
}
