package com.asr.live.overlay

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class OverlayPreferences(context: Context) : AutoCloseable {
    private val prefs = context.getSharedPreferences("floating_captions", Context.MODE_PRIVATE)
    private fun read() = OverlayOptions(prefs.getBoolean("enabled", false), prefs.getFloat("opacity", 0.65f),
        prefs.getInt("font", 24), prefs.getInt("lines", 3), prefs.getBoolean("source", false),
        prefs.getBoolean("through", false)).normalized()
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> mutable.value = read() }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }
    fun update(options: OverlayOptions) {
        val o = options.normalized()
        prefs.edit().putBoolean("enabled", o.enabled).putFloat("opacity", o.opacity)
            .putInt("font", o.fontSp).putInt("lines", o.lines).putBoolean("source", o.source)
            .putBoolean("through", o.touchThrough).apply()
    }
    fun position(expanded: Boolean): Pair<Float, Float> {
        val prefix = if (expanded) "inner" else "outer"
        return prefs.getFloat("$prefix.x", 0.5f) to prefs.getFloat("$prefix.y", 0.75f)
    }
    fun savePosition(expanded: Boolean, x: Float, y: Float) {
        val prefix = if (expanded) "inner" else "outer"
        prefs.edit().putFloat("$prefix.x", x).putFloat("$prefix.y", y).apply()
    }
    override fun close() = prefs.unregisterOnSharedPreferenceChangeListener(listener)
}
