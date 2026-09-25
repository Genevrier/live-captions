package com.asr.live.pipeline

/** Admission control for cumulative stable prefixes; translation boundaries never reset ASR. */
object ProvisionalTranslationPolicy {
    const val DEBOUNCE_MS = 650L
    const val MIN_STABLE_CHARACTERS = 4
    const val MIN_MATERIAL_CHANGE = 4

    fun shouldTranslate(previous: String, next: String, nowMs: Long, lastSubmittedAtMs: Long): Boolean {
        val candidate = next.trim()
        if (candidate.length < MIN_STABLE_CHARACTERS || candidate == previous ||
            nowMs - lastSubmittedAtMs < DEBOUNCE_MS) return false
        if (previous.isBlank()) return true

        val shared = previous.zip(candidate).takeWhile { (left, right) -> left == right }.size
        val changedText = candidate.drop(shared).trim()
        // Clause punctuation may produce a useful subtitle before the recognizer's existing
        // endpoint. The translator still receives the complete stable prefix, preserving
        // preceding context for negation, numbers, and phrase-final words.
        val boundaries = setOf('.', '!', '?', '。', '！', '？', ',', ';', ':', '，', '；', '：')
        val addedBoundary = candidate.lastOrNull() in boundaries && previous.lastOrNull() !in boundaries
        return changedText.length >= MIN_MATERIAL_CHANGE || addedBoundary
    }
}
