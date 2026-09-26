package com.asr.live.pipeline.engine

/**
 * chrF++ (character n-gram F-score, extended with word n-grams): a reproducible, license-free,
 * locally computable translation-quality metric. No model judges another model's output here —
 * this is character/word n-gram overlap against a fixed reference, the same family of metric
 * used by sacrebleu's `chrf` with default settings (char order 6, word order 2, beta 2).
 *
 * This is a *lightweight screening* signal for Stage B candidate elimination, not a substitute
 * for held-out human evaluation or COMET. A high chrF++ score means the wording overlaps the
 * reference closely; it does not by itself certify semantic correctness (see [SemanticChecks]
 * for the negation/number/unit spot checks that catch what n-gram overlap can miss).
 */
object ChrFScorer {
    /** 0.0 (no overlap) .. 100.0 (identical to the reference). */
    fun score(hypothesis: String, reference: String, charOrder: Int = 6, wordOrder: Int = 2, beta: Double = 2.0): Double {
        if (reference.isBlank()) return if (hypothesis.isBlank()) 100.0 else 0.0
        if (hypothesis.isBlank()) return 0.0

        val hypChars = normalizeChars(hypothesis)
        val refChars = normalizeChars(reference)
        val hypWords = normalizeWords(hypothesis)
        val refWords = normalizeWords(reference)

        val components = (1..charOrder).map { n -> ngramF(charGrams(hypChars, n), charGrams(refChars, n), beta) } +
            (1..wordOrder).map { n -> ngramF(wordGrams(hypWords, n), wordGrams(refWords, n), beta) }
        val counted = components.filterNotNull()
        if (counted.isEmpty()) return 0.0
        return 100.0 * counted.average()
    }

    private fun normalizeChars(text: String) = text.trim().lowercase().filterNot { it.isWhitespace() }
    private fun normalizeWords(text: String) = text.trim().lowercase()
        .split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun charGrams(chars: String, n: Int): List<String> =
        if (chars.length < n) emptyList() else (0..chars.length - n).map { chars.substring(it, it + n) }
    private fun wordGrams(words: List<String>, n: Int): List<String> =
        if (words.size < n) emptyList() else (0..words.size - n).map { words.subList(it, it + n).joinToString(" ") }

    /** F-beta over multiset-overlap precision/recall for one n-gram order; null when both sides have none. */
    private fun ngramF(hyp: List<String>, ref: List<String>, beta: Double): Double? {
        if (hyp.isEmpty() && ref.isEmpty()) return null
        if (hyp.isEmpty() || ref.isEmpty()) return 0.0
        val hypCounts = hyp.groupingBy { it }.eachCount()
        val refCounts = ref.groupingBy { it }.eachCount()
        val matched = hypCounts.entries.sumOf { (gram, count) -> minOf(count, refCounts[gram] ?: 0) }
        val precision = matched.toDouble() / hyp.size
        val recall = matched.toDouble() / ref.size
        if (precision == 0.0 && recall == 0.0) return 0.0
        val beta2 = beta * beta
        return (1 + beta2) * precision * recall / (beta2 * precision + recall)
    }
}
