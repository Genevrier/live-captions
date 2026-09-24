package com.asr.live.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.asr.live.i18n.Languages
import com.asr.live.model.ModelCatalog
import com.asr.live.model.ModelInfo
import com.asr.live.model.ModelRepository
import com.asr.live.model.ModelStore
import com.asr.live.service.CaptionService
import com.asr.live.service.CaptionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CaptionViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("asr", Context.MODE_PRIVATE)

    val models = ModelCatalog.ALL

    private val _selected = MutableStateFlow(
        prefs.getString(KEY_ENGINE, ModelCatalog.DEFAULT.id) ?: ModelCatalog.DEFAULT.id
    )
    val selected: StateFlow<String> = _selected.asStateFlow()

    /** Whisper source-language hint. */
    private val _spoken = MutableStateFlow(prefs.getString(KEY_SPOKEN, "nl") ?: "nl")
    val spoken: StateFlow<String> = _spoken.asStateFlow()

    /** Translation target, or [Languages.OFF]. */
    private val _target = MutableStateFlow(prefs.getString(KEY_TARGET, "en") ?: "en")
    val target: StateFlow<String> = _target.asStateFlow()

    private val _present = MutableStateFlow(presentIds())
    val present: StateFlow<Set<String>> = _present.asStateFlow()

    // Re-exported recognizer/download state.
    val running = CaptionState.running
    val lines = CaptionState.lines
    val partial = CaptionState.partial
    val error = CaptionState.error
    val status = CaptionState.status
    val download = ModelRepository.state

    fun refreshPresence() { _present.value = presentIds() }

    fun selectedInfo(): ModelInfo = ModelCatalog.byId(_selected.value) ?: ModelCatalog.DEFAULT

    fun select(id: String) {
        if (running.value) return // don't switch engines mid-session
        _selected.value = id
        prefs.edit().putString(KEY_ENGINE, id).apply()
    }

    fun setSpoken(code: String) {
        if (running.value) return
        if (code != "en" && !selectedInfo().isMultilingual) {
            select(ModelCatalog.WHISPER_SMALL.id)
        }
        _spoken.value = code
        prefs.edit().putString(KEY_SPOKEN, code).apply()
    }

    fun setTarget(code: String) {
        if (running.value) return
        _target.value = code
        prefs.edit().putString(KEY_TARGET, code).apply()
    }

    /** Swap spoken language and translation target (only meaningful when both are languages). */
    fun swapLanguages() {
        if (running.value) return
        if (!selectedInfo().isMultilingual || _target.value == Languages.OFF) return
        val s = _spoken.value
        val t = _target.value
        _spoken.value = t
        _target.value = s
        prefs.edit().putString(KEY_SPOKEN, t).putString(KEY_TARGET, s).apply()
    }

    fun download(id: String) {
        val info = ModelCatalog.byId(id) ?: return
        viewModelScope.launch {
            ModelRepository.download(getApplication(), info)
            refreshPresence()
        }
    }

    fun toggle() {
        val ctx = getApplication<Application>()
        if (running.value) {
            CaptionService.stop(ctx)
        } else {
            CaptionService.start(ctx, _selected.value, _spoken.value, _target.value)
        }
    }

    fun clear() = CaptionState.clear()
    fun dismissError() = CaptionState.setError(null)

    private fun presentIds(): Set<String> =
        models.filter { ModelStore.isPresent(getApplication(), it) }.map { it.id }.toSet()

    private companion object {
        const val KEY_ENGINE = "engine"
        const val KEY_SPOKEN = "spoken"
        const val KEY_TARGET = "target"
    }
}
