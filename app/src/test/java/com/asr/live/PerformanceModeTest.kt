package com.asr.live

import com.asr.live.pipeline.PerformanceMode
import com.asr.live.pipeline.Profile
import com.asr.live.pipeline.SessionConfig
import com.asr.live.pipeline.TranslationQuality
import com.asr.live.pipeline.opusBenchmarkEnabled
import com.asr.live.pipeline.withMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceModeTest {
    @Test fun magicV5DefaultsToMaxQualityOnlyOnMatchingHardware() {
        assertEquals(PerformanceMode.MAX_QUALITY, PerformanceMode.defaultFor("SM8750", "HONOR", "Magic V5"))
        assertEquals(PerformanceMode.MAX_QUALITY, PerformanceMode.defaultFor(null, "HONOR", "HONOR MBH-N49"))
        assertEquals(PerformanceMode.BALANCED, PerformanceMode.defaultFor("SM8650", "HONOR", "Magic V5"))
        assertEquals(PerformanceMode.BALANCED, PerformanceMode.defaultFor("SM8750", "other", "Magic V5"))
    }

    @Test fun maxQualityEnablesSupportedEndpointCorrectionWithoutImplyingAcceleratorAvailability() {
        val max = SessionConfig().withMode(PerformanceMode.MAX_QUALITY)
        assertEquals("nemotron-3.5-560ms-int8", max.modelId)
        assertEquals(TranslationQuality.HY_7B_Q4, max.quality)
        assertFalse(max.qnn)
        assertFalse(max.gpuTranslation)
        assertTrue(max.correction)
        val chinese = SessionConfig(profile = Profile.CHINESE_ENGLISH).withMode(PerformanceMode.MAX_QUALITY)
        assertEquals("qwen3-asr-0.6b-int8", chinese.modelId)
        assertFalse(chinese.correction)
    }

    @Test fun fastModeUsesTheSmallerHyModelAndOnlyItsAdvertisedOpusComparison() {
        assertFalse(SessionConfig().withMode(PerformanceMode.MAX_QUALITY).opusBenchmarkEnabled)
        assertFalse(SessionConfig().withMode(PerformanceMode.BALANCED).opusBenchmarkEnabled)
        val fast = SessionConfig().withMode(PerformanceMode.FAST)
        assertEquals(TranslationQuality.HY_Q4, fast.quality)
        assertTrue(fast.opusBenchmarkEnabled)
        assertFalse(SessionConfig(profile = Profile.CHINESE_ENGLISH).withMode(PerformanceMode.FAST).opusBenchmarkEnabled)
        assertFalse(SessionConfig().withMode(PerformanceMode.FAST)
            .copy(quality = TranslationQuality.ML_KIT).opusBenchmarkEnabled)
    }
}
