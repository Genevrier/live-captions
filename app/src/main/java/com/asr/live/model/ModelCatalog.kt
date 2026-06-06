package com.asr.live.model

/** The recognizer architecture a model uses; drives how the engine is built. */
enum class EngineKind {
    /** Streaming Zipformer transducer (true low-latency, English). */
    STREAMING_ZIPFORMER,
    /** Offline transducer (Parakeet TDT), VAD-segmented, English. */
    OFFLINE_PARAKEET,
    /** Whisper encoder/decoder, VAD-segmented, multilingual (+ translate-to-English). */
    WHISPER,
}

data class ModelInfo(
    val id: String,
    val displayName: String,
    /** Short label for the engine picker in the top bar. */
    val shortName: String,
    val tagline: String,
    val kind: EngineKind,
    /** sherpa-onnx release archive (.tar.bz2). */
    val url: String,
    /** Approximate download size, for the UI only; real size comes from Content-Length. */
    val approxMB: Int,
    val encoder: String,
    val decoder: String,
    /** Empty for Whisper (no joiner). */
    val joiner: String = "",
    val tokens: String = "tokens.txt",
    /** Sanity floors for extracted files; guard against truncated downloads. */
    val encoderMinBytes: Long = 0,
    val decoderMinBytes: Long = 0,
) {
    val isMultilingual: Boolean get() = kind == EngineKind.WHISPER

    /** Files that must exist on disk for the model to be considered installed. */
    val requiredFiles: List<String>
        get() = buildList {
            add(encoder)
            add(decoder)
            if (joiner.isNotEmpty()) add(joiner)
            add(tokens)
        }
}

/**
 * On-device English + multilingual engines, all run fully offline through sherpa-onnx:
 *  - Streaming Zipformer: true low-latency English captions (default).
 *  - Parakeet TDT 0.6B: SOTA-accuracy English, VAD-segmented.
 *  - Whisper base/small: ~90 languages incl. Turkish, with optional translate-to-English.
 */
object ModelCatalog {
    private const val REL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

    val STREAMING = ModelInfo(
        id = "streaming-zipformer-en",
        displayName = "Streaming Zipformer (English)",
        shortName = "Streaming",
        tagline = "Real-time, low-latency English captions",
        kind = EngineKind.STREAMING_ZIPFORMER,
        url = REL + "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
        approxMB = 296,
        encoder = "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
        decoder = "decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
        joiner = "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
        encoderMinBytes = 40_000_000L, // ~68 MB expected
    )

    val PARAKEET = ModelInfo(
        id = "parakeet-tdt-0.6b-en",
        displayName = "NVIDIA Parakeet TDT 0.6B",
        shortName = "Accuracy",
        tagline = "Highest-accuracy English (OpenASR leader), ~1s delay",
        kind = EngineKind.OFFLINE_PARAKEET,
        url = REL + "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
        approxMB = 460,
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
        encoderMinBytes = 400_000_000L, // ~652 MB expected
    )

    val WHISPER_BASE = ModelInfo(
        id = "whisper-base",
        displayName = "Multilingual · Whisper base",
        shortName = "Multi-base",
        tagline = "~90 languages incl. Turkish — fast, lighter",
        kind = EngineKind.WHISPER,
        url = REL + "sherpa-onnx-whisper-base.tar.bz2",
        approxMB = 197,
        encoder = "base-encoder.int8.onnx",
        decoder = "base-decoder.int8.onnx",
        tokens = "base-tokens.txt",
        encoderMinBytes = 18_000_000L,
        decoderMinBytes = 45_000_000L,
    )

    val WHISPER_SMALL = ModelInfo(
        id = "whisper-small",
        displayName = "Multilingual · Whisper small",
        shortName = "Multi-small",
        tagline = "~90 languages incl. Turkish — best accuracy",
        kind = EngineKind.WHISPER,
        url = REL + "sherpa-onnx-whisper-small.tar.bz2",
        approxMB = 609,
        encoder = "small-encoder.int8.onnx",
        decoder = "small-decoder.int8.onnx",
        tokens = "small-tokens.txt",
        encoderMinBytes = 90_000_000L,  // ~112 MB expected
        decoderMinBytes = 200_000_000L, // ~262 MB expected
    )

    // Order = recommended first within each tier; small (better) listed before base.
    val ALL = listOf(STREAMING, PARAKEET, WHISPER_SMALL, WHISPER_BASE)
    val DEFAULT = STREAMING

    fun byId(id: String?): ModelInfo? = ALL.firstOrNull { it.id == id }
}
