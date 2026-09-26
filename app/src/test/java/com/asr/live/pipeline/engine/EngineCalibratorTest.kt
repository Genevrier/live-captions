package com.asr.live.pipeline.engine

import com.asr.live.i18n.LocalTranslator
import com.asr.live.pipeline.Profile
import com.asr.live.pipeline.TranslationQuality
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Exercises the full Stage A/B + selection + persistence orchestration deterministically with
 * fake translators, per the "fake engines for orchestration tests" requirement. This proves the
 * wiring is correct; it does not and cannot prove real engine quality without the device.
 */
@RunWith(RobolectricTestRunner::class)
class EngineCalibratorTest {
    /**
     * A fake dialled to a chosen chrF outcome without depending on real chrF arithmetic being
     * "high enough" for some plausible-looking sentence: a "good" fake echoes the exact
     * held-out reference for its source text (chrF = 100 by construction, per [ChrFTest]'s
     * identity case), and a "bad" fake returns an all-consonant, letter-only string designed to
     * share almost no n-gram overlap with ordinary prose in any of the three target languages.
     */
    private fun fakeFactory(qualityByEngine: Map<String, Double>) = { candidate: EngineCandidate, profile: Profile, _: Boolean ->
        val goodEnough = (qualityByEngine[candidate.engineId] ?: 0.0) >= 50.0
        val referenceBySource = BenchmarkCorpus.heldOut(profile).associate { it.source to it.reference }
        object : LocalTranslator {
            override fun translate(text: String): String =
                if (goodEnough) referenceBySource[text] ?: "zjxqvwkzjxqvwk qzvjxkwq zvjxkqw"
                else "zjxqvwkzjxqvwk qzvjxkwq zvjxkqw"
            override val backend get() = "CPU"
            override fun close() {}
        } as LocalTranslator
    }

    @Test fun theHighestScoringFakeIsSelectedAndPersistedForTheProfile() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = CalibrationStore(ctx)
        val factory = fakeFactory(mapOf("hy-mt2-hy_q8" to 90.0))
        val outcome = EngineCalibrator.calibrate(ctx, Profile.DUTCH_ENGLISH, requestedOpenCl = false,
            store = store, factory = factory)

        assertEquals("hy-mt2-hy_q8", outcome.winner?.engineId)
        val saved = store.getFresh(Profile.DUTCH_ENGLISH)
        assertNotNull("the winner must be persisted immediately", saved)
        assertEquals(outcome.winner?.engineId, saved?.engineId)
    }

    @Test fun everyEligibleCandidateAppearsInTheReportWithNoSilentOmission() {
        val ctx = RuntimeEnvironment.getApplication()
        val factory = fakeFactory(emptyMap())
        val outcome = EngineCalibrator.calibrate(ctx, Profile.DUTCH_ENGLISH, requestedOpenCl = false, factory = factory)

        val eligible = EngineRegistry.forProfile(Profile.DUTCH_ENGLISH, includeExtendedTier = true)
        val reportedIds = outcome.reports.map { it.candidate.engineId }.toSet()
        eligible.forEach { assertTrue("${it.engineId} must appear in the report", it.engineId in reportedIds) }
    }

    @Test fun extendedTierIsSkippedByDefaultButReportedAsNotTested() {
        val ctx = RuntimeEnvironment.getApplication()
        val factory = fakeFactory(emptyMap())
        val outcome = EngineCalibrator.calibrate(ctx, Profile.DUTCH_ENGLISH, requestedOpenCl = false, factory = factory)

        val extended = outcome.reports.filter { it.candidate.extendedTier }
        assertTrue(extended.isNotEmpty())
        extended.forEach { assertEquals(SelectionStatus.NOT_TESTED, it.status) }
    }

    @Test fun includingTheExtendedTierActuallyTestsTheSevenBModel() {
        val ctx = RuntimeEnvironment.getApplication()
        val factory = fakeFactory(mapOf("hy-mt2-hy_7b_q4" to 90.0, "hy-mt2-hy_q4" to 5.0,
            "hy-mt2-hy_q6" to 5.0, "hy-mt2-hy_q8" to 5.0, "ml-kit" to 5.0, "opus-nl-en" to 5.0))
        val outcome = EngineCalibrator.calibrate(ctx, Profile.DUTCH_ENGLISH, requestedOpenCl = false,
            includeExtendedTier = true, factory = factory)
        assertEquals(TranslationQuality.HY_7B_Q4, outcome.winner?.quality)
        assertTrue(outcome.reports.none { it.candidate.extendedTier && it.status == SelectionStatus.NOT_TESTED })
    }

    @Test fun aCrashingFactoryProducesAFailedReportInsteadOfPropagatingTheException() {
        val ctx = RuntimeEnvironment.getApplication()
        val factory = { _: EngineCandidate, _: Profile, _: Boolean -> error("simulated native load crash") }
        val outcome = EngineCalibrator.calibrate(ctx, Profile.DUTCH_ENGLISH, requestedOpenCl = false, factory = factory)
        assertNull(outcome.winner)
        assertTrue(outcome.reports.any { it.status == SelectionStatus.FAILED })
    }

    @Test fun aPreviousFreshSelectionIsKeptWhenNothingNewQualifies() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = CalibrationStore(ctx)
        store.save(CalibrationRecord(Profile.DUTCH_ENGLISH, "hy-mt2-hy_q8", TranslationQuality.HY_Q8, 85.0,
            DeviceFingerprint.current(), EngineSelectionPolicy.POLICY_VERSION, BenchmarkCorpus.VERSION, 1L))
        val factory = fakeFactory(emptyMap()) // every candidate produces gibberish this round
        val outcome = EngineCalibrator.calibrate(ctx, Profile.DUTCH_ENGLISH, requestedOpenCl = false,
            store = store, factory = factory)
        assertEquals("hy-mt2-hy_q8", outcome.winner?.engineId)
        assertTrue(outcome.reason.contains("keeping the previous configuration"))
    }
}
