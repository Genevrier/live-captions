package com.asr.live.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrWavBenchmarkTest {
    @Test fun computesSubstitutionDeletionAndInsertionCountsAgainstHumanWords() {
        val result = WordErrorScorer.count(listOf("ik", "heb", "niet", "betaald"),
            listOf("ik", "heb", "wel", "betaald", "gisteren"))
        assertEquals(2, result.errors)
        assertEquals(1, result.substitutions)
        assertEquals(0, result.deletions)
        assertEquals(1, result.insertions)
        assertEquals(4, result.referenceWords)
        assertEquals(.5, result.wer, 0.0001)
    }

    @Test fun exactTranscriptHasNoWordErrors() {
        val result = WordErrorScorer.count(listOf("nee", "ik", "kom", "niet"),
            listOf("nee", "ik", "kom", "niet"))
        assertEquals(0, result.errors)
        assertEquals(0.0, result.wer, 0.0)
        assertTrue(result.referenceWords > 0)
    }

    @Test fun lateNegationIsScoredAsItsOwnHumanReferenceError() {
        val reference = WordErrorScorer.normalize("Ik heb dat nooit bevestigd.")
        val hypothesis = WordErrorScorer.normalize("Ik heb dat bevestigd.")
        val result = WordErrorScorer.countNegations(reference, hypothesis)
        assertEquals(1, result.deletions)
        assertEquals(1, result.errors)
    }
}
