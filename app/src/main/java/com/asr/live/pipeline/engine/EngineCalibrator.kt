package com.asr.live.pipeline.engine

import android.content.Context
import com.asr.live.i18n.LocalTranslator
import com.asr.live.i18n.MlKitTranslator
import com.asr.live.i18n.NativeTranslator
import com.asr.live.model.TranslationModels
import com.asr.live.pipeline.Profile
import java.io.File

/**
 * Builds a real, production [LocalTranslator] for one registry candidate. This calls the exact
 * same constructors [com.asr.live.service.CaptionSession] uses — it is not a simplified stand-in
 * adapter, so a candidate that survives calibration through this factory is proven to work
 * through the real integration path, not a mock of it.
 */
object EngineCandidateFactory {
    fun create(ctx: Context, candidate: EngineCandidate, profile: Profile, preferOpenCl: Boolean): LocalTranslator =
        when (candidate.family) {
            EngineFamily.ML_KIT -> MlKitTranslator(profile.source, profile.target).also { it.prepare() }
            EngineFamily.OPUS -> {
                val bundle = checkNotNull(profile.fastBundle) { "${profile.label} has no OPUS bundle" }
                val directory = TranslationModels.verify(ctx, bundle)
                NativeTranslator(directory, 2, true, profile, "", false,
                    ctx.getDir("llama-opencl-cache", Context.MODE_PRIVATE))
            }
            EngineFamily.HY_MT2 -> {
                val quality = checkNotNull(candidate.quality) { "${candidate.engineId} has no TranslationQuality" }
                val bundle = checkNotNull(quality.bundleId) { "${candidate.engineId} has no GGUF bundle" }
                val directory = TranslationModels.verify(ctx, bundle)
                NativeTranslator(File(directory, "model.gguf"), 4, false, profile, "", preferOpenCl,
                    ctx.getDir("llama-opencl-cache", Context.MODE_PRIVATE))
            }
            EngineFamily.TRANSLATE_GEMMA, EngineFamily.GEMMA_LITE_RT, EngineFamily.HY_MT2_ULTRA_LOW_BIT ->
                error("${candidate.engineId} is not integrated in this build: ${candidate.blockerReason}")
        }
}

/**
 * Runs Stage A (compatibility/smoke) and Stage B (source-text quality screening) for every
 * eligible registry candidate on one language pair, applies [EngineSelectionPolicy], and
 * persists the result via [CalibrationStore] so it takes effect immediately in this build.
 *
 * This is real orchestration over the real adapters; what it cannot do without the target
 * device is prove real-world latency (Stage C/D are out of scope for this phase) or that the
 * result generalizes beyond the small corpus in [BenchmarkCorpus].
 */
object EngineCalibrator {
    /**
     * [factory] defaults to the real [EngineCandidateFactory], which loads real native/SDK
     * translators. Tests inject a fake factory to exercise this orchestration deterministically
     * without a device; production callers should never override it.
     */
    fun calibrate(ctx: Context, profile: Profile, requestedOpenCl: Boolean,
                  includeExtendedTier: Boolean = false, store: CalibrationStore = CalibrationStore(ctx),
                  factory: (EngineCandidate, Profile, Boolean) -> LocalTranslator =
                      { candidate, p, openCl -> EngineCandidateFactory.create(ctx, candidate, p, openCl) }): SelectionOutcome {
        val toTest = EngineRegistry.forProfile(profile, includeExtendedTier)
        val skippedExtended = EngineRegistry.forProfile(profile, includeExtendedTier = true) - toTest.toSet()
        val corpus = BenchmarkCorpus.heldOut(profile)

        val evaluations = toTest.map { candidate -> evaluateCandidate(candidate, profile, requestedOpenCl, corpus, factory) }
        val previousWinner = store.getFresh(profile)?.let { record ->
            EngineRegistry.all.firstOrNull { it.engineId == record.engineId } }

        val outcome = EngineSelectionPolicy.select(profile, evaluations, previousWinner)
        val fullReports = outcome.reports + skippedExtended.map {
            CandidateReport(it, SelectionStatus.NOT_TESTED, "extended tier; run full calibration to include it") }
        val result = outcome.copy(reports = fullReports)

        result.winner?.let { winner ->
            val winnerEvaluation = evaluations.first { it.candidate == winner }
            store.save(CalibrationRecord(profile, winner.engineId, winner.quality, winnerEvaluation.chrfScore,
                DeviceFingerprint.current(), EngineSelectionPolicy.POLICY_VERSION, BenchmarkCorpus.VERSION,
                System.currentTimeMillis()))
        }
        return result
    }

    private fun evaluateCandidate(candidate: EngineCandidate, profile: Profile, requestedOpenCl: Boolean,
                                   corpus: List<CorpusItem>, factory: (EngineCandidate, Profile, Boolean) -> LocalTranslator): CandidateEvaluation {
        val smoke = EngineSmokeGate.run(profile, requestedOpenCl) { factory(candidate, profile, requestedOpenCl) }
        if (smoke !is SmokeOutcome.Qualified) return CandidateEvaluation(candidate, smoke)

        // Stage A already closed its own instance; Stage B gets a fresh one so a leaked
        // resource in one stage cannot mask or corrupt the other's result.
        val translator = try { factory(candidate, profile, requestedOpenCl) }
        catch (t: Throwable) { return CandidateEvaluation(candidate, SmokeOutcome.Failed("Stage B load failed: ${t.message}")) }
        try {
            var totalScore = 0.0
            var scored = 0
            var negationDropped = 0
            var numbersMissing = 0
            for (item in corpus) {
                val output = try { translator.translate(item.source) } catch (_: Throwable) { continue }
                totalScore += ChrFScorer.score(output, item.reference)
                scored++
                if (SemanticChecks.negationMarkerDropped(output, item.reference, profile.target)) negationDropped++
                numbersMissing += SemanticChecks.numberDigitsMissing(output, item.reference, profile.target).size
            }
            val mean = if (scored > 0) totalScore / scored else null
            return CandidateEvaluation(candidate, smoke, mean, scored, negationDropped, numbersMissing)
        } finally { runCatching { translator.close() } }
    }
}
