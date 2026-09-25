package com.asr.live.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class AudioSignalTelemetryTest {
    @Test fun recordsRequestedRateAmplitudeClippingAndMonotonicCallbackContinuity() {
        val telemetry = AudioSignalTelemetry()
        telemetry.setSampleRate(16_000)
        telemetry.accept(floatArrayOf(0f, .5f, -1f), 1_000_000_000L)
        val snapshot = telemetry.accept(floatArrayOf(.25f), 1_000_187_500L)
        assertEquals(16_000, snapshot.sampleRateHz)
        assertEquals(4L, snapshot.samples)
        assertEquals(1L, snapshot.clippedSamples)
        assertEquals(1.0, snapshot.peak, 0.00001)
        assertEquals(kotlin.math.sqrt(1.3125 / 4), snapshot.rms, 0.00001)
        assertEquals(0L, snapshot.timestampGaps)
        assertEquals(1_000_000_000L, snapshot.firstMonotonicNs)
        assertEquals(1_000_187_500L, snapshot.lastMonotonicNs)
    }

    @Test fun countsCallbackTimestampDiscontinuity() {
        val telemetry = AudioSignalTelemetry(timestampToleranceNs = 1_000L)
        telemetry.accept(floatArrayOf(0f, .1f), 1_000_000_000L)
        val snapshot = telemetry.accept(floatArrayOf(.1f), 1_001_000_000L)
        assertEquals(1L, snapshot.timestampGaps)
    }

    @Test fun correctionHashMatchesLittleEndianPcm16Bytes() {
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(byteArrayOf(0x00, 0x40, 0x00, 0xc0.toByte()))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, pcm16Sha256(floatArrayOf(.5f, -.5f)))
    }
}
