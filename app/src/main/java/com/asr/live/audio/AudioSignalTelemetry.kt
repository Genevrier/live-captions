package com.asr.live.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class AudioSignalSnapshot(
    val sampleRateHz: Int = AudioCapture.SAMPLE_RATE,
    val channels: Int = 1,
    val samples: Long = 0,
    val rms: Double = 0.0,
    val peak: Double = 0.0,
    val clippedSamples: Long = 0,
    val timestampGaps: Long = 0,
    val firstMonotonicNs: Long? = null,
    val lastMonotonicNs: Long? = null,
)

/** Captured PCM16 amplitude and callback-timeline checks; timestamps use Android's monotonic clock. */
internal class AudioSignalTelemetry(private val timestampToleranceNs: Long = 250_000_000L) {
    private var sampleRateHz = AudioCapture.SAMPLE_RATE
    private var samples = 0L
    private var squareSum = 0.0
    private var peak = 0.0
    private var clippedSamples = 0L
    private var timestampGaps = 0L
    private var firstMonotonicNs: Long? = null
    private var lastMonotonicNs: Long? = null
    private var previousChunkSamples = 0

    @Synchronized fun setSampleRate(rateHz: Int) { if (rateHz > 0) sampleRateHz = rateHz }

    @Synchronized fun accept(chunk: FloatArray, capturedAtNs: Long): AudioSignalSnapshot {
        require(capturedAtNs > 0)
        val previousTime = lastMonotonicNs
        if (previousTime != null) {
            val expected = previousTime + previousChunkSamples * 1_000_000_000L / sampleRateHz
            if (capturedAtNs < previousTime || kotlin.math.abs(capturedAtNs - expected) > timestampToleranceNs)
                timestampGaps++
        } else firstMonotonicNs = capturedAtNs
        lastMonotonicNs = capturedAtNs
        previousChunkSamples = chunk.size
        for (sample in chunk) {
            val value = sample.toDouble().coerceIn(-1.0, 1.0)
            squareSum += value * value
            peak = maxOf(peak, kotlin.math.abs(value))
            if (kotlin.math.abs(sample) >= 0.9999695f) clippedSamples++
        }
        samples += chunk.size
        return snapshot()
    }

    @Synchronized fun snapshot(): AudioSignalSnapshot = AudioSignalSnapshot(
        sampleRateHz = sampleRateHz,
        samples = samples,
        rms = if (samples == 0L) 0.0 else kotlin.math.sqrt(squareSum / samples),
        peak = peak,
        clippedSamples = clippedSamples,
        timestampGaps = timestampGaps,
        firstMonotonicNs = firstMonotonicNs,
        lastMonotonicNs = lastMonotonicNs,
    )
}

/** Hashes exactly the PCM16 representation retained for endpoint correction. */
internal fun pcm16Sha256(samples: FloatArray): String {
    val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { sample ->
        val value = (sample.coerceIn(-1f, 1f) * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        pcm.putShort(value.toShort())
    }
    return MessageDigest.getInstance("SHA-256").digest(pcm.array())
        .joinToString("") { byte -> "%02x".format(byte) }
}
