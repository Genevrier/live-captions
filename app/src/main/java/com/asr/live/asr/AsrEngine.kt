package com.asr.live.asr

/**
 * A recognizer fed 16 kHz mono float samples. Implementations are NOT thread-safe:
 * every call must come from the same thread (the decode worker in CaptionService).
 */
interface AsrEngine {
    /** Cumulative timings for actual recognizer.decode() calls; callback-only updates add none. */
    val decodeStats: AsrDecodeStats get() = AsrDecodeStats()

    /** Time in the streaming input/result path; decode remains separately measured. */
    val pipelineStats: AsrPipelineStats get() = AsrPipelineStats()

    /** Feed a chunk of audio; may emit partial/final results via the callbacks. */
    fun accept(samples: FloatArray)

    /** Flush any buffered audio at stop and emit a trailing final result. */
    fun finish()

    fun release()
}

data class AsrPipelineStats(
    val audioFeedNanos: Long = 0,
    val resultNanos: Long = 0,
    val endpointCheckNanos: Long = 0,
)
