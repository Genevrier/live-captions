package com.asr.live.pipeline.engine

/**
 * Deliberately narrow, explainable heuristics for a few failure modes that character n-gram
 * overlap (chrF++) can miss or over/under-penalize. These are diagnostics attached to a
 * candidate's evaluation, not a semantic-correctness classifier:
 *
 *  - [negationMarkerDropped] flags the case where the reference has an explicit negation marker
 *    and the hypothesis has *none of the tracked ones*. This is a marker-presence check, not a
 *    semantic classifier, and it fails in both directions: it cannot detect a negation reworded
 *    without any tracked marker (false negative for "dropped"), a wrong-scope negation, or a
 *    double negative — and a correct translation that rewords negation idiomatically (e.g.
 *    "cannot" -> "is unable to") will be flagged as if it dropped the negation (false positive).
 *    Callers must treat every result as a coarse hint to review, never as "flagged = wrong" or
 *    "not flagged = correct."
 *  - [numberDigitsMissing] extracts digit sequences from the reference and checks the same
 *    digits, or their word form for small numbers, appear in the hypothesis. It accepts
 *    "21" == "twenty-one" for 0-20 in en/fr/nl, but does not attempt date/decimal reformatting
 *    equivalence beyond that.
 */
object SemanticChecks {
    private val negationMarkers = mapOf(
        "en" to setOf(" not ", " no ", " n't", " never ", " none ", " cannot "),
        "fr" to setOf(" ne ", " pas ", " non ", " jamais ", " aucun"),
        "nl" to setOf(" niet", " geen", " nee", " nooit"),
    )
    private val smallNumberWords = mapOf(
        "en" to mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
            "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "twenty" to 20, "ninety" to 90),
        "fr" to mapOf("zéro" to 0, "un" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5,
            "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10, "quinze" to 15, "quarante" to 40),
        "nl" to mapOf("nul" to 0, "een" to 1, "twee" to 2, "drie" to 3, "vier" to 4, "vijf" to 5,
            "zes" to 6, "zeven" to 7, "acht" to 8, "negen" to 9, "tien" to 10, "negentig" to 90),
    )

    /** True only when the reference clearly negates and the hypothesis shows no negation marker at all. */
    fun negationMarkerDropped(hypothesis: String, reference: String, targetLanguage: String): Boolean {
        val markers = negationMarkers[targetLanguage] ?: return false
        val refPadded = " ${reference.lowercase()} "
        val hypPadded = " ${hypothesis.lowercase()} "
        val referenceHasNegation = markers.any { refPadded.contains(it) }
        if (!referenceHasNegation) return false
        return markers.none { hypPadded.contains(it) }
    }

    /** Digit sequences present in the reference but absent (as digits or a known small-number word) from the hypothesis. */
    fun numberDigitsMissing(hypothesis: String, reference: String, targetLanguage: String): List<String> {
        val digitGroups = Regex("\\d+").findAll(reference).map { it.value }.toList()
        if (digitGroups.isEmpty()) return emptyList()
        val hypLower = hypothesis.lowercase()
        val words = smallNumberWords[targetLanguage].orEmpty()
        return digitGroups.filterNot { digits ->
            hypothesis.contains(digits) || words.any { (word, value) -> value.toString() == digits && hypLower.contains(word) }
        }
    }
}
