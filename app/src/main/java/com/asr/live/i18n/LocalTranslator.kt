package com.asr.live.i18n

/** One instance per worker; create/use/close on that worker. */
interface LocalTranslator : AutoCloseable {
    fun translate(text: String): String
    /** Request-scoped overload lets cooperative native backends abort only obsolete work. */
    fun translate(text: String, requestId: Long): String = translate(text)
    val backend: String get() = "CPU"
    val timings: TranslationTimings get() = TranslationTimings()
    fun warmUp() {}
    fun cancel() {}
    fun cancel(requestId: Long) {}
}

data class TranslationTimings(
    val prefillMs: Long = 0,
    val decodeMs: Long = 0,
    val firstTokenMs: Long = 0,
    val outputTokens: Long = 0,
) {
    val tokensPerSecond: Double get() = if (decodeMs > 0) outputTokens * 1000.0 / decodeMs else 0.0
}
