package com.asr.live.i18n

import com.asr.live.pipeline.Profile
import java.io.File

/** The worker owns translate/close. A controller may request cancellation without freeing the model. */
class NativeTranslator(path: File, threads: Int, private val opus: Boolean, private val profile: Profile,
                       private val glossary: String = "", preferOpenCl: Boolean = false,
                       cacheDir: File? = null, batch: Int = 256, ubatch: Int = 128) : LocalTranslator {
    @Volatile private var handle: Long = load(path.absolutePath.toByteArray(Charsets.UTF_8), threads, batch, ubatch, opus,
        preferOpenCl, cacheDir?.absolutePath?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
    @Volatile private var lastTimings = TranslationTimings()
    override val backend: String get() = if (handle == 0L) "Stopped" else backend(handle)
    override val timings: TranslationTimings get() = lastTimings
    override fun translate(text: String): String {
        val pointer = synchronized(this) { check(handle != 0L); handle }
        val prompt = if (opus) text else TranslationPrompt.build(profile, text, glossary)
        val result = run(pointer, prompt.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8).trim()
        val durations = stats(pointer)
        lastTimings = TranslationTimings(durations.getOrElse(0) { 0 }, durations.getOrElse(1) { 0 })
        check(result.isNotEmpty()) { "Translator returned empty output" }
        return result
    }
    override fun warmUp() {
        if (!opus) translate("A short warm up sentence.")
    }
    @Synchronized override fun cancel() { if (handle != 0L) abort(handle) }
    @Synchronized override fun close() { if (handle != 0L) { free(handle); handle = 0L } }
    private external fun load(path: ByteArray, threads: Int, batch: Int, ubatch: Int,
                              opus: Boolean, preferOpenCl: Boolean, cacheDir: ByteArray): Long
    private external fun run(handle: Long, text: ByteArray): ByteArray
    private external fun backend(handle: Long): String
    private external fun stats(handle: Long): LongArray
    private external fun abort(handle: Long)
    private external fun free(handle: Long)
    companion object { init { System.loadLibrary("live-translator") } }
}

object TranslationPrompt {
    fun build(profile: Profile, source: String, glossary: String): String {
        require(glossary.length <= 2000) { "Glossary is too long" }
        val terms = glossary.lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split("->", limit = 2)
            require(parts.size == 2 && parts.all { it.isNotBlank() }) { "Use one source -> target term per line" }
            "${parts[0].trim()} translates to ${parts[1].trim()}"
        }
        val target = if (profile.target == "fr") "French" else "English"
        return (if (terms.isEmpty()) "" else "Reference the following translations:\n${terms.joinToString("\n")}\n\n") +
            "Translate the following text into $target. Only output the translated result without any additional explanation:\n\n$source"
    }
}
