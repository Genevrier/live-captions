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

data class TranslationBenchmark(
    val model: String, val elapsedMs: Long, val output: String, val error: String? = null,
    val rssMiB: Long? = null, val availableMiB: Long? = null,
    val backend: String = "CPU", val requestedOpenCl: Boolean = false,
    val p95Ms: Long = 0, val prefillMs: Long = 0, val decodeMs: Long = 0,
    val loadMs: Long = 0, val batch: Int = 256, val ubatch: Int = 128,
    val qualityMatched: Boolean? = null, val quality: TranslationQuality? = null,
)

data class ManagedModel(val id: String, val label: String, val bytes: Long, val installed: Boolean, val selectable: Boolean = false, val stored: Boolean = installed)

class CaptionViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("profiles_v2", Context.MODE_PRIVATE)
    private val overlayPrefs = OverlayPreferences(app)
    private val deviceMode = PerformanceMode.defaultFor(if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else null, android.os.Build.MANUFACTURER, android.os.Build.MODEL)
    private val qnnPreferred = deviceMode == PerformanceMode.MAX_QUALITY && android.os.Build.VERSION.SDK_INT >= 31 &&
        BackendPolicy.qnnEligible(android.os.Build.SOC_MODEL, com.asr.live.BuildConfig.QNN_ENABLED)
    private val initialProfile = Profile.fromId(prefs.getString("profile", null))
    private val deviceKey = listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL,
        if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "unknown")
        .joinToString("-").replace(Regex("[^A-Za-z0-9._-]"), "_")
    private val deviceTune = prefs.getString("autotune.$deviceKey", null)?.split(":")?.takeIf { it.size == 4 }
    private val preferredQuality = TranslationQuality.entries.firstOrNull { it.name == deviceTune?.get(0) }
        ?: if (prefs.getBoolean("quality.userOverride", false))
            TranslationQuality.entries.firstOrNull { it.name == prefs.getString("quality", null) } else null
    private val initialMode = PerformanceMode.entries.firstOrNull { it.name == prefs.getString("performanceMode", null) }
        ?: deviceMode
    val overlay = overlayPrefs.state
    fun updateOverlay(options: OverlayOptions) = overlayPrefs.update(options)
    override fun onCleared() { overlayPrefs.close(); super.onCleared() }
    private val _managed = MutableStateFlow<List<ManagedModel>>(emptyList())
    val managed = _managed.asStateFlow()
    private val _config = MutableStateFlow(SessionConfig(
        profile = initialProfile,
        performanceMode = initialMode,
        modelId = prefs.getString("model", ModelCatalog.DEFAULT.id) ?: ModelCatalog.DEFAULT.id,
        threads = prefs.getInt("threads", 6).coerceIn(1, 8),
        correctionThreads = prefs.getInt("correctionThreads", 4).let { if (it in setOf(2, 4, 6, 8)) it else 4 },
        quality = preferredQuality
            ?: when (initialMode) {
                PerformanceMode.FAST -> TranslationQuality.HY_7B_Q4
                PerformanceMode.MAX_QUALITY -> TranslationQuality.HY_7B_Q4
                PerformanceMode.BALANCED -> TranslationQuality.HY_Q8
            },
        qnn = if (prefs.getBoolean("qnn.userOverride", false)) prefs.getBoolean("qnn", qnnPreferred) else qnnPreferred,
        gpuTranslation = deviceTune?.get(1)?.let { it == "true" }
            ?: prefs.getBoolean("gpuTranslation", deviceMode == PerformanceMode.MAX_QUALITY && com.asr.live.BuildConfig.OPENCL_ENABLED),
        translationBatch = (deviceTune?.get(2)?.toIntOrNull() ?: prefs.getInt("translationBatch", 256)).let { if (it in setOf(128, 256, 512)) it else 256 },
        translationUbatch = (deviceTune?.get(3)?.toIntOrNull() ?: prefs.getInt("translationUbatch", 128)).let { if (it in setOf(64, 128, 256)) it else 128 },
        correction = if (prefs.getBoolean("correction.userOverride", false)) prefs.getBoolean("correction", false)
            else initialMode == PerformanceMode.MAX_QUALITY && initialProfile.correctionSupported,
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
    val comparisons = CaptionState.comparisons
    val download = ModelRepository.state
    private val _benchmark = MutableStateFlow<List<TranslationBenchmark>>(emptyList())
    val benchmark = _benchmark.asStateFlow()
    private val _benchmarkBusy = MutableStateFlow(false)
    val benchmarkBusy = _benchmarkBusy.asStateFlow()
    fun benchmarkTranslation(source: String) {
        if (_busy.value || _benchmarkBusy.value || CaptionState.running.value || source.isBlank()) return
        val profile = _config.value.profile
        val glossary = _config.value.glossary
        val current = _config.value
        val sources = source.lineSequence().map(String::trim).filter(String::isNotEmpty).take(12).toList()
        if (sources.isEmpty()) return
        _benchmarkBusy.value = true
        _benchmark.value = emptyList()
        viewModelScope.launch {
            val results = mutableListOf<TranslationBenchmark>()
            try {
                val qualities = listOf(TranslationQuality.HY_7B_Q6, TranslationQuality.HY_7B_Q4,
                    TranslationQuality.HY_7B_Q5, TranslationQuality.HY_7B_Q8)
                val configs = listOf(Triple(false, 256, 128), Triple(true, 128, 64), Triple(true, 256, 128),
                    Triple(true, 512, 128), Triple(true, 512, 256)).filter { !it.first || com.asr.live.BuildConfig.OPENCL_ENABLED }
                for (quality in qualities) {
                    val id = quality.bundleId!!
                    val installed = withContext(Dispatchers.IO) { TranslationModels.present(getApplication(), id) }
                    if (!installed) {
                        results += TranslationBenchmark(quality.label, 0, "", "Model not downloaded", quality = quality)
                        _benchmark.value = results.toList()
                        continue
                    }
                    for ((preferOpenCl, batch, ubatch) in configs) {
                        val result = withContext(Dispatchers.IO) {
                            val requestLabel = "${quality.label} · ${batch}/${ubatch}"
                            val bundle = TranslationModels.bundle(getApplication(), id)
                            if (!com.asr.live.service.MemoryUsage.canLoad(getApplication(), bundle.size)) {
                                TranslationBenchmark(requestLabel, 0, "", "Insufficient free RAM with 2 GiB system reserve",
                                    requestedOpenCl = preferOpenCl, batch = batch, ubatch = ubatch, quality = quality)
                            } else runCatching {
                                val dir = TranslationModels.verify(getApplication(), id)
                                val loadStarted = android.os.SystemClock.elapsedRealtime()
                                com.asr.live.i18n.NativeTranslator(java.io.File(dir, "model.gguf"), minOf(current.threads, 4),
                                    false, profile, glossary, preferOpenCl,
                                    getApplication<Application>().getDir("llama-opencl-cache", Context.MODE_PRIVATE), batch, ubatch).use { engine ->
                                    val loadMs = android.os.SystemClock.elapsedRealtime() - loadStarted
                                    engine.warmUp()
                                    val durations = mutableListOf<Long>()
                                    var prefill = 0L; var decode = 0L
                                    var output = emptyList<String>()
                                    repeat(5) {
                                        output = sources.map { sentence ->
                                            val start = android.os.SystemClock.elapsedRealtime()
                                            val translated = engine.translate(sentence)
                                            durations += android.os.SystemClock.elapsedRealtime() - start
                                            prefill += engine.timings.prefillMs
                                            decode += engine.timings.decodeMs
                                            translated
                                        }
                                    }
                                    val memory = com.asr.live.service.MemoryUsage.sample(getApplication())
                                    val sorted = durations.sorted()
                                    val average = durations.average().toLong()
                                    val p95 = sorted[((sorted.size * 0.95).toInt().coerceAtLeast(1) - 1).coerceAtMost(sorted.lastIndex)]
                                    TranslationBenchmark(requestLabel, average, output.joinToString("\n"),
                                        rssMiB = memory.appPssKb / 1024, availableMiB = memory.availableKb / 1024,
                                        backend = engine.backend, requestedOpenCl = preferOpenCl, p95Ms = p95,
                                        prefillMs = prefill / durations.size, decodeMs = decode / durations.size,
                                        loadMs = loadMs, batch = batch, ubatch = ubatch, quality = quality)
                                }
                            }.getOrElse { TranslationBenchmark(requestLabel, 0, "", it.message ?: "Benchmark failed",
                                requestedOpenCl = preferOpenCl, batch = batch, ubatch = ubatch, quality = quality) }
                        }
                        results += result
                        _benchmark.value = results.toList()
                    }
                }
                val reference = results.firstOrNull { it.quality == TranslationQuality.HY_7B_Q6 && !it.requestedOpenCl && it.error == null }
                val referenceText = reference?.output?.let(::canonicalTranslation)
                val qualified = if (referenceText == null) emptyList() else results.filter { candidate ->
                    candidate.error == null && candidate.quality != null && canonicalTranslation(candidate.output) == referenceText &&
                        (!candidate.requestedOpenCl || candidate.backend.startsWith("Adreno OpenCL"))
                }
                if (referenceText != null) {
                    val marked = results.map { it.copy(qualityMatched = it.error == null && canonicalTranslation(it.output) == referenceText) }
                    _benchmark.value = marked
                    val best = qualified.minByOrNull { it.elapsedMs }
                    if (best != null) {
                        val tuned = current.copy(quality = checkNotNull(best.quality), gpuTranslation = best.requestedOpenCl,
                            translationBatch = best.batch, translationUbatch = best.ubatch)
                        _config.value = tuned
                        prefs.edit().putString("quality", tuned.quality.name).putBoolean("gpuTranslation", tuned.gpuTranslation)
                            .putInt("translationBatch", tuned.translationBatch).putInt("translationUbatch", tuned.translationUbatch)
                            .putString("autotune.$deviceKey", "${tuned.quality.name}:${tuned.gpuTranslation}:${tuned.translationBatch}:${tuned.translationUbatch}")
                            .apply()
                        refreshPresence()
                    }
                }
            } finally { _benchmarkBusy.value = false }
        }
    }

    private fun canonicalTranslation(text: String) = text.trim().replace(Regex("\\s+"), " ").lowercase()
    init { refreshPresence() }
    fun model() = ModelCatalog.byId(_config.value.modelId) ?: ModelCatalog.DEFAULT
    fun models() = ModelCatalog.ALL.filter { it.supports(_config.value.profile.source) }
    fun selectMode(mode: PerformanceMode) = update(_config.value.withMode(mode))
    fun update(config: SessionConfig, userChangedQnn: Boolean = false, userChangedCorrection: Boolean = false) {
        if (_busy.value || _benchmarkBusy.value || CaptionState.running.value) return
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
        if (previous.quality != adjusted.quality || previous.gpuTranslation != adjusted.gpuTranslation ||
            previous.translationBatch != adjusted.translationBatch || previous.translationUbatch != adjusted.translationUbatch)
            prefs.edit().remove("autotune.$deviceKey").apply()
        if (previous.quality != adjusted.quality) prefs.edit().putBoolean("quality.userOverride", true).apply()
        prefs.edit().putString("profile", adjusted.profile.name).putString("model", adjusted.modelId)
            .putInt("threads", adjusted.threads).putInt("correctionThreads", adjusted.correctionThreads).putString("quality", adjusted.quality.name)
            .putString("performanceMode", adjusted.performanceMode.name)
            .putString("glossary", adjusted.glossary).putBoolean("correction", adjusted.correction)
            .putBoolean("qnn", adjusted.qnn).putBoolean("gpuTranslation", adjusted.gpuTranslation).apply()
        if (userChangedQnn) prefs.edit().putBoolean("qnn.userOverride", true).apply()
        if (userChangedCorrection) prefs.edit().putBoolean("correction.userOverride", true).apply()
        prefs.edit().putInt("translationBatch", adjusted.translationBatch).putInt("translationUbatch", adjusted.translationUbatch).apply()
        if (previous.profile != adjusted.profile || previous.modelId != adjusted.modelId || previous.quality != adjusted.quality ||
            previous.performanceMode != adjusted.performanceMode || previous.correction != adjusted.correction || previous.qnn != adjusted.qnn) refreshPresence()
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
                        (!cfg.opusBenchmarkEnabled || cfg.profile.fastBundle?.let { TranslationModels.present(getApplication(), it) } == true)
                }
            }
            if (_config.value == cfg) _ready.value = ready
        }
    }
    fun downloadRequired() {
        if (_busy.value || _benchmarkBusy.value || CaptionState.running.value) return
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
                    if (cfg.opusBenchmarkEnabled) cfg.profile.fastBundle?.let { ModelRepository.downloadBundle(getApplication(), it) }
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
                    info.archiveBytes, installed, installed && loaded(info), ModelStore.dir(getApplication(), info.id).exists())
            } + bundleIds.map { id -> val b = TranslationModels.bundle(getApplication(), id)
                ManagedModel(id, b.label, b.size, TranslationModels.present(getApplication(), id), stored = ModelStore.dir(getApplication(), id).exists()) }
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
        if (_busy.value || _benchmarkBusy.value || CaptionState.running.value || _managed.value.none { it.id == id }) return
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
        if (_busy.value || _benchmarkBusy.value || CaptionState.running.value || _managed.value.none { it.id == id }) return
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
        if (cfg.opusBenchmarkEnabled) cfg.profile.fastBundle?.let { bytes += TranslationModels.bundle(getApplication(), it).size }
        return bytes / 1_000_000
    }
    fun toggle() {
        if (CaptionState.running.value) CaptionService.stop(getApplication())
        else if (_ready.value && !_busy.value && !_benchmarkBusy.value) {
            val cfg = _config.value
            val check = runCatching { com.asr.live.i18n.TranslationPrompt.build(cfg.profile, "", cfg.glossary) }
            if (check.isFailure) CaptionState.setError(check.exceptionOrNull()?.message)
            else CaptionService.start(getApplication(), cfg)
        }
    }
    fun clear() = CaptionState.clear()
    fun acknowledgeDisplayed(key: SegmentKey) {
        CaptionState.acknowledgeDisplayed(key.session, key, System.nanoTime())
    }
    fun rateComparison(key: SegmentKey, vote: TranslationVote) { CaptionState.vote(key, vote) }
    fun dismissError() = CaptionState.setError(null)
}
