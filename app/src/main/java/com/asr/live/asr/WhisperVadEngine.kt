package com.asr.live.asr

import com.asr.live.model.ModelInfo
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Multilingual offline ASR via Whisper. Silero VAD segments the stream into utterances;
 * each is decoded by the Whisper recognizer.
 *
 * @param language ISO code Whisper should assume (e.g. "tr", "en"); "" = auto-detect.
 * @param task "transcribe" (keep source language) or "translate" (Whisper → English).
 */
class WhisperVadEngine(
    modelDir: File,
    vadPath: String,
    info: ModelInfo,
    language: String,
    task: String,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
) : AsrEngine {

    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadPath,
                threshold = 0.5f,
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 4f, // Bound phrase latency for compatibility recognition
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
        )
    )

    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(modelDir, info.encoder).absolutePath,
                    decoder = File(modelDir, info.decoder).absolutePath,
                    language = language,
                    task = task,
                ),
                tokens = File(modelDir, info.tokens).absolutePath,
                numThreads = 4,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
    )


    override fun accept(samples: FloatArray) {
        vad.acceptWaveform(samples)
        drain()

    }

    override fun finish() {
        vad.flush()
        drain()
        onPartial("")
    }

    override fun release() {
        vad.release()
        recognizer.release()
    }

    private fun drain() {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val text = transcribe(segment.samples)
            if (text.isNotBlank()) onFinal(text)
        }
    }

    private fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
    }
}
