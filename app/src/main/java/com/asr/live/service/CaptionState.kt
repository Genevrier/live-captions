package com.asr.live.service

import com.asr.live.pipeline.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ListeningState { STOPPED, STARTING, LISTENING, STOPPING }
data class Performance(
    val asrMs: Long = 0, val asrRtf: Double = 0.0, val translationMs: Long = 0,
    val provisionalLatencyMs: Long? = null, val finalLatencyMs: Long? = null,
    val audioDepth: Int = 0, val provisionalDepth: Int = 0, val finalDepth: Int = 0,
    val captionBacklogMs: Long = 0,
    val backlogMs: Long = 0, val droppedAudioMs: Long = 0, val skippedTranslations: Int = 0,
    val correctionMs: Long = 0, val correctionRtf: Double = 0.0, val skippedCorrections: Int = 0,
    val asr: String = "", val translator: String = "ML Kit", val backend: String = "CPU",
    val profile: String = "Dutch → English", val chunk: String = "560 ms", val threads: Int = 6,
)
object CaptionState {
    private val ledger = SegmentLedger()
    private val _lines = MutableStateFlow<List<Caption>>(emptyList())
    val lines = _lines.asStateFlow()
    private val _lifecycle = MutableStateFlow(ListeningState.STOPPED)
    val lifecycle = _lifecycle.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()
    private val _metrics = MutableStateFlow(Performance())
    val metrics = _metrics.asStateFlow()
    @Volatile private var generation = -1L
    @Synchronized fun begin(id: Long, config: SessionConfig, modelName: String) {
        generation = id; ledger.start(id); publish()
        _metrics.value = Performance(asr = modelName, translator = config.quality.label, profile = config.profile.label, threads = config.threads, chunk = if (config.modelId == com.asr.live.model.ModelCatalog.NEMOTRON.id) "560 ms" else "VAD phrases")
        _error.value = null; setLifecycle(ListeningState.STARTING)
    }
    @Synchronized fun source(id: Long, text: String, endpoint: Boolean, nowMs: Long): Caption? =
        ledger.source(id, text, endpoint, nowMs)?.also { publish() }
    @Synchronized fun translated(key: SegmentKey, text: String, rank: Int, final: Boolean): Boolean =
        ledger.translate(key, text, rank, final).also { if (it) publish() }
    @Synchronized fun revise(key: SegmentKey, text: String): Caption? = ledger.revise(key, text)?.also { publish() }
    @Synchronized fun skip(key: SegmentKey, reason: String) { ledger.skip(key, reason); publish() }
    fun current(key: SegmentKey) = ledger.current(key)
    @Synchronized fun discontinuity(id: Long) { ledger.discontinuity(id); publish() }
    @Synchronized fun cancel(id: Long) {
        if (id != generation) return
        ledger.cancel(); publish(); setLifecycle(ListeningState.STOPPING)
    }
    @Synchronized fun stopped(id: Long) {
        if (id != generation) return
        setLifecycle(ListeningState.STOPPED); _status.value = null
    }
    @Synchronized fun listening(id: Long) { if (id == generation) setLifecycle(ListeningState.LISTENING) }
    @Synchronized fun metrics(id: Long, update: (Performance) -> Performance) {
        if (id == generation) _metrics.value = update(_metrics.value)
    }
    @Synchronized fun error(id: Long, text: String) { if (id == generation) _error.value = text }
    fun setError(text: String?) { _error.value = text }
    fun setStatus(text: String?) { _status.value = text }
    @Synchronized fun clear() { ledger.clear(); publish() }
    private fun publish() { _lines.value = ledger.snapshot() }
    private fun setLifecycle(value: ListeningState) { _lifecycle.value = value; _running.value = value != ListeningState.STOPPED }
}
