package com.asr.live.pipeline

/** Admission control for live translations; the mailbox itself coalesces queued stale work. */
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
        val addedSentenceBoundary = candidate.lastOrNull() in setOf('.', '!', '?', '。', '！', '？') &&
            previous.lastOrNull() !in setOf('.', '!', '?', '。', '！', '？')
        return changedText.length >= MIN_MATERIAL_CHANGE || addedSentenceBoundary
    }
}
