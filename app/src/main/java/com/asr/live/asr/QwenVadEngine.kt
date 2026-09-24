package com.asr.live.asr

import com.k2fsa.sherpa.onnx.*
import java.io.File

/** Qwen3 is phrase recognition, not a streaming transducer. Four-second VAD windows
 * bound decoder work and avoid waiting for a long uninterrupted monologue. */
class QwenVadEngine(dir: File, vadPath: String, threads: Int,
                    private val onFinal: (String) -> Unit) : AsrEngine {
    private val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 128),
        modelConfig = OfflineModelConfig(qwen3Asr = OfflineQwen3AsrModelConfig(
            convFrontend = File(dir, "conv_frontend.onnx").absolutePath,
            encoder = File(dir, "encoder.int8.onnx").absolutePath,
            decoder = File(dir, "decoder.int8.onnx").absolutePath,
            tokenizer = File(dir, "tokenizer").absolutePath,
            maxTotalLen = 512, maxNewTokens = 128, temperature = 0.000001f),
            numThreads = threads, provider = "cpu")))
    private val vad = try {
        Vad(config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
            model = vadPath, threshold = 0.5f, minSilenceDuration = 0.3f,
            minSpeechDuration = 0.25f, windowSize = 512, maxSpeechDuration = 4f),
            sampleRate = 16000, numThreads = 1, provider = "cpu"))
    } catch (t: Throwable) { recognizer.release(); throw t }

    override fun accept(samples: FloatArray) { vad.acceptWaveform(samples); drain() }
    override fun finish() { vad.flush(); drain() }
    override fun release() { vad.release(); recognizer.release() }
    private fun drain() {
        while (!vad.empty()) {
            val audio = vad.front().samples; vad.pop()
            val stream = recognizer.createStream()
            try {
                // Qwen's runtime prepends "language Chinese" to its decoder prompt.
                stream.setOption("language", "Chinese")
                stream.acceptWaveform(audio, 16000); recognizer.decode(stream)
                recognizer.getResult(stream).text.trim().takeIf { it.isNotBlank() }?.let(onFinal)
            } finally { stream.release(); audio.fill(0f) }
        }
    }
}
