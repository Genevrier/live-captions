package com.asr.live.overlay

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import com.asr.live.pipeline.Caption
import com.asr.live.pipeline.CaptionStage
import com.asr.live.service.CaptionState
import com.asr.live.service.ListeningState

/** Main-thread-only window owner. One window; never an Accessibility Service. */
class FloatingCaptions(private val context: Context, private val prefs: OverlayPreferences) : AutoCloseable {
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var window: WindowManager? = null
    private var root: LinearLayout? = null
    private var target: TextView? = null
    private var source: TextView? = null
    private var label: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var rows: List<Caption> = emptyList()
    private var lifecycle = ListeningState.STOPPED
    private var language = "English"
    private var expanded = false
    private var minX = 0; private var minY = 0
    private var rangeX = 0; private var rangeY = 0
    private var downX = 0f; private var downY = 0f
    private var startX = 0; private var startY = 0
    private var dragging = false
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = refreshDisplay()
        override fun onDisplayRemoved(id: Int) = refreshDisplay()
        override fun onDisplayChanged(id: Int) = refreshDisplay()
    }
    init { displays.registerDisplayListener(displayListener, handler) }
    fun render(captions: List<Caption>, state: ListeningState, targetLanguage: String) {
        rows = captions; lifecycle = state; language = targetLanguage
        val o = prefs.state.value
        if (!o.enabled || !Settings.canDrawOverlays(context) ||
            lifecycle !in setOf(ListeningState.STARTING, ListeningState.LISTENING) ||
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            hide(); return
        }
        try {
            if (root == null) create()
            val view = root ?: return
            val caption = OverlayPolicy.caption(rows)
            target!!.text = caption?.translation ?: "Waiting for translation…"
            target!!.textSize = o.fontSp.toFloat(); target!!.maxLines = o.lines
            target!!.setTypeface(null, if (caption?.stage == CaptionStage.PROVISIONAL) Typeface.ITALIC else Typeface.NORMAL)
            source!!.text = caption?.source.orEmpty()
            source!!.textSize = (o.fontSp * 0.7f).coerceAtLeast(12f)
            source!!.visibility = if (o.source && caption != null) View.VISIBLE else View.GONE
            label!!.text = "$language · ${caption?.stage?.name?.lowercase() ?: "listening"}${if (!o.touchThrough) " · drag to move" else ""}"
            view.background = GradientDrawable().apply {
                cornerRadius = 12 * view.resources.displayMetrics.density
                setColor(Color.argb((o.opacity * 255).toInt(), 0, 0, 0))
            }
            val p = params!!
            p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (o.touchThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
            val maximum = if (Build.VERSION.SDK_INT >= 31)
                context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch else 0.8f
            p.alpha = OverlayPolicy.windowAlpha(o.touchThrough, maximum)
            place()
            if (!view.isAttachedToWindow) window!!.addView(view, p) else window!!.updateViewLayout(view, p)
        } catch (e: RuntimeException) {
            hide()
            prefs.update(o.copy(enabled = false))
            CaptionState.setError("Floating captions unavailable: ${e.message}. In-app captions remain available.")
        }
    }
    private fun create() {
        val display = displays.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val displayContext = context.createDisplayContext(display)
        val windowContext = if (Build.VERSION.SDK_INT >= 30)
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else displayContext
        window = windowContext.getSystemService(WindowManager::class.java)
        fun text(size: Float) = TextView(windowContext).apply {
            setTextColor(Color.WHITE); textSize = size; ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
        }
        root = LinearLayout(windowContext).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            label = text(11f).also { it.maxLines = 1; addView(it) }
            target = text(24f).also { addView(it) }
            source = text(16f).also { it.maxLines = 2; addView(it) }
            setOnTouchListener { view, event ->
                val p = params ?: return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dragging = true; downX = event.rawX; downY = event.rawY; startX = p.x; startY = p.y; true }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = (startX + event.rawX - downX).toInt().coerceIn(minX, minX + rangeX)
                        p.y = (startY + event.rawY - downY).toInt().coerceIn(minY, minY + rangeY)
                        runCatching { window?.updateViewLayout(view, p) }; true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dragging = false
                        prefs.savePosition(expanded, OverlayPolicy.fraction(p.x - minX, rangeX), OverlayPolicy.fraction(p.y - minY, rangeY))
                        view.performClick(); true
                    }
                    else -> false
                }
            }
        }
        params = WindowManager.LayoutParams(1, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; setTitle("Live translation captions") }
    }
    @Suppress("DEPRECATION")
    private fun place() {
        val view = root!!; val p = params!!; val density = view.resources.displayMetrics.density
        val margin = (12 * density).toInt()
        val bounds = if (Build.VERSION.SDK_INT >= 30) window!!.currentWindowMetrics.bounds
            else android.graphics.Rect().also { r -> val point = android.graphics.Point(); window!!.defaultDisplay.getRealSize(point); r.set(0, 0, point.x, point.y) }
        val insets = if (Build.VERSION.SDK_INT >= 30) window!!.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()) else android.graphics.Insets.of(0, (28 * density).toInt(), 0, (28 * density).toInt())
        val width = (bounds.width() - insets.left - insets.right - 2 * margin).coerceAtLeast(1)
        val height = (bounds.height() - insets.top - insets.bottom - 2 * margin).coerceAtLeast(1)
        expanded = bounds.width() / density >= 600
        p.width = minOf((680 * density).toInt(), (width * 0.95f).toInt()).coerceAtLeast(1)
        view.measure(View.MeasureSpec.makeMeasureSpec(p.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        p.height = view.measuredHeight.coerceAtMost(height)
        minX = insets.left + margin; minY = insets.top + margin
        rangeX = (width - p.width).coerceAtLeast(0); rangeY = (height - p.height).coerceAtLeast(0)
        val position = prefs.position(expanded)
        if (!dragging) {
            p.x = minX + OverlayPolicy.position(position.first, rangeX)
            p.y = minY + OverlayPolicy.position(position.second, rangeY)
        }
    }
    private fun refreshDisplay() { hide(); render(rows, lifecycle, language) }
    fun hide() { root?.let { if (it.isAttachedToWindow) runCatching { window?.removeViewImmediate(it) } }; dragging = false; root = null; params = null; target = null; source = null; label = null; window = null }
    override fun close() { displays.unregisterDisplayListener(displayListener); hide() }
}
