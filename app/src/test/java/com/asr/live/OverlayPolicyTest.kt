package com.asr.live

import com.asr.live.overlay.*
import com.asr.live.pipeline.*
import org.junit.Assert.*
import org.junit.Test

class OverlayPolicyTest {
    @Test fun touchThroughUsesWindowAlphaBelowAndroidLimit() {
        assertEquals(0.8f, OverlayPolicy.windowAlpha(true, 0.8f), 0f)
        assertEquals(0.6f, OverlayPolicy.windowAlpha(true, 0.6f), 0f)
        assertEquals(0.8f, OverlayPolicy.windowAlpha(true, 1f), 0f)
        assertEquals(1f, OverlayPolicy.windowAlpha(false, 0.8f), 0f)
    }
    @Test fun positionsSurviveFoldAndRotationWithoutGoingOffscreen() {
        val fraction = OverlayPolicy.fraction(600, 800)
        assertEquals(300, OverlayPolicy.position(fraction, 400))
        assertEquals(0, OverlayPolicy.position(-5f, 400))
        assertEquals(400, OverlayPolicy.position(5f, 400))
        assertEquals(0, OverlayPolicy.position(0.5f, -100))
        assertEquals(200, OverlayPolicy.position(Float.NaN, 400))
    }
    @Test fun malformedSettingsAreClamped() {
        val options = OverlayOptions(opacity = Float.NaN, fontSp = 1000, lines = 10).normalized()
        assertEquals(0.65f, options.opacity, 0f)
        assertEquals(40, options.fontSp)
        assertEquals(4, options.lines)
        assertEquals(1, OverlayOptions(lines = 0).normalized().lines)
        assertFalse(OverlayOptions().enabled)
    }
    @Test fun retainTranslationWhileNewestSegmentAwaitsTranslation() {
        val translated = Caption(SegmentKey(1, 1, 1), "bron", translation = "Source", stage = CaptionStage.FINAL)
        val awaiting = Caption(SegmentKey(1, 2, 0), "volgende")
        assertEquals(translated, OverlayPolicy.caption(listOf(translated, awaiting)))
        val revision = translated.copy(key = translated.key.copy(revision = 2), translation = "Corrected", stage = CaptionStage.REVISED)
        assertEquals("Corrected", OverlayPolicy.caption(listOf(revision, awaiting))?.translation)
        assertNull(OverlayPolicy.caption(listOf(translated.copy(stage = CaptionStage.CANCELLED))))
        assertNull(OverlayPolicy.caption(emptyList()))
    }
}
