package com.asr.live.service

/** One buffered item plus the time it was queued, for age-based eviction. */
internal data class Aged<T>(val value: T, val queuedAtNs: Long)

internal data class StartupBufferSnapshot(val buffered: Int, val dropped: Int, val oldestAgeMs: Long)

/**
 * Holds final translation requests recognized while Hy-MT2 is still loading.
 *
 * Capture starts as soon as ASR is ready, so endpoints can arrive well before the translation
 * model does. The steady-state `finals` mailbox is only 2 deep — sized for normal backpressure,
 * not for a multi-second model load — so routing loading-time finals through it would silently
 * and permanently drop recognized speech (`SegmentLedger` never revisits a SKIPPED caption).
 * This buffer exists solely to bridge that startup window: bounded by count and by age, with
 * every eviction reported rather than silently discarded.
 */
internal class StartupFinalBuffer<T>(
    private val capacity: Int = 16,
    private val maxAgeNs: Long = 15_000_000_000L,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val items = ArrayDeque<Aged<T>>()
    private var droppedCount = 0

    @Synchronized fun offer(value: T): T? {
        evictStaleLocked()
        val dropped = if (items.size >= capacity) items.removeFirst().value else null
        if (dropped != null) droppedCount++
        items.addLast(Aged(value, nowNs()))
        return dropped
    }

    @Synchronized fun poll(): T? {
        evictStaleLocked()
        return items.removeFirstOrNull()?.value
    }

    @Synchronized fun isEmpty(): Boolean { evictStaleLocked(); return items.isEmpty() }
    @Synchronized fun size(): Int { evictStaleLocked(); return items.size }

    @Synchronized fun snapshot(): StartupBufferSnapshot {
        evictStaleLocked()
        val oldest = items.firstOrNull()?.let { (nowNs() - it.queuedAtNs) / 1_000_000L } ?: 0L
        return StartupBufferSnapshot(items.size, droppedCount, oldest)
    }

    /** Everything still queued when the model finally becomes ready, or the session ends. */
    @Synchronized fun drainAll(): List<T> {
        val all = items.map { it.value }
        items.clear()
        return all
    }

    private fun evictStaleLocked(): List<T> {
        val evicted = mutableListOf<T>()
        val now = nowNs()
        while (items.isNotEmpty() && now - items.first().queuedAtNs > maxAgeNs) {
            evicted += items.removeFirst().value
            droppedCount++
        }
        return evicted
    }

    /** Same as offer, but also returns anything the age check evicted in the same pass. */
    @Synchronized fun offerReportingStale(value: T): Pair<T?, List<T>> {
        val stale = evictStaleLocked()
        val dropped = if (items.size >= capacity) items.removeFirst().value else null
        if (dropped != null) droppedCount++
        items.addLast(Aged(value, nowNs()))
        return dropped to stale
    }
}
