package com.asr.live.i18n

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * On-device text translation via ML Kit. The language model (~30 MB, English-pivoted) is
 * downloaded once on first use, then translation runs fully offline.
 *
 * All methods block; call them from a worker thread (never the main thread).
 */
class MlKitTranslator(sourceCode: String, targetCode: String) : LocalTranslator {

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

    /**
     * Model placement (CPU/GPU/NPU) is decided inside Play services and is not observable from
     * here, so this is reported as SDK-managed rather than defaulting to a false "CPU" claim —
     * the app's OpenCL toggle has no effect on ML Kit.
     */
    override val backend: String get() = "SDK-managed"

    /** Downloads the model if needed. Bounded so a network hang cannot stall calibration/startup forever. */
    fun prepare() {
        client?.let { awaitBounded(it.downloadModelIfNeeded(), PREPARE_TIMEOUT_MS, "ML Kit model download") }
    }

    /** Fails visibly when the on-device translator cannot produce a result. */
    override fun translate(text: String): String {
        val c = client ?: error("Unsupported translation direction")
        return awaitBounded(c.translate(text), TRANSLATE_TIMEOUT_MS, "ML Kit translation")
    }

    override fun close() {
        client?.close()
    }

    private fun <T> awaitBounded(task: com.google.android.gms.tasks.Task<T>, timeoutMs: Long, what: String): T =
        try { Tasks.await(task, timeoutMs, TimeUnit.MILLISECONDS) }
        catch (t: TimeoutException) { throw IllegalStateException("$what timed out after ${timeoutMs}ms", t) }

    companion object {
        private const val PREPARE_TIMEOUT_MS = 120_000L
        private const val TRANSLATE_TIMEOUT_MS = 10_000L
    }
}
