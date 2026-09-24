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
import com.asr.live.overlay.OverlayPreferences
import com.asr.live.overlay.OverlayOptions

data class ManagedModel(val id: String, val label: String, val bytes: Long, val installed: Boolean, val selectable: Boolean = false)

class CaptionViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("profiles_v2", Context.MODE_PRIVATE)
    private val overlayPrefs = OverlayPreferences(app)
    val overlay = overlayPrefs.state
    fun updateOverlay(options: OverlayOptions) = overlayPrefs.update(options)
    override fun onCleared() { overlayPrefs.close(); super.onCleared() }
    private val _managed = MutableStateFlow<List<ManagedModel>>(emptyList())
    val managed = _managed.asStateFlow()
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
        var adjusted = if (config.profile != _config.value.profile) config.copy(modelId = ModelCatalog.defaultFor(config.profile.source).id, correction = config.correction && config.profile.correctionSupported)
            else if (info?.supports(config.profile.source) == true) config else config.copy(modelId = ModelCatalog.defaultFor(config.profile.source).id)
        if (adjusted.qnn && ModelCatalog.byId(adjusted.modelId)?.kind == EngineKind.NEMOTRON)
            adjusted = adjusted.copy(modelId = ModelCatalog.NEMOTRON.id)
        if (adjusted.modelId != _config.value.modelId && adjusted.modelId != ModelCatalog.NEMOTRON.id &&
            ModelCatalog.byId(adjusted.modelId)?.kind == EngineKind.NEMOTRON &&
            _managed.value.none { it.id == adjusted.modelId && it.selectable }) return
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
            refreshManaged()
            val ready = withContext(Dispatchers.IO) {
                val info = ModelCatalog.byId(cfg.modelId) ?: ModelCatalog.DEFAULT
                ModelStore.isPresent(getApplication(), info) && (info.kind != EngineKind.NEMOTRON || info == ModelCatalog.NEMOTRON || loaded(info)) && (!info.requiresVad || TranslationModels.present(getApplication(), "silero-vad")) && (!cfg.qnn || info.kind != EngineKind.NEMOTRON || ModelStore.isPresent(getApplication(), ModelCatalog.NEMOTRON_QNN)) && (!cfg.correction || !cfg.profile.correctionSupported || ModelStore.isPresent(getApplication(), ModelCatalog.PARAKEET)) && if (cfg.quality == TranslationQuality.ML_KIT) {
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
        if (_busy.value || CaptionState.running.value) return
        val cfg = _config.value
        _busy.value = true
        viewModelScope.launch {
            try {
                val info = ModelCatalog.byId(cfg.modelId) ?: ModelCatalog.DEFAULT
                if (info.requiresVad && !ModelRepository.downloadBundle(getApplication(), "silero-vad")) return@launch
                if (!ModelRepository.download(getApplication(), info)) return@launch
                if (info.kind == EngineKind.NEMOTRON) validateChunk(info)
                if (cfg.qnn && info.kind == EngineKind.NEMOTRON && !ModelRepository.download(getApplication(), ModelCatalog.NEMOTRON_QNN)) return@launch
                if (cfg.correction && cfg.profile.correctionSupported && !ModelRepository.download(getApplication(), ModelCatalog.PARAKEET)) return@launch
                if (cfg.quality == TranslationQuality.ML_KIT) {
                    if (!ModelRepository.translationPresent(cfg.profile.source, cfg.profile.target))
                        ModelRepository.prepareTranslation(getApplication(), info, cfg.profile.source, cfg.profile.target)
                } else {
                    if (!ModelRepository.downloadBundle(getApplication(), cfg.quality.bundleId!!)) return@launch
                    cfg.profile.fastBundle?.let { ModelRepository.downloadBundle(getApplication(), it) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                CaptionState.setError("Model preparation failed: ${e.message}")
            } finally { _busy.value = false; refreshPresence() }
        }
    }
    private fun stamp(info: ModelInfo) = "${com.asr.live.BuildConfig.VERSION_CODE}:${info.sha256}"
    private fun loaded(info: ModelInfo) = prefs.getString("loaded.${info.id}", null) == stamp(info)
    private val asrAssets get() = (ModelCatalog.ALL + ModelCatalog.NEMOTRON_PROFILES + ModelCatalog.PARAKEET + ModelCatalog.NEMOTRON_QNN).distinctBy { it.id }
    private val bundleIds get() = (TranslationQuality.entries.mapNotNull { it.bundleId } + Profile.entries.mapNotNull { it.fastBundle } + "silero-vad").distinct()
    private suspend fun refreshManaged() {
        _managed.value = withContext(Dispatchers.IO) {
            asrAssets.map { info ->
                val installed = ModelStore.isPresent(getApplication(), info)
                ManagedModel(info.id, if (info in ModelCatalog.NEMOTRON_PROFILES) "Nemotron CPU · ${ModelCatalog.chunkLabel(info.chunkMs)}" else info.displayName,
                    info.archiveBytes, installed, installed && loaded(info))
            } + bundleIds.map { id -> val b = TranslationModels.bundle(getApplication(), id)
                ManagedModel(id, b.label, b.size, TranslationModels.present(getApplication(), id)) }
        }
    }
    private suspend fun validateChunk(info: ModelInfo) = withContext(Dispatchers.IO) {
        ModelStore.verify(getApplication(), info)
        val engine = com.asr.live.asr.EngineFactory.create(getApplication(), info, _config.value.profile.source,
            "transcribe", {}, {}, _config.value.threads)
        try {
            // Exercise at least one complete encoder chunk; no microphone or persisted audio.
            repeat(20) { engine.accept(FloatArray(1600)) }
            engine.finish()
            prefs.edit().putString("loaded.${info.id}", stamp(info)).apply()
        } finally { engine.release() }
    }
    fun downloadModel(id: String) {
        if (_busy.value || CaptionState.running.value || _managed.value.none { it.id == id }) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val info = asrAssets.firstOrNull { it.id == id }
                if (info != null) {
                    if (ModelRepository.download(getApplication(), info) && info in ModelCatalog.NEMOTRON_PROFILES) validateChunk(info)
                } else ModelRepository.downloadBundle(getApplication(), id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                CaptionState.setError("Model load test failed: ${e.message}")
            } finally { _busy.value = false; refreshPresence() }
        }
    }
    fun removeModel(id: String) {
        if (_busy.value || CaptionState.running.value || _managed.value.none { it.id == id }) return
        _busy.value = true
        viewModelScope.launch {
            try {
                ModelRepository.remove(getApplication(), id)
                prefs.edit().remove("loaded.$id").apply()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                CaptionState.setError("Could not remove model: ${e.message}")
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
