package com.asr.live.pipeline

/** A second hypothesis is optional work, never an authority or an accumulating queue. */
object CorrectionPolicy {
    fun admit(profile: Profile, audioSamples: Int, audioDepth: Int, finalDepth: Int,
              busy: Boolean, previousRtf: Double): Boolean = profile.correctionSupported &&
        audioSamples in 8_000..320_000 && audioDepth <= 1 && finalDepth == 0 && !busy && previousRtf <= 0.5

    fun accept(original: String, candidate: String, elapsedMs: Long, audioDepth: Int): Boolean {
        val words = candidate.trim().split(Regex("\\s+"))
        return candidate.isNotBlank() && candidate != original && elapsedMs <= 3000 && audioDepth <= 1 &&
            candidate.length >= original.length / 2 && candidate.length <= maxOf(24, original.length * 2) &&
            (words.size < 6 || words.toSet().size > 2)
    }
}
