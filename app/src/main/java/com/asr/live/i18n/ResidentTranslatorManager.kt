package com.asr.live.i18n

/**
 * Configuration identity for one resident translator slot. Two sessions with an identical
 * identity can safely share the same native model/context; anything else forces a fresh load.
 */
data class TranslatorIdentity(
    val modelBundle: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val glossary: String,
    val backendRequestsOpenCl: Boolean,
    val batch: Int,
    val ubatch: Int,
)

/**
 * Keeps a translator resident across Listen → Stop → Listen instead of reloading the GGUF (or
 * the OPUS ONNX graphs) every session. The Honor Magic V5 case this exists for: Hy-MT2 load time
 * dominates time-to-first-translated-caption, and nothing about Stop requires freeing the model
 * — only in-flight *requests* need to end.
 *
 * Safety: exactly one [CaptionSession] runs at a time (`CaptionService` waits for full
 * termination before starting a replacement), so a resident instance is never touched by two
 * sessions concurrently. A session that stops never closes its translator directly — it calls
 * [release], which leaves the instance resident. The instance is only closed here, by
 * [acquire] when the identity changes, or by [closeAll] under memory pressure / process teardown
 * — never while any session might still be calling [LocalTranslator.translate] on it, because
 * the caller of [acquire] is required to hold the session's own worker thread when it does so and
 * only the *previous* session's identity can be evicted, never a live one.
 */
object ResidentTranslatorManager {
    private data class Resident(val identity: TranslatorIdentity, val translator: LocalTranslator)

    private val slots = mutableMapOf<String, Resident>()

    /**
     * Returns the resident translator for [slot] if its identity matches, otherwise closes any
     * stale resident and builds a fresh one via [create]. [reused] receives whether the identity
     * matched, for telemetry.
     */
    @Synchronized fun acquire(slot: String, identity: TranslatorIdentity, create: () -> LocalTranslator,
                              reused: (Boolean) -> Unit = {}): LocalTranslator {
        val current = slots[slot]
        if (current != null && current.identity == identity) {
            reused(true)
            return current.translator
        }
        current?.let { runCatching { it.translator.close() } }
        slots.remove(slot)
        reused(false)
        val translator = create()
        slots[slot] = Resident(identity, translator)
        return translator
    }

    /** Session end: the instance stays resident. Only cancels are appropriate here, not close. */
    @Synchronized fun release(slot: String) { /* intentionally a no-op: residency is the point */ }

    /** Drops a slot's identity without closing, e.g. after a load that never produced an instance. */
    @Synchronized fun forget(slot: String, identity: TranslatorIdentity) {
        if (slots[slot]?.identity == identity) slots.remove(slot)
    }

    @Synchronized fun isResident(slot: String, identity: TranslatorIdentity): Boolean =
        slots[slot]?.identity == identity

    /** Memory pressure or process teardown: no session may be active when this is called. */
    @Synchronized fun closeAll() {
        slots.values.forEach { runCatching { it.translator.close() } }
        slots.clear()
    }
}
