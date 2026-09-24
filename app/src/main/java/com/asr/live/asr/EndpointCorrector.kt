package com.asr.live.asr

import com.k2fsa.sherpa.onnx.*
import java.io.File

/** Owned, decoded and released exclusively by the correction worker. */
class EndpointCorrector(dir: File) : AutoCloseable {
    private val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig = OfflineModelConfig(transducer = OfflineTransducerModelConfig(
            encoder = File(dir, "encoder.int8.onnx").absolutePath,
            decoder = File(dir, "decoder.int8.onnx").absolutePath,
            joiner = File(dir, "joiner.int8.onnx").absolutePath),
            tokens = File(dir, "tokens.txt").absolutePath, numThreads = 2,
            provider = "cpu", modelType = "nemo_transducer")))
    fun decode(samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, 16000); recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally { stream.release() }
    }
    override fun close() = recognizer.release()
}
