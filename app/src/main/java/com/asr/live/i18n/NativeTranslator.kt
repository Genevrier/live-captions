package com.asr.live.i18n

import com.asr.live.pipeline.Profile
import java.io.File

/** The worker owns translate/close. A controller may request cancellation without freeing the model. */
class NativeTranslator(path: File, threads: Int, private val opus: Boolean, private val profile: Profile, private val glossary: String = "") : LocalTranslator {
    private var handle: Long = load(path.absolutePath.toByteArray(Charsets.UTF_8), threads, opus)
    override fun translate(text: String): String {
        val pointer = synchronized(this) { check(handle != 0L); handle }
        val prompt = if (opus) text else TranslationPrompt.build(profile, text, glossary)
        val result = run(pointer, prompt.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8).trim()
        check(result.isNotEmpty()) { "Translator returned empty output" }
        return result
    }
    @Synchronized override fun cancel() { if (handle != 0L) abort(handle) }
    @Synchronized override fun close() { if (handle != 0L) { free(handle); handle = 0L } }
    private external fun load(path: ByteArray, threads: Int, opus: Boolean): Long
    private external fun run(handle: Long, text: ByteArray): ByteArray
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
