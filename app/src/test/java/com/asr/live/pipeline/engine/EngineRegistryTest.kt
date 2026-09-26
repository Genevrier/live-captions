package com.asr.live.pipeline.engine

import com.asr.live.pipeline.Profile
import com.asr.live.pipeline.TranslationQuality
import org.junit.Assert.*
import org.junit.Test

class EngineRegistryTest {
    @Test fun everyBlockedCandidateCarriesAnExactReasonAndNoLanguageCoverage() {
        val blocked = EngineRegistry.blocked()
        assertTrue("E/F/G research candidates must be present but blocked", blocked.isNotEmpty())
        blocked.forEach {
            assertFalse("a blocked candidate must never claim language support",
                it.supportedProfiles.isNotEmpty())
            assertNotNull(it.blockerReason)
            assertTrue("blocker reason must be a real explanation, not a placeholder",
                it.blockerReason!!.length > 20)
        }
    }

    @Test fun chineseEnglishHasNoOpusCandidateBecauseNoOpusBundleExists() {
        assertTrue(Profile.CHINESE_ENGLISH.fastBundle == null)
        val opusForChinese = EngineRegistry.all.filter { it.family == EngineFamily.OPUS && it.supports(Profile.CHINESE_ENGLISH) }
        assertTrue(opusForChinese.isEmpty())
    }

    @Test fun everyCandidateQualityMatchesATranslationQualityBundleWhenPresent() {
        EngineRegistry.all.filter { it.status == QualificationTier.CANDIDATE && it.family == EngineFamily.HY_MT2 }
            .forEach { candidate ->
                val quality = checkNotNull(candidate.quality)
                assertNotNull("${candidate.engineId} must reference a real GGUF bundle", quality.bundleId)
            }
    }

    @Test fun forProfileExcludesTheExtendedTierByDefault() {
        val standard = EngineRegistry.forProfile(Profile.DUTCH_ENGLISH)
        val extended = EngineRegistry.forProfile(Profile.DUTCH_ENGLISH, includeExtendedTier = true)
        assertFalse(standard.any { it.extendedTier })
        assertTrue("full calibration must add the 7B tier", extended.any { it.extendedTier })
        assertTrue(extended.size > standard.size)
    }

    @Test fun forProfileNeverReturnsABlockedCandidate() {
        Profile.entries.forEach { profile ->
            assertTrue(EngineRegistry.forProfile(profile, includeExtendedTier = true)
                .none { it.status == QualificationTier.BLOCKED })
        }
    }

    @Test fun mlKitCoversAllThreeConfiguredLanguageDirections() {
        val mlKit = EngineRegistry.all.first { it.engineId == "ml-kit" }
        Profile.entries.forEach { assertTrue("${it.label} should be ML Kit-eligible", mlKit.supports(it)) }
    }
}
