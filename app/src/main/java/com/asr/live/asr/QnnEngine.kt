package com.asr.live.asr

import android.content.*
import android.os.*
import com.asr.live.model.*
import com.asr.live.pipeline.BackendPolicy
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Bounded synchronous RPC keeps ASR backpressure in the parent audio queue.
 * A native crash, timeout, unsupported device or initialization failure selects CPU. */
class QnnEngine(private val ctx: Context, private val language: String, private val threads: Int,
                private val onPartial: (String) -> Unit, private val onFinal: (String) -> Unit,
                private val onBackend: (String) -> Unit, private val onGap: () -> Unit,
                private val sessionId: Long = 0L,
                private val onProfile: (QnnProfileStats) -> Unit = {},
                /** Lets Stop abandon startup instead of paying for a CPU engine nobody will use. */
                private val isAborted: () -> Boolean = { false }) : AsrEngine {
    @Volatile private var remoteDecodeStats = AsrDecodeStats()
    private val qnnProfileTelemetry = QnnProfileTelemetry()
    override val pipelineStats: AsrPipelineStats get() = cpu?.pipelineStats ?: remotePipelineStats.get()
    private val remotePipelineStats = AtomicReference(AsrPipelineStats())
    private val decodeStatsAccumulator = DecodeStatsAccumulator()
    override val decodeStats: AsrDecodeStats get() = decodeStatsAccumulator.snapshot(cpu?.decodeStats ?: remoteDecodeStats)
    @Volatile private var remote: IQnnRecognizer? = null
    private var remotePid = -1
    private var bound = false
    private var cpu: AsrEngine? = null
    private val connected = CountDownLatch(1)
    private val rpc = Executors.newSingleThreadExecutor()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            remote = IQnnRecognizer.Stub.asInterface(binder); connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) { remote = null; connected.countDown() }
        override fun onNullBinding(name: ComponentName) { connected.countDown() }
        override fun onBindingDied(name: ComponentName) { remote = null; connected.countDown() }
    }
    init {
        try {
            check(Build.VERSION.SDK_INT >= 31 && BackendPolicy.qnnEligible(Build.SOC_MODEL, com.asr.live.BuildConfig.QNN_ENABLED)) { "Requires SM8750 and the QNN build" }
            ModelStore.verify(ctx, ModelCatalog.NEMOTRON_QNN)
            bound = ctx.bindService(Intent(ctx, QnnService::class.java), connection, Context.BIND_AUTO_CREATE)
            check(bound && connected.await(QnnStartup.BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "QNN process connection timed out" }
            checkNotAborted()
            remotePid = call(QnnStartup.PID_TIMEOUT_MS) { it.pid() }
            checkNotAborted()
            val initialized = call(QnnStartup.INITIALIZE_TIMEOUT_MS) {
                it.initialize(ModelStore.dir(ctx, ModelCatalog.NEMOTRON_QNN.id).absolutePath,
                    language, threads, sessionId) }
            qnnProfileTelemetry.recordInitialization(initialized)
            onProfile(qnnProfileTelemetry.snapshot())
            // Do not claim active NPU until the first actual decoder call succeeds.
            onBackend("QNN initializing · experimental")
        } catch (t: Exception) { fallback(t) }
    }
    private fun checkNotAborted() {
        if (isAborted()) throw InterruptedException("QNN startup abandoned after stop")
    }
    private fun <T> call(timeoutMs: Long, dataPlane: Boolean = false, fn: (IQnnRecognizer) -> T): T {
        val queuedAtNs = System.nanoTime()
        val startedAtNs = AtomicLong(0L)
        val future = rpc.submit<T> {
            startedAtNs.set(System.nanoTime())
            fn(checkNotNull(remote) { "QNN process unavailable" })
        }
        return try {
            val result = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            val finishedAtNs = System.nanoTime()
            if (dataPlane && result is Bundle) {
                val rpcServiceNs = result.getLong("qnn_service_ns")
                qnnProfileTelemetry.recordDataCall(
                    startedAtNs.get() - queuedAtNs, finishedAtNs - queuedAtNs, rpcServiceNs, result)
                updateDecodeStats(result)
                remotePipelineStats.set(AsrPipelineStats(
                    audioFeedNanos = result.getLong("asr_audio_feed_ns"),
                    resultNanos = result.getLong("asr_result_ns"),
                    endpointCheckNanos = result.getLong("asr_endpoint_check_ns")))
                onProfile(qnnProfileTelemetry.snapshot(remoteDecodeStats.totalNanos))
            }
            result
        }
        catch (t: Exception) { future.cancel(true); throw t }
    }
    private fun disconnect() {
        if (remotePid > 0 && remotePid != Process.myPid()) Process.killProcess(remotePid)
        remotePid = -1; remote = null
        if (bound) { runCatching { ctx.unbindService(connection) }; bound = false }
    }
    private fun fallback(reason: Exception) {
        decodeStatsAccumulator.retire(remoteDecodeStats)
        disconnect(); rpc.shutdownNow()
        if (reason is InterruptedException) throw reason
        // Never build a CPU recognizer for a session the user already stopped.
        if (isAborted()) throw InterruptedException("QNN fallback abandoned after stop")
        ModelStore.verify(ctx, ModelCatalog.NEMOTRON)
        cpu = EngineFactory.create(ctx, ModelCatalog.NEMOTRON, language, "transcribe", onPartial, onFinal, threads)
        onBackend("CPU · QNN fallback: ${reason.cause?.message ?: reason.message}")
    }
    override fun accept(samples: FloatArray) {
        cpu?.let { it.accept(samples); return }
        try {
            val result = call(QnnStartup.ACCEPT_TIMEOUT_MS, dataPlane = true) { it.accept(samples) }
            onBackend("QNN/NPU · experimental")
            dispatch(result)
        } catch (t: Exception) { onGap(); fallback(t); cpu!!.accept(samples) }
    }
    override fun finish() {
        cpu?.let { it.finish(); return }
        try {
            val result = call(QnnStartup.FINISH_TIMEOUT_MS, dataPlane = true) { it.finish() }
            dispatch(result)
        } catch (t: Exception) {
            onGap()
            fallback(t)
            cpu?.finish()
        }
    }
    override fun release() {
        // Killing only the dedicated same-UID worker also handles a hung vendor call.
        disconnect(); rpc.shutdownNow(); cpu?.release(); cpu = null
    }

    private fun dispatch(result: Bundle) {
        val types = result.getIntArray("event_types") ?: return
        val texts = result.getStringArrayList("event_texts") ?: return
        val count = minOf(types.size, texts.size)
        val events = (0 until count).map { index ->
            if (types[index] == 1) RecognitionEvent.Final(texts[index]) else RecognitionEvent.Partial(texts[index])
        }
        dispatchRecognitionEvents(events, onPartial, onFinal)
    }

    private fun updateDecodeStats(result: Bundle) {
        remoteDecodeStats = AsrDecodeStats(
            calls = result.getLong("decode_calls"),
            totalNanos = result.getLong("decode_total_ns"),
            meanNanos = result.getLong("decode_mean_ns"),
            p50Nanos = result.getLong("decode_p50_ns"),
            p95Nanos = result.getLong("decode_p95_ns"),
            maxNanos = result.getLong("decode_max_ns"),
            samplesNanos = result.getLongArray("decode_samples_ns")?.toList().orEmpty(),
        )
    }
}
