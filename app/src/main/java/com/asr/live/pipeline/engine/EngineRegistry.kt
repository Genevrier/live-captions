package com.asr.live.pipeline.engine

import com.asr.live.pipeline.Profile
import com.asr.live.pipeline.TranslationQuality

/** How a candidate's execution backend is actually verified, not merely requested. */
enum class Backend { CPU, OPENCL, SDK_MANAGED }

/** Where a candidate stands before any on-device trial runs. */
enum class QualificationTier {
    /** Ready to enter Stage A compatibility/smoke testing. */
    CANDIDATE,
    /**
     * Not integrated in this build. Selection must never choose it; it exists in the registry
     * purely so it is reported rather than silently omitted (per the "do not silently omit
     * failed candidates" requirement).
     */
    BLOCKED,
}

/**
 * One selectable translation engine configuration.
 *
 * This is deliberately thin: model files, checksums and download URLs already live in
 * `translation-models.json` / [com.asr.live.model.TranslationModels] and are not duplicated
 * here. A registry entry only adds what that manifest cannot express — language coverage,
 * runtime/backend claims, and whether the candidate is actually wired up in this build.
 */
data class EngineCandidate(
    val engineId: String,
    val family: EngineFamily,
    val displayName: String,
    val modelSizeLabel: String,
    /** Non-null for HY_MT2 and ML_KIT rows; null for OPUS, which has no [TranslationQuality] entry. */
    val quality: TranslationQuality?,
    val supportedProfiles: Set<Profile>,
    val format: String,
    val requiredRuntime: String,
    val supportedBackends: Set<Backend>,
    val licenseNote: String,
    val status: QualificationTier,
    /** Hy-MT2 7B: a larger, slower tier the optimizer only enters under "Full calibration". */
    val extendedTier: Boolean = false,
    /** Set only for [QualificationTier.BLOCKED] rows: the exact, checkable reason. */
    val blockerReason: String? = null,
) {
    init {
        require((status == QualificationTier.BLOCKED) == (blockerReason != null)) {
            "Blocked candidates must carry a blocker reason; others must not"
        }
    }

    fun supports(profile: Profile) = profile in supportedProfiles
}

enum class EngineFamily { ML_KIT, OPUS, HY_MT2, TRANSLATE_GEMMA, GEMMA_LITE_RT, HY_MT2_ULTRA_LOW_BIT }

/**
 * The curated candidate set for this build.
 *
 * Entries E/F/G from the original request (TranslateGemma, Gemma via LiteRT-LM, ultra-low-bit
 * Hy-MT2) are listed as [QualificationTier.BLOCKED] with the specific integration gap, per the
 * instruction to report exact blockers rather than omit or stub unverified candidates:
 *
 *  - TranslateGemma: no verified, licensed, Android-ARM64-deployable checkpoint has been
 *    confirmed from a primary source for this build; integrating it would mean guessing at a
 *    prompt template and a runtime this repo does not carry, which is exactly what was ruled out.
 *  - Gemma via LiteRT-LM: this build has no LiteRT-LM runtime integrated (only llama.cpp and
 *    ONNX Runtime are linked into `live-translator`/`live-asr`). Adding one is a new native
 *    dependency, not a model download, and is out of scope for this phase.
 *  - Hy-MT2 ultra-low-bit (e.g. ternary/1.25-2 bit): these are reported as specialized formats,
 *    not GGUF quantizations llama.cpp's existing k-quant kernels can load; no verified GGUF
 *    conversion compatible with the bundled llama.cpp revision has been confirmed.
 */
object EngineRegistry {
    val all: List<EngineCandidate> = listOf(
        EngineCandidate(
            engineId = "ml-kit", family = EngineFamily.ML_KIT,
            displayName = "ML Kit on-device translation", modelSizeLabel = "~30 MB per direction",
            quality = TranslationQuality.ML_KIT,
            supportedProfiles = setOf(Profile.DUTCH_ENGLISH, Profile.CHINESE_ENGLISH, Profile.ENGLISH_FRENCH),
            format = "SDK-managed", requiredRuntime = "ML Kit Translate SDK (Google Play services)",
            supportedBackends = setOf(Backend.SDK_MANAGED),
            licenseNote = "Google ML Kit terms of service; language models fetched via Play services",
            status = QualificationTier.CANDIDATE,
        ),
        EngineCandidate(
            engineId = "opus-nl-en", family = EngineFamily.OPUS,
            displayName = "OPUS-MT (Dutch to English)", modelSizeLabel = "~75 MB",
            quality = null, supportedProfiles = setOf(Profile.DUTCH_ENGLISH),
            format = "ONNX (encoder/decoder) + SentencePiece", requiredRuntime = "ONNX Runtime (bundled)",
            supportedBackends = setOf(Backend.CPU),
            licenseNote = "Helsinki-NLP OPUS-MT, CC-BY 4.0",
            status = QualificationTier.CANDIDATE,
        ),
        EngineCandidate(
            engineId = "opus-en-fr", family = EngineFamily.OPUS,
            displayName = "OPUS-MT (English to French)", modelSizeLabel = "~75 MB",
            quality = null, supportedProfiles = setOf(Profile.ENGLISH_FRENCH),
            format = "ONNX (encoder/decoder) + SentencePiece", requiredRuntime = "ONNX Runtime (bundled)",
            supportedBackends = setOf(Backend.CPU),
            licenseNote = "Helsinki-NLP OPUS-MT, CC-BY 4.0",
            status = QualificationTier.CANDIDATE,
        ),
        hyMt2(TranslationQuality.HY_Q4, "1.8B Q4_K_M"),
        hyMt2(TranslationQuality.HY_Q6, "1.8B Q6_K"),
        hyMt2(TranslationQuality.HY_Q8, "1.8B Q8_0"),
        hyMt2(TranslationQuality.HY_7B_Q4, "7B Q4_K_M", extendedTier = true),
        EngineCandidate(
            engineId = "translate-gemma-4b", family = EngineFamily.TRANSLATE_GEMMA,
            displayName = "TranslateGemma 4B", modelSizeLabel = "unverified",
            quality = null, supportedProfiles = emptySet(),
            format = "unverified", requiredRuntime = "unverified", supportedBackends = emptySet(),
            licenseNote = "unverified", status = QualificationTier.BLOCKED,
            blockerReason = "No verified, licensed, Android-ARM64-deployable checkpoint and " +
                "documented translation template confirmed from a primary source for this build.",
        ),
        EngineCandidate(
            engineId = "gemma-4-e2b-litert", family = EngineFamily.GEMMA_LITE_RT,
            displayName = "Gemma 4 E2B (LiteRT-LM)", modelSizeLabel = "unverified",
            quality = null, supportedProfiles = emptySet(),
            format = "unverified", requiredRuntime = "LiteRT-LM (not integrated)", supportedBackends = emptySet(),
            licenseNote = "unverified", status = QualificationTier.BLOCKED,
            blockerReason = "This build links llama.cpp and ONNX Runtime only; no LiteRT-LM " +
                "runtime is integrated. Adding one is a new native dependency, not a model download.",
        ),
        EngineCandidate(
            engineId = "hy-mt2-ultra-low-bit", family = EngineFamily.HY_MT2_ULTRA_LOW_BIT,
            displayName = "Hy-MT2 1.8B ultra-low-bit", modelSizeLabel = "unverified",
            quality = null, supportedProfiles = emptySet(),
            format = "unverified (reported as a specialized ternary/sub-4-bit format, not a " +
                "standard GGUF k-quant)", requiredRuntime = "unverified", supportedBackends = emptySet(),
            licenseNote = "unverified", status = QualificationTier.BLOCKED,
            blockerReason = "No GGUF conversion of this format verified compatible with the " +
                "bundled llama.cpp revision's k-quant kernels has been confirmed from a primary source.",
        ),
    )

    private fun hyMt2(quality: TranslationQuality, sizeLabel: String, extendedTier: Boolean = false) = EngineCandidate(
        engineId = "hy-mt2-${quality.name.lowercase()}", family = EngineFamily.HY_MT2,
        displayName = quality.label, modelSizeLabel = sizeLabel, quality = quality,
        supportedProfiles = setOf(Profile.DUTCH_ENGLISH, Profile.CHINESE_ENGLISH, Profile.ENGLISH_FRENCH),
        format = "GGUF", requiredRuntime = "llama.cpp (bundled)",
        supportedBackends = setOf(Backend.CPU, Backend.OPENCL),
        licenseNote = "See model card bundled with the pinned checkpoint",
        status = QualificationTier.CANDIDATE, extendedTier = extendedTier,
    )

    fun forProfile(profile: Profile, includeExtendedTier: Boolean = false): List<EngineCandidate> =
        all.filter { it.status == QualificationTier.CANDIDATE && it.supports(profile) &&
            (includeExtendedTier || !it.extendedTier) }

    fun blocked(): List<EngineCandidate> = all.filter { it.status == QualificationTier.BLOCKED }
}
