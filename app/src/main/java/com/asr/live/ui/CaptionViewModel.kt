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
        quality = TranslationQuality.entries.firstOrNull { it.name == prefs.getString("quality", null) } ?: TranslationQuality.HY_Q8,
        qnn = prefs.getBoolean("qnn", false),
        correction = prefs.getBoolean("correction", false),
        glossary = prefs.getString("glossary", "") ?: "",
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
        val adjusted = if (config.profile != _config.value.profile) config.copy(modelId = ModelCatalog.defaultFor(config.profile.source).id, correction = config.correction && config.profile.correctionSupported)
            else if (info?.supports(config.profile.source) == true) config else config.copy(modelId = ModelCatalog.defaultFor(config.profile.source).id)
        val previous = _config.value
        _config.value = adjusted
        prefs.edit().putString("profile", adjusted.profile.name).putString("model", adjusted.modelId)
            .putInt("threads", adjusted.threads).putString("quality", adjusted.quality.name)
            .putString("glossary", adjusted.glossary).putBoolean("correction", adjusted.correction).putBoolean("qnn", adjusted.qnn).apply()
        if (previous.profile != adjusted.profile || previous.modelId != adjusted.modelId || previous.quality != adjusted.quality || previous.correction != adjusted.correction || previous.qnn != adjusted.qnn) refreshPresence()
    }
    fun refreshPresence() {
        val cfg = _config.value
        _ready.value = false
        viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) {
                val info = ModelCatalog.byId(cfg.modelId) ?: ModelCatalog.DEFAULT
                ModelStore.isPresent(getApplication(), info) && (!info.requiresVad || TranslationModels.present(getApplication(), "silero-vad")) && (!cfg.qnn || info.kind != EngineKind.NEMOTRON || ModelStore.isPresent(getApplication(), ModelCatalog.NEMOTRON_QNN)) && (!cfg.correction || !cfg.profile.correctionSupported || ModelStore.isPresent(getApplication(), ModelCatalog.PARAKEET)) && if (cfg.quality == TranslationQuality.ML_KIT) {
                    runCatching { ModelRepository.translationPresent(cfg.profile.source, cfg.profile.target) }.getOrDefault(false)
                } else {
                    TranslationModels.present(getApplication(), cfg.quality.bundleId!!) &&
                        (cfg.profile.fastBundle?.let { TranslationModels.present(getApplication(), it) } ?: true)
                }
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
                if (info.requiresVad && !ModelRepository.downloadBundle(getApplication(), "silero-vad")) return@launch
                if (!ModelRepository.download(getApplication(), info)) return@launch
                if (cfg.qnn && info.kind == EngineKind.NEMOTRON && !ModelRepository.download(getApplication(), ModelCatalog.NEMOTRON_QNN)) return@launch
                if (cfg.correction && cfg.profile.correctionSupported && !ModelRepository.download(getApplication(), ModelCatalog.PARAKEET)) return@launch
                if (cfg.quality == TranslationQuality.ML_KIT) {
                    if (!ModelRepository.translationPresent(cfg.profile.source, cfg.profile.target))
                        ModelRepository.prepareTranslation(getApplication(), info, cfg.profile.source, cfg.profile.target)
                } else {
                    if (!ModelRepository.downloadBundle(getApplication(), cfg.quality.bundleId!!)) return@launch
                    cfg.profile.fastBundle?.let { ModelRepository.downloadBundle(getApplication(), it) }
                }
            } finally { _busy.value = false; refreshPresence() }
        }
    }
    fun downloadMegabytes(): Long {
        val cfg = _config.value
        var bytes = model().archiveBytes
        if (model().requiresVad) bytes += TranslationModels.bundle(getApplication(), "silero-vad").size
        if (cfg.qnn && model().kind == EngineKind.NEMOTRON) bytes += ModelCatalog.NEMOTRON_QNN.archiveBytes
        if (cfg.correction && cfg.profile.correctionSupported) bytes += ModelCatalog.PARAKEET.archiveBytes
        cfg.quality.bundleId?.let { bytes += TranslationModels.bundle(getApplication(), it).size }
        if (cfg.quality != TranslationQuality.ML_KIT) cfg.profile.fastBundle?.let { bytes += TranslationModels.bundle(getApplication(), it).size }
        return bytes / 1_000_000
    }
    fun toggle() {
        if (CaptionState.running.value) CaptionService.stop(getApplication())
        else if (_ready.value && !_busy.value) {
            val cfg = _config.value
            val check = runCatching { com.asr.live.i18n.TranslationPrompt.build(cfg.profile, "", cfg.glossary) }
            if (check.isFailure) CaptionState.setError(check.exceptionOrNull()?.message)
            else CaptionService.start(getApplication(), cfg)
        }
    }
    fun clear() = CaptionState.clear()
    fun dismissError() = CaptionState.setError(null)
}
