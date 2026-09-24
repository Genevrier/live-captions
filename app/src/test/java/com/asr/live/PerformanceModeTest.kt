package com.asr.live

import com.asr.live.pipeline.PerformanceMode
import com.asr.live.pipeline.SessionConfig
import com.asr.live.pipeline.TranslationQuality
import com.asr.live.pipeline.withMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PerformanceModeTest {
    @Test fun magicV5DefaultsToMaxQualityOnlyOnMatchingHardware() {
        assertEquals(PerformanceMode.MAX_QUALITY, PerformanceMode.defaultFor("SM8750", "HONOR", "Magic V5"))
        assertEquals(PerformanceMode.BALANCED, PerformanceMode.defaultFor("SM8650", "HONOR", "Magic V5"))
        assertEquals(PerformanceMode.BALANCED, PerformanceMode.defaultFor("SM8750", "other", "Magic V5"))
    }

    @Test fun maxQualityDoesNotClaimUnvalidatedAccelerationOrCorrection() {
        val max = SessionConfig().withMode(PerformanceMode.MAX_QUALITY)
        assertEquals("nemotron-3.5-560ms-int8", max.modelId)
        assertEquals(TranslationQuality.HY_7B_Q6, max.quality)
        assertFalse(max.qnn)
        assertFalse(max.correction)
    }
}
