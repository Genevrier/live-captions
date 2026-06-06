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
) : AsrEngine {

    private val recognizer = OnlineRecognizer(
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, info.encoder).absolutePath,
                    decoder = File(modelDir, info.decoder).absolutePath,
                    joiner = File(modelDir, info.joiner).absolutePath,
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 4,
                provider = "cpu",
                modelType = "zipformer2",
            ),
            endpointConfig = EndpointConfig(),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
    )

    private val stream = recognizer.createStream()
    private var lastPartial = ""

    override fun accept(samples: FloatArray) {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)

        val text = recognizer.getResult(stream).text
        if (recognizer.isEndpoint(stream)) {
            if (text.isNotBlank()) onFinal(text)
            recognizer.reset(stream)
            lastPartial = ""
            onPartial("")
        } else if (text != lastPartial) {
            lastPartial = text
            onPartial(text)
        }
    }

    override fun finish() {
        stream.inputFinished()
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = recognizer.getResult(stream).text
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
