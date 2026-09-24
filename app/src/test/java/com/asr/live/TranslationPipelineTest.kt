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
