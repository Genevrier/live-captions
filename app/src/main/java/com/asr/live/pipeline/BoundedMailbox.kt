package com.asr.live.pipeline

import java.util.ArrayDeque

/** Drop oldest on overload; callers must account for the returned item. Never blocks producers. */
class BoundedMailbox<T>(private val capacity: Int) {
    init { require(capacity > 0) }
    private val items = ArrayDeque<T>()
    private var closed = false
    @Synchronized fun offer(value: T): T? {
        if (closed) return value
        val dropped = if (items.size == capacity) items.removeFirst() else null
        items.addLast(value)
        return dropped
    }
    @Synchronized fun poll(): T? = if (items.isEmpty()) null else items.removeFirst()
    @Synchronized fun drain(): List<T> = items.toList().also { items.clear() }
    @Synchronized fun size() = items.size
    @Synchronized fun close(): List<T> {
        closed = true
        return items.toList().also { items.clear() }
    }
}

/** PCM exists only in RAM. An overlong utterance is marked incomplete, never corrected as a full one. */
class PcmBuffer(private val maxSamples: Int = 16_000 * 20) {
    private val data = FloatArray(maxSamples)
    private var count = 0
    var truncated = false
        private set
    fun append(samples: FloatArray) {
        val n = minOf(samples.size, maxSamples - count)
        samples.copyInto(data, count, 0, n)
        count += n
        if (n < samples.size) truncated = true
    }
    fun take(): FloatArray? {
        val result = if (truncated) null else data.copyOf(count)
        data.fill(0f, 0, count)
        count = 0; truncated = false
        return result
    }
    fun invalidate() { truncated = true }
}

/** Reset after rebuilding so the first fresh chunk cannot trigger another rebuild. */
class AudioContinuity {
    private var previous: Long? = null
    fun gap(sequence: Long): Boolean {
        val lost = previous?.let { sequence != it + 1 } ?: false
        previous = sequence
        return lost
    }
    fun reset() { previous = null }
}
