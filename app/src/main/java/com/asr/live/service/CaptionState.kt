package com.asr.live.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide bridge between [CaptionService] (writer) and the UI (reader).
 * Plain singleton so the UI keeps observing transcripts even across config changes.
 */
object CaptionState {

    /** A finalized caption. [original] holds the pre-translation text when translating. */
    data class Line(val id: Long, val text: String, val original: String? = null, val revision: Int = 0, val state: String = "final")

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Transient status (e.g. "Downloading translation model…"); null when idle. */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private var counter = 0L

    @Synchronized
    fun appendFinal(text: String, original: String? = null) {
        val t = text.trim()
        if (t.isEmpty()) return
        _lines.value = (_lines.value + Line(counter++, t, original?.trim()?.ifEmpty { null }))
            .takeLast(MAX_LINES)
        _partial.value = ""
    }

    @Synchronized
    fun appendSource(text: String): Long {
        val id = counter++
        _lines.value = (_lines.value + Line(id, "Translating…", text.trim(), state = "provisional"))
            .takeLast(MAX_LINES)
        _partial.value = ""
        return id
    }

    @Synchronized
    fun applyTranslation(id: Long, revision: Int, translated: String) {
        _lines.value = _lines.value.map { line ->
            if (line.id == id && revision >= line.revision) line.copy(
                text = translated.trim(), revision = revision, state = "final"
            ) else line
        }
    }

    fun setPartial(text: String) { _partial.value = text.trim() }
    fun setRunning(running: Boolean) { _running.value = running }
    fun setError(message: String?) { _error.value = message }
    fun setStatus(message: String?) { _status.value = message }

    fun clear() {
        _lines.value = emptyList()
        _partial.value = ""
    }

    private const val MAX_LINES = 500
}
