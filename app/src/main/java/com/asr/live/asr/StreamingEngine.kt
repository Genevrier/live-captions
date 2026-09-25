package com.asr.live.asr

import com.asr.live.model.ModelInfo
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * True streaming recognizer (Zipformer transducer). Emits a growing partial as you
 * speak and finalizes a line when sherpa-onnx detects an endpoint (a pause).
 */
class StreamingEngine(
    modelDir: File,
    info: ModelInfo,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val language: String = "en",
    threads: Int = 6,
    qnnLibraries: String? = null,
) : AsrEngine {

    private val decodeTelemetry = DecodeTelemetry()
    override val decodeStats: AsrDecodeStats get() = decodeTelemetry.snapshot()
    private var audioFeedNanos = 0L
    private var resultNanos = 0L
    private var endpointCheckNanos = 0L
    override val pipelineStats: AsrPipelineStats
        get() = AsrPipelineStats(audioFeedNanos, resultNanos, endpointCheckNanos)

    private val recognizer = OnlineRecognizer(
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    qnnConfig = if (qnnLibraries != null) com.k2fsa.sherpa.onnx.QnnConfig(
                        backendLib = File(qnnLibraries, "libQnnHtp.so").absolutePath,
                        systemLib = File(qnnLibraries, "libQnnSystem.so").absolutePath,
                        contextBinary = listOf("encoder.bin", "decoder.bin", "joiner.bin").joinToString(",") { File(modelDir, it).absolutePath }
                    ) else com.k2fsa.sherpa.onnx.QnnConfig(),
                    encoder = if (qnnLibraries == null) File(modelDir, info.encoder).absolutePath else "",
                    decoder = if (qnnLibraries == null) File(modelDir, info.decoder).absolutePath else "",
                    joiner = if (qnnLibraries == null) File(modelDir, info.joiner).absolutePath else "",
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = threads,
                provider = if (qnnLibraries != null) "qnn" else "cpu",
                modelType = if (qnnLibraries != null) "nemo_transducer" else if (info.kind == com.asr.live.model.EngineKind.NEMOTRON) "" else "zipformer2",
            ),
            endpointConfig = EndpointConfig(),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
    )

    private val stream = recognizer.createStream().also {
        if (info.kind == com.asr.live.model.EngineKind.NEMOTRON) it.setOption("language", com.asr.live.pipeline.BackendPolicy.nemotronLanguage(language))
    }
    private val qnnLibrariesForCallbacks = qnnLibraries
    private var lastPartial = ""

    override fun accept(samples: FloatArray) {
        val feedStarted = System.nanoTime()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        audioFeedNanos += System.nanoTime() - feedStarted
        while (recognizer.isReady(stream)) decodeTelemetry.measure { recognizer.decode(stream) }

        val resultStarted = System.nanoTime()
        val text = recognizer.getResult(stream).text
        resultNanos += System.nanoTime() - resultStarted
        val endpointStarted = System.nanoTime()
        val isEndpoint = recognizer.isEndpoint(stream)
        endpointCheckNanos += System.nanoTime() - endpointStarted
        if (isEndpoint) {
            onFinal(text)
            recognizer.reset(stream)
            stream.setOption("language", com.asr.live.pipeline.BackendPolicy.nemotronLanguage(language))
            lastPartial = ""
            if (qnnLibrariesForCallbacks == null) onPartial("")
        } else if (text != lastPartial) {
            lastPartial = text
            onPartial(text)
        }
    }

    override fun finish() {
        val feedStarted = System.nanoTime()
        stream.inputFinished()
        audioFeedNanos += System.nanoTime() - feedStarted
        while (recognizer.isReady(stream)) decodeTelemetry.measure { recognizer.decode(stream) }
        val resultStarted = System.nanoTime()
        val text = recognizer.getResult(stream).text
        resultNanos += System.nanoTime() - resultStarted
        if (text.isNotBlank()) onFinal(text)
        onPartial("")
    }

    override fun release() {
        stream.release()
        recognizer.release()
    }

    private companion object {
        const val SAMPLE_RATE = 16000
    }
}
