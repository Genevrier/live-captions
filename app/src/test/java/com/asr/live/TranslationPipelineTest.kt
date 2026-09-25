package com.asr.live

import com.asr.live.pipeline.*
import org.junit.Assert.*
import org.junit.Test

class TranslationPipelineTest {
    @Test fun provisionalTranslationDebouncesAndRequiresMaterialStableChanges() {
        assertTrue(ProvisionalTranslationPolicy.shouldTranslate("", "Goedemorgen", 1000, 0))
        assertFalse(ProvisionalTranslationPolicy.shouldTranslate("", "Hoi", 1000, 0))
        assertFalse(ProvisionalTranslationPolicy.shouldTranslate("Goedemorgen", "Goedemorgen", 2000, 1000))
        assertFalse(ProvisionalTranslationPolicy.shouldTranslate("Goedemorgen", "Goedemorgen nu", 1500, 1000))
        assertTrue(ProvisionalTranslationPolicy.shouldTranslate("Goedemorgen", "Goedemorgen allemaal", 1700, 1000))
        assertTrue(ProvisionalTranslationPolicy.shouldTranslate("Het is vandaag", "Het is vandaag.", 2000, 1000))
    }

    @Test fun clauseBoundaryCanTranslateBeforeEndpointWithoutShorteningTheAsrContext() {
        assertTrue(ProvisionalTranslationPolicy.shouldTranslate("Het is zover", "Het is zover,", 1700, 1000))

        val ledger = SegmentLedger(); ledger.start(31)
        ledger.source(31, "We lopen naar", false, 1000)
        val firstStable = ledger.source(31, "We lopen naar huis", false, 1700)!!
        val grown = ledger.source(31, "We lopen naar huis en praten", false, 2400)!!
        val longer = ledger.source(31, "We lopen naar huis en praten over morgen", false, 3100)!!
        assertEquals(firstStable.key.id, grown.key.id)
        assertEquals(grown.key.id, longer.key.id)
        assertTrue(longer.stableSource.startsWith(firstStable.stableSource))
        assertEquals("We lopen naar huis en praten", longer.stableSource)
        // Translation requests retain all recognized context; they are independent of the
        // recognizer's endpoint/reset and never turn a clause boundary into an ASR reset.
        assertTrue(longer.stableSource.contains("naar huis en praten"))
    }

    @Test fun lateNegationAndNumberUpdatesUseTheWholeStablePrefix() {
        assertTrue(ProvisionalTranslationPolicy.shouldTranslate(
            "Ik wil dit wel", "Ik wil dit niet", 2000, 1000))
        assertTrue(ProvisionalTranslationPolicy.shouldTranslate(
            "Het kost tweeënveertig", "Het kost tweeënveertig euro", 2000, 1000))
        assertTrue(ProvisionalTranslationPolicy.shouldTranslate(
            "We gaan naar huis", "We gaan naar huis.", 2000, 1000))
    }

    @Test fun opusAndHyResultsAreComparedForTheSameStableSourceAndCanBeRated() {
        val ledger = TranslationComparisonLedger(maxRows = 1)
        val key = SegmentKey(3, 9, 2)
        ledger.record(key, "Dit is dezelfde stabiele transcriptie", ComparisonEngine.OPUS, "This is the same stable transcript", 120)
        val row = ledger.record(key, "Dit is dezelfde stabiele transcriptie", ComparisonEngine.HY_MT2, "This is the same stable transcription", 410)
        assertEquals("Dit is dezelfde stabiele transcriptie", row.source)
        assertEquals(120L, row.opusMs)
        assertEquals(410L, row.hyMs)
        assertTrue(ledger.vote(key, TranslationVote.HY_MT2))
        assertEquals(TranslationVote.HY_MT2, ledger.snapshot().single().vote)
        assertFalse(ledger.vote(SegmentKey(3, 10, 0), TranslationVote.OPUS))
    }

    @Test fun comparisonHistoryIsBounded() {
        val ledger = TranslationComparisonLedger(maxRows = 2)
        repeat(3) { index ->
            val key = SegmentKey(1, index.toLong(), 0)
            ledger.record(key, "source $index", ComparisonEngine.OPUS, "result $index", index.toLong())
        }
        assertEquals(listOf(1L, 2L), ledger.snapshot().map { it.key.id })
    }
}
