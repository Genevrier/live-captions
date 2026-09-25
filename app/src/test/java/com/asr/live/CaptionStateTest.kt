package com.asr.live

import android.os.SystemClock
import com.asr.live.model.ModelCatalog
import com.asr.live.pipeline.*
import com.asr.live.service.CaptionState
import com.asr.live.service.ListeningState
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
    @Test fun appendOnlyGrowthKeepsItsTranslatedPortionVisible() {
        val ledger = SegmentLedger(); ledger.start(1)
        ledger.source(1, "Hallo wereld vandaag", false, 1)
        val stable = ledger.source(1, "Hallo wereld tijdens", false, 2)!!
        assertEquals("Hallo wereld", stable.stableSource)
        val portion = TranslationPortionIdentity(1, stable.key.id, stable.stableSource)
        assertTrue(ledger.translatePortion(stable.key, portion, "Hello world", 0, false, 77))
        val rawEdit = ledger.source(1, "Hallo wereld morgen", false, 3)!!
        assertEquals(stable.key, rawEdit.key)
        assertEquals("Hello world", rawEdit.translation)
        val extended = ledger.source(1, "Hallo wereld morgen samen", false, 4)!!
        assertTrue(extended.key.revision > stable.key.revision)
        assertEquals("Hello world", extended.translation)
        assertEquals("Hallo wereld", extended.translatedSource)
        assertEquals(" morgen samen", extended.source.removePrefix(extended.translatedSource))
        assertEquals(77L, extended.translationRequestId)
        assertTrue(ledger.canTranslatePortion(stable.key, portion))

        val inFlightLedger = SegmentLedger(); inFlightLedger.start(2)
        inFlightLedger.source(2, "We blijven hier vandaag", false, 1)
        val pending = inFlightLedger.source(2, "We blijven hier morgen", false, 2)!!
        val pendingPortion = TranslationPortionIdentity(2, pending.key.id, pending.stableSource)
        val pendingGrowth = inFlightLedger.source(2, "We blijven hier morgen samen", false, 3)!!
        assertTrue(pendingGrowth.key.revision > pending.key.revision)
        assertTrue(inFlightLedger.translatePortion(pending.key, pendingPortion, "We stay here", 0, false, 79))
        assertEquals("We stay here", inFlightLedger.snapshot().single().translation)
    }

    @Test fun internalCorrectionInvalidatesAnInFlightTranslatedPortion() {
        val ledger = SegmentLedger(); ledger.start(1)
        ledger.source(1, "Ik wil dit wel doen", false, 1)
        val stable = ledger.source(1, "Ik wil dit wel graag", false, 2)!!
        assertEquals("Ik wil dit wel", stable.stableSource)
        val portion = TranslationPortionIdentity(1, stable.key.id, stable.stableSource)
        assertTrue(ledger.translatePortion(stable.key, portion, "I do want this", 0, false, 4))

        val corrected = ledger.source(1, "Ik wil dit niet graag", false, 3)!!
        assertFalse(ledger.canTranslatePortion(stable.key, portion))
        assertEquals("", corrected.translation)
        assertNull(corrected.translationPortion)
        assertFalse(ledger.translatePortion(stable.key, portion, "I do want this", 0, false, 5))
    }

    @Test fun longerOutOfOrderResultWinsAndShorterResultCannotRollCoverageBack() {
        val ledger = SegmentLedger(); ledger.start(1)
        ledger.source(1, "Ik kom morgen", false, 1)
        val short = ledger.source(1, "Ik kom morgen zeker", false, 2)!!
        assertEquals("Ik kom morgen", short.stableSource)
        val shortPortion = TranslationPortionIdentity(1, short.key.id, "Ik kom")
        val longPortion = TranslationPortionIdentity(1, short.key.id, short.stableSource)
        assertTrue(ledger.translatePortion(short.key, longPortion, "I am coming tomorrow", 0, false, 12))
        assertFalse(ledger.translatePortion(short.key, shortPortion, "I am coming", 0, false, 11))
        assertEquals("Ik kom morgen", ledger.snapshot().single().translatedSource)
        assertEquals("I am coming tomorrow", ledger.snapshot().single().translation)
    }
    @Test fun repeatedHypothesisDoesNotPromoteAnUnstableSuffixToStableText() {
        val ledger = SegmentLedger(); ledger.start(1)
        val first = ledger.source(1, "Goedemorgen allemaal", false, 1)!!
        val repeated = ledger.source(1, "Goedemorgen allemaal", false, 2)!!
        assertEquals(first.key, repeated.key)
        assertEquals("", repeated.stableSource)
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
    @Test fun lateUpdatesFromOldSessionCannotChangeCurrentUiState() {
        val oldGeneration = 995_301L
        val newGeneration = 995_302L
        CaptionState.begin(oldGeneration, SessionConfig(quality = TranslationQuality.ML_KIT), "Old")
        val old = CaptionState.source(oldGeneration, "oude tekst", true, 1)!!
        CaptionState.begin(newGeneration, SessionConfig(profile = Profile.ENGLISH_FRENCH,
            quality = TranslationQuality.ML_KIT), "New")
        val linesAfterRestart = CaptionState.lines.value

        assertNull(CaptionState.source(oldGeneration, "late old text", true, 2))
        assertFalse(CaptionState.translated(old.key, "old translation", 2, true))
        CaptionState.metrics(oldGeneration) { it.copy(asrMs = 999) }
        CaptionState.stopping(oldGeneration)
        CaptionState.stopped(oldGeneration)

        assertEquals(linesAfterRestart, CaptionState.lines.value)
        assertTrue(CaptionState.lines.value.none { it.source == "late old text" })
        assertEquals("English → French", CaptionState.metrics.value.profile)
        assertEquals(0, CaptionState.metrics.value.asrMs)
        assertEquals(ListeningState.STARTING, CaptionState.lifecycle.value)
        CaptionState.stopped(newGeneration)
    }
    @Test fun computedResultIsCountedOnlyAfterItIsPublishedAndRendered() {
        val id = 995_304L
        CaptionState.begin(id, SessionConfig(quality = TranslationQuality.ML_KIT), "Nemotron")
        val row = CaptionState.source(id, "Goedemorgen allemaal", false, 1)!!
        val stable = CaptionState.source(id, "Goedemorgen allemaal vandaag", false, 2)!!
        val portion = TranslationPortionIdentity(id, stable.key.id, stable.stableSource)
        val requestId = 92L
        val computedAt = SystemClock.elapsedRealtimeNanos()
        CaptionState.resultComputed(id, stable.key, computedAt, requestId, portion)
        assertTrue(CaptionState.translatedPortion(stable.key, portion, "Good morning everyone", 0, false, requestId))
        assertEquals(1L, CaptionState.metrics.value.resultsComputed)
        assertEquals(0L, CaptionState.metrics.value.resultsDisplayed)

        val displayedAt = SystemClock.elapsedRealtimeNanos()
        assertTrue(CaptionState.acknowledgeDisplayed(id, stable.key, displayedAt))
        assertFalse(CaptionState.acknowledgeDisplayed(id, stable.key, displayedAt + 1_000_000L))
        CaptionState.resultRejected(id, "stale translation revision")
        assertEquals(1L, CaptionState.metrics.value.resultsDisplayed)
        assertEquals(1L, CaptionState.metrics.value.resultsRejected)
        assertTrue(CaptionState.metrics.value.displayLatencyMs >= 0)
        assertNotNull(CaptionState.metrics.value.firstUsefulCaptionMs)
        assertEquals(1, CaptionState.metrics.value.rejectionReasons["stale translation revision"])
        CaptionState.stopped(id)
    }
    @Test fun stoppingCancelsPendingCaptions() {
        val ledger = SegmentLedger(); ledger.start(1)
        val row = ledger.source(1, "partial", false, 0)!!
        ledger.cancel()
        assertFalse(ledger.current(row.key))
        assertEquals(CaptionStage.CANCELLED, ledger.snapshot().single().stage)
    }
    @Test fun gracefulStopKeepsSegmentOpenUntilFinalTranslationArrives() {
        val id = 995_201L
        CaptionState.begin(id, SessionConfig(quality = TranslationQuality.ML_KIT), "Nemotron")
        val partial = CaptionState.source(id, "Ik denk", false, 1)!!
        CaptionState.stopping(id)
        assertEquals(ListeningState.STOPPING, CaptionState.lifecycle.value)
        assertTrue(CaptionState.current(partial.key))
        val endpoint = CaptionState.source(id, "Ik denk het wel", true, 2)!!
        assertEquals(partial.key.id, endpoint.key.id)
        assertTrue(CaptionState.translated(endpoint.key, "I think so", 2, true))
        assertEquals(CaptionStage.FINAL, CaptionState.lines.value.single().stage)
        CaptionState.stopped(id)
    }
    @Test fun lateMicrophoneStartedCallbackCannotUndoStartupCancellation() {
        val id = 995_203L
        CaptionState.begin(id, SessionConfig(quality = TranslationQuality.ML_KIT), "Nemotron")
        CaptionState.cancel(id)
        CaptionState.listening(id)
        assertEquals(ListeningState.STOPPING, CaptionState.lifecycle.value)
        CaptionState.stopped(id)
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
    @Test fun gracefulMailboxCloseDrainsExistingAudioAndRejectsLaterInput() {
        val queue = BoundedMailbox<Int>(2)
        assertNull(queue.offer(1)); assertNull(queue.offer(2))
        queue.closeForDrain()
        assertEquals(3, queue.offer(3))
        assertEquals(1, queue.poll()); assertEquals(2, queue.poll()); assertNull(queue.poll())
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
