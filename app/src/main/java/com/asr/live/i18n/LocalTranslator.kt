package com.asr.live.i18n

/** One instance per worker; create/use/close on that worker. */
interface LocalTranslator : AutoCloseable {
    fun translate(text: String): String
    /** Request-scoped overload lets cooperative native backends abort only obsolete work. */
    fun translate(text: String, requestId: Long): String = translate(text)
    /** Progressive callbacks are request-tagged and remain provisional until this call returns. */
    fun translate(text: String, requestId: Long, sessionId: Long, segmentId: Long, revision: Int,
                  onProgress: (TranslationProgress) -> Unit): String = translate(text, requestId)
    val backend: String get() = "CPU"
    val timings: TranslationTimings get() = TranslationTimings()
    fun warmUp() {}
    fun cancel() {}
    fun cancel(requestId: Long) {}
}

data class TranslationProgress(
    val sessionId: Long,
    val segmentId: Long,
    val revision: Int,
    val elapsedMs: Long,
    val text: String,
    val provisional: Boolean = true,
)

data class TranslationTimings(
    val prefillMs: Long = 0,
    val decodeMs: Long = 0,
    val firstTokenMs: Long = 0,
    val outputTokens: Long = 0,
    val inputTokens: Long = 0,
    val cacheReusedTokens: Long = 0,
    val prefillDecodeUs: Long = 0,
    val prefillSyncUs: Long = 0,
    val samplingUs: Long = 0,
    val decodeComputeUs: Long = 0,
    val decodeSyncUs: Long = 0,
    val firstVisibleMs: Long = 0,
    val completeMs: Long = 0,
    val deviceBytesAllocated: Long = 0,
    val offloadedLayers: Long = 0,
    val totalLayers: Long = 0,
    val fallbackCount: Long = 0,
) {
    val tokensPerSecond: Double get() = if (decodeMs > 0) outputTokens * 1000.0 / decodeMs else 0.0
}
