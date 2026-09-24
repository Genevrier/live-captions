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
    }
    @Test fun correctionRetranslatesSameSegmentAndRejectsOldResult() {
        val ledger = SegmentLedger(); ledger.start(1)
        val row = ledger.source(1, "Hallo werelt", true, 0)!!
        ledger.translate(row.key, "Hello", 2, true)
        val revised = ledger.revise(row.key, "Hallo wereld")!!
        assertEquals(row.key.id, revised.key.id)
        assertFalse(ledger.translate(row.key, "Old result", 2, true))
        assertTrue(ledger.translate(revised.key, "Hello world", 2, true))
        assertEquals(CaptionStage.REVISED, ledger.snapshot().single().stage)
    }
    @Test fun skippedAndDiscontinuousSegmentsCannotBeRevived() {
        val ledger = SegmentLedger(); ledger.start(1)
        val row = ledger.source(1, "Hallo", false, 0)!!
        ledger.discontinuity(1)
        assertFalse(ledger.current(row.key))
        assertFalse(ledger.translate(row.key, "Late", 0, false))
        assertNull(ledger.revise(row.key, "Late correction"))
    }
}
