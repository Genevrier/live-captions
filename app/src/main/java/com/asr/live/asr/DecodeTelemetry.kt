package com.asr.live.asr

import java.util.ArrayDeque

/** Monotonic timings for actual native decoder calls, separate from accept/callback time. */
data class AsrDecodeStats(
    val calls: Long = 0,
    val totalNanos: Long = 0,
    val meanNanos: Long = 0,
    val p50Nanos: Long = 0,
    val p95Nanos: Long = 0,
    val maxNanos: Long = 0,
    val samplesNanos: List<Long> = emptyList(),
)

internal class DecodeTelemetry(private val sampleLimit: Int = 512) {
    init { require(sampleLimit > 0) }
    private val samples = ArrayDeque<Long>()
    private var calls = 0L
    private var totalNanos = 0L
    private var maxNanos = 0L

    fun <T> measure(block: () -> T): T {
        val started = System.nanoTime()
        try { return block() }
        finally { record(System.nanoTime() - started) }
    }

    @Synchronized private fun record(durationNs: Long) {
        val sample = durationNs.coerceAtLeast(0)
        calls++
        totalNanos += sample
        maxNanos = maxOf(maxNanos, sample)
        if (samples.size == sampleLimit) samples.removeFirst()
        samples.addLast(sample)
    }

    @Synchronized fun snapshot(): AsrDecodeStats {
        val sorted = samples.sorted()
        fun percentile(p: Double): Long = sorted.getOrNull(
            ((sorted.size * p).toInt() - 1).coerceIn(0, (sorted.size - 1).coerceAtLeast(0))) ?: 0L
        return AsrDecodeStats(calls, totalNanos,
            if (calls == 0L) 0 else totalNanos / calls,
            percentile(.50), percentile(.95), maxNanos, samples.toList())
    }
}

/** Retains session totals and a bounded latency sample window across ASR engine rebuilds. */
internal class DecodeStatsAccumulator(private val sampleLimit: Int = 512) {
    private val retiredSamples = ArrayDeque<Long>()
    private var retiredCalls = 0L
    private var retiredTotalNs = 0L
    private var retiredMaxNs = 0L

    @Synchronized fun retire(stats: AsrDecodeStats) {
        retiredCalls += stats.calls
        retiredTotalNs += stats.totalNanos
        retiredMaxNs = maxOf(retiredMaxNs, stats.maxNanos)
        stats.samplesNanos.forEach(::addRetiredSample)
    }

    @Synchronized fun snapshot(active: AsrDecodeStats = AsrDecodeStats()): AsrDecodeStats {
        val samples = (retiredSamples.toList() + active.samplesNanos).takeLast(sampleLimit).sorted()
        fun percentile(p: Double): Long = samples.getOrNull(
            ((samples.size * p).toInt() - 1).coerceIn(0, (samples.size - 1).coerceAtLeast(0))) ?: 0L
        val calls = retiredCalls + active.calls
        val total = retiredTotalNs + active.totalNanos
        return AsrDecodeStats(calls, total, if (calls == 0L) 0 else total / calls,
            percentile(.50), percentile(.95), maxOf(retiredMaxNs, active.maxNanos), samples)
    }

    private fun addRetiredSample(sample: Long) {
        if (retiredSamples.size == sampleLimit) retiredSamples.removeFirst()
        retiredSamples.addLast(sample)
    }
}
