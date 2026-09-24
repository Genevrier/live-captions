package com.asr.live.asr

import android.content.*
import android.os.*
import com.asr.live.model.*
import com.asr.live.pipeline.BackendPolicy
import java.util.concurrent.*

/** Bounded synchronous RPC keeps ASR backpressure in the parent audio queue.
 * A native crash, timeout, unsupported device or initialization failure selects CPU. */
class QnnEngine(private val ctx: Context, private val language: String, private val threads: Int,
                private val onPartial: (String) -> Unit, private val onFinal: (String) -> Unit,
                private val onBackend: (String) -> Unit, private val onGap: () -> Unit) : AsrEngine {
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
            check(bound && connected.await(10, TimeUnit.SECONDS)) { "QNN process connection timed out" }
            remotePid = call(2) { it.pid() }
            call(40) { it.initialize(ModelStore.dir(ctx, ModelCatalog.NEMOTRON_QNN.id).absolutePath, language, threads) }
            // Do not claim active NPU until the first actual decoder call succeeds.
            onBackend("QNN initializing · experimental")
        } catch (t: Exception) { fallback(t) }
    }
    private fun <T> call(seconds: Long, fn: (IQnnRecognizer) -> T): T {
        val future = rpc.submit<T> { fn(checkNotNull(remote) { "QNN process unavailable" }) }
        return try { future.get(seconds, TimeUnit.SECONDS) }
        catch (t: Exception) { future.cancel(true); throw t }
    }
    private fun disconnect() {
        if (remotePid > 0 && remotePid != Process.myPid()) Process.killProcess(remotePid)
        remotePid = -1; remote = null
        if (bound) { runCatching { ctx.unbindService(connection) }; bound = false }
    }
    private fun fallback(reason: Exception) {
        disconnect(); rpc.shutdownNow()
        if (reason is InterruptedException) throw reason
        ModelStore.verify(ctx, ModelCatalog.NEMOTRON)
        cpu = EngineFactory.create(ctx, ModelCatalog.NEMOTRON, language, "transcribe", onPartial, onFinal, threads)
        onBackend("CPU · QNN fallback: ${reason.cause?.message ?: reason.message}")
    }
    override fun accept(samples: FloatArray) {
        cpu?.let { it.accept(samples); return }
        try {
            val result = call(5) { it.accept(samples) }
            onBackend("QNN/NPU · experimental")
            val text = result.getString("text").orEmpty()
            if (result.getBoolean("endpoint")) onFinal(text) else onPartial(text)
        } catch (t: Exception) { onGap(); fallback(t); cpu!!.accept(samples) }
    }
    override fun finish() {
        cpu?.let { it.finish(); return }
        try {
            val result = call(10) { it.finish() }
            if (result.getBoolean("endpoint")) onFinal(result.getString("text").orEmpty())
            onPartial("")
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
}
