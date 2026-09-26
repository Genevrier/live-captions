package com.asr.live.pipeline.engine

import org.junit.Assert.*
import org.junit.Test

class SemanticChecksTest {
    @Test fun flagsANegationThatWasDroppedEntirely() {
        assertTrue(SemanticChecks.negationMarkerDropped(
            hypothesis = "I can send the report now.",
            reference = "I cannot send the report now.", targetLanguage = "en"))
    }

    @Test fun doesNotFlagWhenTheNegationMarkerIsPreserved() {
        assertFalse(SemanticChecks.negationMarkerDropped(
            hypothesis = "I cannot send the report now.",
            reference = "I cannot send the report now.", targetLanguage = "en"))
    }

    @Test fun doesNotFlagWhenTheReferenceItselfHasNoNegation() {
        assertFalse(SemanticChecks.negationMarkerDropped(
            hypothesis = "The weather is nice today.",
            reference = "The weather is nice today.", targetLanguage = "en"))
    }

    /**
     * Documents the heuristic's known failure mode rather than hiding it: a correctly-reworded
     * negation that avoids every tracked marker (here, "unable to" instead of "cannot") is
     * flagged as "dropped" even though the translation is fine — a false positive, not a false
     * negative. This is exactly why the class doc insists this is a coarse marker-presence
     * check, not a semantic negation classifier: it must never be read as "not flagged = correct".
     */
    @Test fun aCorrectlyRewordedNegationWithoutATrackedMarkerIsAFalsePositiveHere() {
        assertTrue("known limitation: 'unable to' carries no tracked marker, so this reads as dropped",
            SemanticChecks.negationMarkerDropped(
                hypothesis = "I am unable to send the report now.",
                reference = "I cannot send the report now.", targetLanguage = "en"))
    }

    @Test fun frenchAndDutchNegationMarkersAreRecognized() {
        assertTrue(SemanticChecks.negationMarkerDropped("Je peux envoyer le rapport.",
            "Je ne peux pas envoyer le rapport.", "fr"))
        assertTrue(SemanticChecks.negationMarkerDropped("Ik kan het verslag versturen.",
            "Ik kan het verslag niet versturen.", "nl"))
    }

    @Test fun numberDigitsMissingReportsOnlyReferenceDigitsAbsentFromTheHypothesis() {
        assertEquals(listOf("90"), SemanticChecks.numberDigitsMissing(
            "The meeting starts at three.", "The meeting starts at three and lasts 90 minutes.", "en"))
        assertEquals(emptyList<String>(), SemanticChecks.numberDigitsMissing(
            "The meeting lasts 90 minutes.", "The meeting lasts 90 minutes.", "en"))
    }

    @Test fun numberDigitsMissingAcceptsTheSmallNumberWordEquivalent() {
        assertEquals(emptyList<String>(), SemanticChecks.numberDigitsMissing(
            "It lasts ninety minutes.", "It lasts 90 minutes.", "en"))
    }

    @Test fun numberDigitsMissingReturnsEmptyWhenTheReferenceHasNoDigits() {
        assertEquals(emptyList<String>(), SemanticChecks.numberDigitsMissing(
            "Anything at all.", "No numbers here.", "en"))
    }
}
