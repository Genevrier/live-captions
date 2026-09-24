package com.asr.live.i18n

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * On-device text translation via ML Kit. The language model (~30 MB, English-pivoted) is
 * downloaded once on first use, then translation runs fully offline.
 *
 * All methods block; call them from a worker thread (never the main thread).
 */
class MlKitTranslator(sourceCode: String, targetCode: String) {

    private val source = TranslateLanguage.fromLanguageTag(sourceCode)
    private val target = TranslateLanguage.fromLanguageTag(targetCode)

    val supported: Boolean get() = source != null && target != null

    private val client: Translator? =
        if (supported) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source!!)
                    .setTargetLanguage(target!!)
                    .build()
            )
        } else null

    /** Downloads the model if needed. Throws on failure (e.g. no network on first use). */
    fun prepare() {
        client?.let { Tasks.await(it.downloadModelIfNeeded()) }
    }

    /** Fails visibly when the on-device translator cannot produce a result. */
    fun translate(text: String): String {
        val c = client ?: error("Unsupported translation direction")
        return Tasks.await(c.translate(text))
    }

    fun close() {
        client?.close()
    }
}
