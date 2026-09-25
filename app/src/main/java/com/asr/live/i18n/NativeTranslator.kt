package com.asr.live.i18n

import com.asr.live.pipeline.Profile
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private fun interface NativeProgressCallback {
    fun onProgress(sessionId: Long, segmentId: Long, revision: Int, elapsedMs: Long, utf8: ByteArray)
}

/** The worker owns translate/close. A controller may request cancellation without freeing the model. */
class NativeTranslator(path: File, threads: Int, private val opus: Boolean, private val profile: Profile,
                       private val glossary: String = "", preferOpenCl: Boolean = false,
                       cacheDir: File? = null, batch: Int = 256, ubatch: Int = 128) : LocalTranslator {
    @Volatile private var handle: Long = load(path.absolutePath.toByteArray(Charsets.UTF_8), threads, batch, ubatch, opus,
        preferOpenCl, cacheDir?.absolutePath?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
    @Volatile private var lastTimings = TranslationTimings()
    private val activeRequestId = AtomicLong(NO_REQUEST)
    private val cancelledRequestId = AtomicLong(NO_REQUEST)
    private val contextOwner = ReentrantLock()
    @Volatile private var closing = false
    override val backend: String get() = contextOwner.withLock {
        synchronized(this) { if (handle == 0L) "Stopped" else backend(handle) }
    }
    override val timings: TranslationTimings get() = lastTimings
    override fun translate(text: String): String = translateInternal(text, WARMUP_REQUEST, WARMUP_SESSION, 0, 0, null)
    override fun translate(text: String, requestId: Long): String =
        translateInternal(text, requestId, WARMUP_SESSION, 0, 0, null)
    override fun translate(text: String, requestId: Long, sessionId: Long, segmentId: Long, revision: Int,
                           onProgress: (TranslationProgress) -> Unit): String =
        translateInternal(text, requestId, sessionId, segmentId, revision, onProgress)

    private fun translateInternal(text: String, requestId: Long, sessionId: Long, segmentId: Long, revision: Int,
                                  onProgress: ((TranslationProgress) -> Unit)?): String = contextOwner.withLock {
        val pointer = synchronized(this) {
            check(!closing && handle != 0L) { "Translator is closed" }
            activeRequestId.set(requestId)
            handle
        }
        try {
            // Reset native cancellation, then recheck the request id so a cancellation
            // racing with entry to JNI cannot be lost.
            resetCancellation(pointer)
            if (cancelledRequestId.get() == requestId) abort(pointer)
            val prompt = if (opus) text else TranslationPrompt.build(profile, text, glossary)
            val cacheScope = if (opus) ByteArray(0) else
                "hymt-chat-prompt-v1\u0000${profile.source}\u0000${profile.target}\u0000$glossary".toByteArray(Charsets.UTF_8)
            val callback = onProgress?.let { listener ->
                NativeProgressCallback { callbackSession, callbackSegment, callbackRevision, elapsedMs, utf8 ->
                    listener(TranslationProgress(callbackSession, callbackSegment, callbackRevision,
                        elapsedMs, String(utf8, Charsets.UTF_8), provisional = true))
                }
            }
            val result = run(pointer, prompt.toByteArray(Charsets.UTF_8), sessionId, segmentId, revision,
                cacheScope, callback).toString(Charsets.UTF_8).trim()
            if (cancelledRequestId.get() == requestId) throw CancellationException("Translation request $requestId was superseded")
            val durations = stats(pointer)
            lastTimings = TranslationTimings(
                prefillMs = durations.getOrElse(0) { 0 }, decodeMs = durations.getOrElse(1) { 0 },
                firstTokenMs = durations.getOrElse(2) { 0 }, outputTokens = durations.getOrElse(3) { 0 },
                inputTokens = durations.getOrElse(4) { 0 }, cacheReusedTokens = durations.getOrElse(5) { 0 },
                prefillDecodeUs = durations.getOrElse(6) { 0 }, prefillSyncUs = durations.getOrElse(7) { 0 },
                samplingUs = durations.getOrElse(8) { 0 }, decodeComputeUs = durations.getOrElse(9) { 0 },
                decodeSyncUs = durations.getOrElse(10) { 0 }, firstVisibleMs = durations.getOrElse(11) { 0 },
                completeMs = durations.getOrElse(12) { 0 }, deviceBytesAllocated = durations.getOrElse(13) { 0 },
                offloadedLayers = durations.getOrElse(14) { 0 }, totalLayers = durations.getOrElse(15) { 0 },
                fallbackCount = durations.getOrElse(16) { 0 },
            )
            check(result.isNotEmpty()) { "Translator returned empty output" }
            result
        } catch (t: Throwable) {
            if (cancelledRequestId.get() == requestId) throw CancellationException("Translation request $requestId was superseded")
            throw t
        } finally {
            activeRequestId.compareAndSet(requestId, NO_REQUEST)
            synchronized(this) { freeIfClosingLocked() }
        }
    }
    override fun warmUp() {
        if (!opus) translate("A short warm up sentence.")
    }
    override fun cancel() {
        val active = activeRequestId.get()
        if (active != NO_REQUEST) cancel(active)
    }
    override fun cancel(requestId: Long) = synchronized(this) {
        cancelledRequestId.set(requestId)
        if (handle != 0L && activeRequestId.get() == requestId) abort(handle)
    }
    override fun close() {
        synchronized(this) {
            if (closing) return
            closing = true
            val active = activeRequestId.get()
            if (handle != 0L && active != NO_REQUEST) {
                cancelledRequestId.set(active)
                abort(handle)
            }
            freeIfClosingLocked()
        }
        // If a worker owns the context, that worker frees it only after native run() returns.
        if (!contextOwner.isHeldByCurrentThread) contextOwner.withLock {
            synchronized(this) { freeIfClosingLocked() }
        }
    }
    private fun freeIfClosingLocked() {
        if (closing && handle != 0L && activeRequestId.get() == NO_REQUEST) {
            free(handle)
            handle = 0L
        }
    }
    private external fun resetCancellation(handle: Long)
    private external fun load(path: ByteArray, threads: Int, batch: Int, ubatch: Int,
                              opus: Boolean, preferOpenCl: Boolean, cacheDir: ByteArray): Long
    private external fun run(handle: Long, text: ByteArray, sessionId: Long, segmentId: Long, revision: Int,
                             cacheScope: ByteArray, callback: NativeProgressCallback?): ByteArray
    private external fun backend(handle: Long): String
    private external fun stats(handle: Long): LongArray
    private external fun abort(handle: Long)
    private external fun free(handle: Long)
    companion object {
        private const val NO_REQUEST = Long.MIN_VALUE
        private const val WARMUP_REQUEST = Long.MIN_VALUE + 1
        private const val WARMUP_SESSION = Long.MIN_VALUE + 2
        init { System.loadLibrary("live-translator") }
    }
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
