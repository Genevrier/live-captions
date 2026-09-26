package com.asr.live.pipeline.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * These fixtures are hand-derived from the chrF/chrF++ formula (character n-gram multiset
 * F-score, beta=2) itself, not cross-checked against sacrebleu's reference implementation —
 * that cross-validation ("test it against pinned reference outputs from a trusted
 * implementation") is a known, explicitly flagged gap; see the project report. What is verified
 * here is the metric's basic mathematical properties: identity, total mismatch, and tolerance
 * for reordering, which any correct chrF implementation must satisfy.
 */
class ChrFTest {
    @Test fun identicalStringsScorePerfectly() {
        assertEquals(100.0, ChrFScorer.score("Good morning", "Good morning"), 1e-9)
    }

    @Test fun blankHypothesisAgainstNonBlankReferenceScoresZero() {
        assertEquals(0.0, ChrFScorer.score("", "Good morning"), 1e-9)
    }

    @Test fun bothBlankScoresPerfectly() {
        assertEquals(100.0, ChrFScorer.score("", ""), 1e-9)
    }

    @Test fun completelyDisjointShortStringsScoreZeroAtDefaultOrders() {
        assertEquals(0.0, ChrFScorer.score("xyz", "abc"), 1e-9)
    }

    /**
     * Word-order-only differences must not zero out the score: at character-unigram order the
     * two strings share the exact same multiset of characters ('a','a','b' vs 'a','b','a'), so
     * unigram precision and recall are both 1.0 by construction. This is a targeted regression
     * for exactly what the task brief called out — chrF must tolerate wording/order differences
     * that a literal string-equality metric would wrongly penalize.
     */
    @Test fun sameCharacterMultisetInDifferentOrderScoresPerfectlyAtUnigramOrder() {
        assertEquals(100.0, ChrFScorer.score("aab", "aba", charOrder = 1, wordOrder = 0), 1e-9)
    }

    @Test fun partialOverlapScoresStrictlyBetweenZeroAndTheIdenticalCase() {
        val partial = ChrFScorer.score("Good afternoon", "Good morning")
        assertTrue("shared prefix must score above zero", partial > 0.0)
        assertTrue("an imperfect match must score below the identical case", partial < 100.0)
    }

    @Test fun closerParaphraseScoresHigherThanAnUnrelatedSentence() {
        val reference = "Could you send the report before noon?"
        val paraphrase = ChrFScorer.score("Could you send the report before midday?", reference)
        val unrelated = ChrFScorer.score("The weather today is quite cold and windy.", reference)
        assertTrue(paraphrase > unrelated)
    }

    @Test fun scoreIsCaseAndWhitespaceInsensitiveForCharacterGrams() {
        val a = ChrFScorer.score("GOOD MORNING", "good morning")
        val b = ChrFScorer.score("good   morning", "good morning")
        assertEquals(100.0, a, 1e-9)
        assertEquals(100.0, b, 1e-9)
    }
}
