package com.asr.live.i18n

/** One instance per worker; create/use/close on that worker. */
interface LocalTranslator : AutoCloseable {
    fun translate(text: String): String
    val backend: String get() = "CPU"
    val timings: TranslationTimings get() = TranslationTimings()
    fun warmUp() {}
    fun cancel() {}
}

data class TranslationTimings(val prefillMs: Long = 0, val decodeMs: Long = 0)
