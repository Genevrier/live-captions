package com.asr.live.asr

/**
 * A recognizer fed 16 kHz mono float samples. Implementations are NOT thread-safe:
 * every call must come from the same thread (the decode worker in CaptionService).
 */
interface AsrEngine {
    /** Feed a chunk of audio; may emit partial/final results via the callbacks. */
    fun accept(samples: FloatArray)

    /** Flush any buffered audio at stop and emit a trailing final result. */
    fun finish()

    fun release()
}
