package com.asr.live

import com.asr.live.model.ModelCatalog
import com.asr.live.pipeline.*
import org.junit.Assert.*
import org.junit.Test

class CaptionStateTest {
    @Test fun routingIsExplicit() {
        assertEquals(ModelCatalog.QWEN3, ModelCatalog.defaultFor("zh"))
        assertTrue(ModelCatalog.QWEN3.supports("zh"))
        assertTrue(ModelCatalog.QWEN3.requiresVad)
        assertTrue(ModelCatalog.WHISPER_BASE.requiresVad)
        assertFalse(ModelCatalog.NEMOTRON.requiresVad)
        assertFalse(ModelCatalog.QWEN3.supports("nl"))
        assertEquals("nl", Profile.DUTCH_ENGLISH.source)
        assertEquals("en", Profile.DUTCH_ENGLISH.target)
        assertTrue(ModelCatalog.NEMOTRON.supports("nl"))
        assertFalse(ModelCatalog.PARAKEET.supports("zh"))
        assertFalse(Profile.CHINESE_ENGLISH.correctionSupported)
        assertTrue(Profile.ENGLISH_FRENCH.correctionSupported)
    }
    @Test fun stablePrefixUsesConsecutiveCompleteWords() {
        val stable = StablePrefix()
        assertEquals("", stable.accept("Hallo wer"))
        assertEquals("Hallo", stable.accept("Hallo wereld"))
        assertEquals("Hallo wereld", stable.accept("Hallo wereld vandaag"))
        assertEquals("", stable.accept("Dag wereld"))
    }
    @Test fun endpointReusesSegmentAndRejectsPartialResult() {
        val ledger = SegmentLedger(); ledger.start(1)
        val partial = ledger.source(1, "Hallo wer", false, 1)!!
        val endpoint = ledger.source(1, "Hallo wereld", true, 2)!!
        assertEquals(partial.key.id, endpoint.key.id)
        assertTrue(endpoint.key.revision > partial.key.revision)
        assertFalse(ledger.translate(partial.key, "Stale", 0, false))
        assertTrue(ledger.translate(endpoint.key, "Hello world", 2, true))
        assertFalse(ledger.translate(endpoint.key, "Late provisional", 0, false))
        assertEquals(1, ledger.snapshot().size)
        assertEquals("Hello world", ledger.snapshot().single().translation)
    }
    @Test fun restartRejectsPreviousSession() {
        val ledger = SegmentLedger(); ledger.start(1)
        val old = ledger.source(1, "old", true, 1)!!
        ledger.cancel(); ledger.start(2)
        val new = ledger.source(2, "new", true, 2)!!
        assertFalse(ledger.translate(old.key, "late", 2, true))
        assertTrue(ledger.translate(new.key, "fresh", 2, true))
        assertNull(ledger.source(1, "stale", true, 3))
    }
    @Test fun stoppingCancelsPendingCaptions() {
        val ledger = SegmentLedger(); ledger.start(1)
        val row = ledger.source(1, "partial", false, 0)!!
        ledger.cancel()
        assertFalse(ledger.current(row.key))
        assertEquals(CaptionStage.CANCELLED, ledger.snapshot().single().stage)
    }
    @Test fun clearDoesNotReuseIds() {
        val ledger = SegmentLedger(); ledger.start(1)
        val old = ledger.source(1, "old", true, 0)!!
        ledger.clear()
        val current = ledger.source(1, "new", true, 0)!!
        assertTrue(current.key.id > old.key.id)
        assertFalse(ledger.translate(old.key, "late", 2, true))
    }
    @Test fun mailboxCoalescesAndCloses() {
        val queue = BoundedMailbox<Int>(1)
        assertNull(queue.offer(1)); assertEquals(1, queue.offer(2))
        assertEquals(1, queue.size()); assertEquals(2, queue.poll())
        queue.close(); assertEquals(3, queue.offer(3)); assertNull(queue.poll())
    }
    @Test fun overloadReturnsExactlyTheLostAudio() {
        val queue = BoundedMailbox<Int>(2)
        queue.offer(1); queue.offer(2); assertEquals(1, queue.offer(3))
        assertEquals(2, queue.poll()); assertEquals(3, queue.poll()); assertNull(queue.poll())
    }
    @Test fun pcmIsBoundedAndRejectsIncompleteUtterance() {
        val pcm = PcmBuffer(4)
        pcm.append(floatArrayOf(1f,2f,3f)); pcm.append(floatArrayOf(4f,5f))
        assertNull(pcm.take())
        pcm.append(floatArrayOf(6f)); assertArrayEquals(floatArrayOf(6f), pcm.take(), 0f)
    }
    @Test fun rebuildingDiscardsStaleBacklogWithoutClosingCaptureQueue() {
        val queue = BoundedMailbox<Int>(2); val continuity = AudioContinuity()
        assertFalse(continuity.gap(1)); assertTrue(continuity.gap(4))
        queue.offer(5); queue.offer(6)
        assertEquals(listOf(5,6), queue.drain()); assertEquals(0, queue.size())
        continuity.reset(); queue.offer(10)
        assertFalse(continuity.gap(queue.poll()!!.toLong()))
        assertFalse(continuity.gap(11))
    }
    @Test fun captionHistoryIsBounded() {
        val ledger = SegmentLedger(2); ledger.start(1)
        repeat(5) { ledger.source(1, "$it", true, 0) }
        assertEquals(listOf("3", "4"), ledger.snapshot().map { it.source })
    }
}
