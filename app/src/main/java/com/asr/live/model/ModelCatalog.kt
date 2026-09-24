package com.asr.live.model

/** The recognizer architecture a model uses; drives how the engine is built. */
enum class EngineKind {
    NEMOTRON,
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
    val sha256: String = "",
    val extraFiles: List<String> = emptyList(),
    val languages: Set<String> = setOf("en"),
    val archiveBytes: Long = 0,
) {
    fun supports(language: String) = language in languages
    val isMultilingual: Boolean get() = languages.size > 1

    /** Files that must exist on disk for the model to be considered installed. */
    val requiredFiles: List<String>
        get() = buildList {
            add(encoder)
            add(decoder)
            if (joiner.isNotEmpty()) add(joiner)
            add(tokens)
            addAll(extraFiles)
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

    val NEMOTRON = ModelInfo(
        id = "nemotron-3.5-560ms-int8",
        displayName = "Nemotron 3.5 Streaming 0.6B",
        shortName = "Nemotron",
        tagline = "Multilingual live captions · CPU, 560 ms",
        kind = EngineKind.NEMOTRON,
        languages = setOf("nl", "en", "zh"),
        archiveBytes = 475271763,
        url = REL + "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11.tar.bz2",
        approxMB = 475,
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
        sha256 = "c6bf5e0df765f9d5b43bc9e0536d4b4b3e7d40bdf5ecf13e45f134c51c05ae3a",
    )

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
        id = "parakeet-tdt-0.6b-v3-int8", displayName = "Parakeet TDT 0.6B v3",
        shortName = "Parakeet v3", tagline = "Optional endpoint second hypothesis · CPU",
        kind = EngineKind.OFFLINE_PARAKEET, languages = setOf("nl", "en"),
        url = REL + "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        approxMB = 487, archiveBytes = 487170055,
        sha256 = "5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf",
        encoder = "encoder.int8.onnx", decoder = "decoder.int8.onnx", joiner = "joiner.int8.onnx",
    )

    val WHISPER_BASE = ModelInfo(
        id = "whisper-base",
        archiveBytes = 207557382,
        sha256 = "911b2083efd7c0dca2ac3b358b75222660dc09fb716d64fbfc417ba6c99ff3de",
        displayName = "Multilingual · Whisper base",
        shortName = "Multi-base",
        tagline = "~90 languages incl. Turkish — fast, lighter",
        kind = EngineKind.WHISPER,
        languages = setOf("nl", "en", "zh", "fr"),
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
        languages = setOf("nl", "en", "zh", "fr"),
        url = REL + "sherpa-onnx-whisper-small.tar.bz2",
        approxMB = 609,
        encoder = "small-encoder.int8.onnx",
        decoder = "small-decoder.int8.onnx",
        tokens = "small-tokens.txt",
        encoderMinBytes = 90_000_000L,  // ~112 MB expected
        decoderMinBytes = 200_000_000L, // ~262 MB expected
    )

    // Order = recommended first within each tier; small (better) listed before base.
    val ALL = listOf(NEMOTRON, WHISPER_BASE)
    val DEFAULT = NEMOTRON

    fun byId(id: String?): ModelInfo? = (ALL + PARAKEET).firstOrNull { it.id == id }
}
