package com.asr.live.overlay

import com.asr.live.pipeline.Caption
import com.asr.live.pipeline.CaptionStage
import kotlin.math.roundToInt

data class OverlayOptions(
    val enabled: Boolean = false,
    val opacity: Float = 0.65f,
    val fontSp: Int = 24,
    val lines: Int = 3,
    val source: Boolean = false,
    val touchThrough: Boolean = false,
) {
    fun normalized() = copy(opacity = if (opacity.isFinite()) opacity.coerceIn(0f, 0.9f) else 0.65f,
        fontSp = fontSp.coerceIn(16, 40), lines = lines.coerceIn(1, 4))
}

object OverlayPolicy {
    fun position(fraction: Float, available: Int): Int =
        ((if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0.5f) * available.coerceAtLeast(0)).roundToInt()
    fun fraction(position: Int, available: Int): Float =
        if (available <= 0) 0.5f else (position.toFloat() / available).coerceIn(0f, 1f)
    // Android applies obscuring checks to the window alpha, not the background drawable alpha.
    fun windowAlpha(touchThrough: Boolean, systemLimit: Float): Float =
        if (touchThrough) systemLimit.coerceIn(0f, 0.8f) else 1f
    fun caption(rows: List<Caption>): Caption? = rows.lastOrNull {
        it.translation.isNotBlank() && it.stage !in setOf(CaptionStage.CANCELLED, CaptionStage.SKIPPED)
    }
}
