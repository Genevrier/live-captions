package com.asr.live

import android.content.Context
import android.os.Looper
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.asr.live.overlay.*
import com.asr.live.pipeline.*
import com.asr.live.service.CaptionState
import com.asr.live.service.ListeningState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h800dp")
class FloatingCaptionsTest {
    private lateinit var context: Context
    private lateinit var prefs: OverlayPreferences
    private lateinit var overlay: FloatingCaptions
    private val caption = Caption(SegmentKey(1, 1, 0), "Goedemorgen", translation = "Good morning", stage = CaptionStage.FINAL)
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("floating_captions", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = OverlayPreferences(context)
        overlay = FloatingCaptions(context, prefs)
        ShadowSettings.setCanDrawOverlays(true)
        CaptionState.setError(null)
    }
    @After fun tearDown() { overlay.close(); prefs.close() }
    private fun views() = Shadow.extract<ShadowWindowManagerImpl>(context.getSystemService(WindowManager::class.java)).views
    private fun render(state: ListeningState = ListeningState.LISTENING) {
        shadowOf(Looper.getMainLooper()).idle()
        overlay.render(listOf(caption), state, "English")
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun permissionDenialAndRevocationLeaveNoWindow() {
        prefs.update(OverlayOptions(enabled = true))
        ShadowSettings.setCanDrawOverlays(false); render()
        assertTrue(views().isEmpty())
        ShadowSettings.setCanDrawOverlays(true); render()
        assertEquals(CaptionState.error.value, 1, views().size)
        ShadowSettings.setCanDrawOverlays(false); render()
        assertTrue(views().isEmpty())
    }
    @Test fun realWindowUsesOverlayTypeAndSafeTouchThroughFlags() {
        prefs.update(OverlayOptions(enabled = true, touchThrough = true, lines = 4, source = true))
        render()
        assertEquals(CaptionState.error.value, 1, views().size)
        val view = views().single() as LinearLayout
        val params = view.layoutParams as WindowManager.LayoutParams
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(params.alpha <= 0.8f)
        assertEquals("Good morning", (view.getChildAt(1) as TextView).text.toString())
        assertEquals(4, (view.getChildAt(1) as TextView).maxLines)
        assertEquals("Goedemorgen", (view.getChildAt(2) as TextView).text.toString())
        render(ListeningState.STOPPING)
        assertTrue(views().isEmpty())
        render()
        assertEquals(1, views().size)
        overlay.close()
        assertTrue(views().isEmpty())
    }
    @Test fun displayResizeRecreatesOneClampedWindowAndSettingsPersist() {
        prefs.update(OverlayOptions(enabled = true, fontSp = 30))
        prefs.savePosition(false, 1f, 1f)
        prefs.savePosition(true, 0f, 0f)
        render()
        assertEquals(CaptionState.error.value, 1, views().size)
        ShadowDisplayManager.changeDisplay(0, "w840dp-h800dp")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CaptionState.error.value, 1, views().size)
        val params = views().single().layoutParams as WindowManager.LayoutParams
        assertTrue(params.x >= 0 && params.y >= 0)
        OverlayPreferences(context).use { restored ->
            assertEquals(30, restored.state.value.fontSp)
            assertEquals(1f to 1f, restored.position(false))
            assertEquals(0f to 0f, restored.position(true))
        }
    }
}
