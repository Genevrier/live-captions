package com.asr.live.service

import org.junit.Assert.*
import org.junit.Test

class StartupFinalBufferTest {
    private class FakeClock(private var nanos: Long = 0) {
        fun nowNs() = nanos
        fun advanceMs(ms: Long) { nanos += ms * 1_000_000L }
    }

    /** Mirrors the real defect: recognized finals that arrive while Hy-MT2 is loading must never
     * be silently and permanently dropped, only bounded and reported. */
    @Test fun fiveFinalsArrivingWhileLoadingAreAllRetainedUnderCapacity() {
        val buffer = StartupFinalBuffer<String>(capacity = 16, maxAgeNs = 15_000_000_000L)
        val dropped = (1..5).mapNotNull { buffer.offerReportingStale("final-$it").first }
        assertTrue("no capacity drop with 5 items under a capacity of 16", dropped.isEmpty())
        assertEquals(5, buffer.snapshot().buffered)
        assertEquals((1..5).map { "final-$it" }, generateSequence { buffer.poll() }.toList())
        assertTrue(buffer.isEmpty())
    }

    @Test fun overflowEvictsOldestFirstAndReportsIt() {
        val buffer = StartupFinalBuffer<Int>(capacity = 3, maxAgeNs = 15_000_000_000L)
        buffer.offer(1); buffer.offer(2); buffer.offer(3)
        val dropped = buffer.offer(4)
        assertEquals("oldest item evicted, not newest", 1, dropped)
        assertEquals(listOf(2, 3, 4), generateSequence { buffer.poll() }.toList())
        assertEquals(1, buffer.snapshot().dropped)
    }

    @Test fun entriesOlderThanMaxAgeAreEvictedAndReportedAsStale() {
        val clock = FakeClock()
        val buffer = StartupFinalBuffer<String>(capacity = 16, maxAgeNs = 10_000_000_000L, nowNs = clock::nowNs)
        buffer.offer("stale")
        clock.advanceMs(10_001)
        val (_, stale) = buffer.offerReportingStale("fresh")
        assertEquals(listOf("stale"), stale)
        assertEquals(listOf("fresh"), generateSequence { buffer.poll() }.toList())
        assertEquals(1, buffer.snapshot().dropped)
    }

    @Test fun snapshotReportsOldestAgeOfWhatIsStillBuffered() {
        val clock = FakeClock()
        val buffer = StartupFinalBuffer<String>(nowNs = clock::nowNs)
        buffer.offer("a")
        clock.advanceMs(500)
        buffer.offer("b")
        assertEquals(500, buffer.snapshot().oldestAgeMs)
    }

    @Test fun drainAllReturnsEverythingQueuedRightNowRegardlessOfAge() {
        // drainAll() is the session-end path (Stop's translation-abandon step, hard cancel):
        // staleness stops mattering once everything is about to be rejected anyway, so unlike
        // every other accessor it does not evict first.
        val clock = FakeClock()
        val buffer = StartupFinalBuffer<String>(maxAgeNs = 1_000_000_000L, nowNs = clock::nowNs)
        buffer.offer("a")
        clock.advanceMs(5_000)
        assertEquals("drainAll bypasses the age check other accessors apply", listOf("a"), buffer.drainAll())
        assertTrue(buffer.isEmpty())
    }

    @Test fun anyOtherAccessCallEvictsStaleEntriesFirst() {
        val clock = FakeClock()
        val buffer = StartupFinalBuffer<String>(maxAgeNs = 1_000_000_000L, nowNs = clock::nowNs)
        buffer.offer("a")
        clock.advanceMs(5_000)
        buffer.offer("b") // triggers eviction of "a" before "b" is added
        assertEquals(listOf("b"), buffer.drainAll())
    }

    @Test fun deterministicChronologicalOrderOnceDrained() {
        val buffer = StartupFinalBuffer<Int>()
        (1..8).forEach(buffer::offer)
        val order = generateSequence { buffer.poll() }.toList()
        assertEquals((1..8).toList(), order)
    }
}
