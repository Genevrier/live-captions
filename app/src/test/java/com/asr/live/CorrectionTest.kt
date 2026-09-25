package com.asr.live

import com.asr.live.pipeline.*
import org.junit.Assert.*
import org.junit.Test

class CorrectionTest {
    @Test fun unsupportedAndOverloadedWorkIsRejected() {
        assertFalse(CorrectionPolicy.admit(Profile.CHINESE_ENGLISH, 16000, 0, 0, false, 0.0))
        assertFalse(CorrectionPolicy.admit(Profile.DUTCH_ENGLISH, 16000, 2, 0, false, 0.0))
        assertFalse(CorrectionPolicy.admit(Profile.DUTCH_ENGLISH, 16000, 0, 1, false, 0.0))
        assertFalse(CorrectionPolicy.admit(Profile.DUTCH_ENGLISH, 16000, 0, 0, true, 0.0))
        assertFalse(CorrectionPolicy.admit(Profile.DUTCH_ENGLISH, 16000, 0, 0, false, 0.6))
        assertTrue(CorrectionPolicy.admit(Profile.DUTCH_ENGLISH, 16000, 0, 0, false, 0.2))
    }
    @Test fun secondHypothesisMustBeTimelyAndPlausible() {
        assertFalse(CorrectionPolicy.accept("Goedemorgen iedereen", "", 100, 0))
        assertFalse(CorrectionPolicy.accept("Goedemorgen iedereen", "Hallo iedereen", 3001, 0))
        assertTrue(CorrectionPolicy.accept("Goedemorgen iedereen", "Goedemorgen allemaal", 800, 0))
        assertEquals(1_000_000_000L, CorrectionPolicy.remainingNanos(5_000_000_000L, 7_000_000_000L))
        assertEquals(0L, CorrectionPolicy.remainingNanos(5_000_000_000L, 8_000_000_000L))
    }
    @Test fun correctionPublishesRevisedSourceAndHyTranslationAtomically() {
        val ledger = SegmentLedger(); ledger.start(1)
        val row = ledger.source(1, "Hallo werelt", true, 0)!!
        ledger.translate(row.key, "Hello", 2, true)
        val committed = ledger.snapshot().single()
        val revised = ledger.reviseTranslated(row.key, "Hallo wereld", "Hello world")!!
        assertEquals(row.key.id, revised.key.id)
        assertEquals("Hallo werelt", committed.source)
        assertEquals("Hello", committed.translation)
        assertEquals("Hallo wereld", revised.source)
        assertEquals("Hello world", revised.translation)
        assertEquals(revised.key.revision, revised.translatedRevision)
        assertEquals(CaptionStage.REVISED, revised.stage)
        assertFalse(ledger.translate(row.key, "Old result", 2, true))
        assertNull(ledger.reviseTranslated(row.key, "Late correction", "Too late"))
    }
    @Test fun skippedAndDiscontinuousSegmentsCannotBeRevived() {
        val ledger = SegmentLedger(); ledger.start(1)
        val row = ledger.source(1, "Hallo", false, 0)!!
        ledger.discontinuity(1)
        assertFalse(ledger.current(row.key))
        assertFalse(ledger.translate(row.key, "Late", 0, false))
        assertNull(ledger.reviseTranslated(row.key, "Late correction", "Too late"))
    }
}
