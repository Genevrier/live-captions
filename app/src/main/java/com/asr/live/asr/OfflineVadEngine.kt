package com.asr.live.asr

import com.asr.live.model.ModelInfo
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * High-accuracy path: Silero VAD chops the stream into utterances, each of which is
 * transcribed by an offline recognizer (Parakeet TDT). No word-by-word partials, but
 * far higher accuracy. A "…" partial is shown while speech is in progress.
 */
class OfflineVadEngine(
    modelDir: File,
    vadPath: String,
    info: ModelInfo,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
) : AsrEngine {

    private val decodeTelemetry = DecodeTelemetry()
    override val decodeStats: AsrDecodeStats get() = decodeTelemetry.snapshot()

    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadPath,
                threshold = 0.5f,
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
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
                transducer = OfflineTransducerModelConfig(
                    encoder = File(modelDir, info.encoder).absolutePath,
                    decoder = File(modelDir, info.decoder).absolutePath,
                    joiner = File(modelDir, info.joiner).absolutePath,
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 4,
                provider = "cpu",
                modelType = "nemo_transducer",
            ),
            decodingMethod = "greedy_search",
        )
    )

    private var speaking = false

    override fun accept(samples: FloatArray) {
        vad.acceptWaveform(samples)
        drain()
        val s = vad.isSpeechDetected()
        if (s != speaking) {
            speaking = s
            onPartial(if (s) "…" else "")
        }
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
            decodeTelemetry.measure { recognizer.decode(stream) }
            recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
    }
}
