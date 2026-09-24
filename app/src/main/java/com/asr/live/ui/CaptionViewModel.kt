package com.asr.live.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.asr.live.model.*
import com.asr.live.pipeline.*
import com.asr.live.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CaptionViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("profiles_v2", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(SessionConfig(
        profile = Profile.fromId(prefs.getString("profile", null)),
        modelId = prefs.getString("model", ModelCatalog.DEFAULT.id) ?: ModelCatalog.DEFAULT.id,
        threads = prefs.getInt("threads", 6).coerceIn(1, 8),
    ))
    val config = _config.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    val lines = CaptionState.lines
    val lifecycle = CaptionState.lifecycle
    val error = CaptionState.error
    val metrics = CaptionState.metrics
    val download = ModelRepository.state
    init { refreshPresence() }
    fun model() = ModelCatalog.byId(_config.value.modelId) ?: ModelCatalog.DEFAULT
    fun models() = ModelCatalog.ALL.filter { it.supports(_config.value.profile.source) }
    fun update(config: SessionConfig) {
        if (_busy.value || CaptionState.running.value) return
        val info = ModelCatalog.byId(config.modelId)
        val adjusted = if (info?.supports(config.profile.source) == true) config else config.copy(modelId = ModelCatalog.DEFAULT.id)
        _config.value = adjusted
        prefs.edit().putString("profile", adjusted.profile.name).putString("model", adjusted.modelId)
            .putInt("threads", adjusted.threads).apply()
        refreshPresence()
    }
    fun refreshPresence() {
        val cfg = _config.value
        _ready.value = false
        viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) {
                val info = ModelCatalog.byId(cfg.modelId) ?: ModelCatalog.DEFAULT
                ModelStore.isPresent(getApplication(), info) &&
                    runCatching { ModelRepository.translationPresent(cfg.profile.source, cfg.profile.target) }.getOrDefault(false)
            }
            if (_config.value == cfg) _ready.value = ready
        }
    }
    fun downloadRequired() {
        if (_busy.value) return
        val cfg = _config.value
        _busy.value = true
        viewModelScope.launch {
            try {
                val info = ModelCatalog.byId(cfg.modelId) ?: ModelCatalog.DEFAULT
                if (!ModelRepository.download(getApplication(), info)) return@launch
                if (!ModelRepository.translationPresent(cfg.profile.source, cfg.profile.target))
                    ModelRepository.prepareTranslation(getApplication(), info, cfg.profile.source, cfg.profile.target)
            } finally { _busy.value = false; refreshPresence() }
        }
    }
    fun toggle() {
        if (CaptionState.running.value) CaptionService.stop(getApplication())
        else if (_ready.value) CaptionService.start(getApplication(), _config.value)
    }
    fun clear() = CaptionState.clear()
    fun dismissError() = CaptionState.setError(null)
}
