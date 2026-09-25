package com.asr.live.asr

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.asr.live.model.ModelCatalog
import java.io.File
import java.util.concurrent.Executors

/** Native QNN errors may call _Exit. Confine them to this private, non-exported process. */
class QnnService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var engine: StreamingEngine? = null
    private var eventBuffer = RecognitionEventBuffer()
    private var acceptedCalls = 0L
    @Volatile private var sessionId = 0L
    private external fun configureDspPath(path: ByteArray)
    private val binder = object : IQnnRecognizer.Stub() {
        override fun pid() = Process.myPid()
        override fun initialize(directory: String, language: String, threads: Int, requestedSessionId: Long): Bundle {
            return worker.submit<Bundle> {
                sessionId = requestedSessionId
                val totalStarted = System.nanoTime()
                val libraryLoadNs = measureNs { System.loadLibrary("live-translator") }
                val dsp = File(filesDir, "qnn-dsp").apply { mkdirs() }
                val skel = File(dsp, "libQnnHtpV79Skel.so")
                // Runtime asset is part of the signed APK, never remotely downloaded executable code.
                val dspCopyNs = measureNs {
                    assets.open("qnn/libQnnHtpV79Skel.so").use { input -> skel.outputStream().use { input.copyTo(it) } }
                }
                val dspSetupNs = measureNs {
                    configureDspPath((dsp.absolutePath + ";" + applicationInfo.nativeLibraryDir + ";/vendor/lib/rfsa/adsp;/vendor/dsp;/system/lib/rfsa/adsp").toByteArray())
                }
                eventBuffer = RecognitionEventBuffer()
                val recognizerPrepareNs = measureNs {
                    engine = StreamingEngine(File(directory), ModelCatalog.NEMOTRON_QNN,
                        { eventBuffer.partial(it) }, { eventBuffer.final(it) }, language, threads,
                        applicationInfo.nativeLibraryDir)
                }
                val initializeTotalNs = System.nanoTime() - totalStarted
                Log.i(TAG, "profile stage=initialize session=$sessionId monotonic_ns=$totalStarted total_ns=$initializeTotalNs library_load_ns=$libraryLoadNs dsp_copy_ns=$dspCopyNs dsp_setup_ns=$dspSetupNs recognizer_prepare_ns=$recognizerPrepareNs graph_breakdown=unavailable")
                Bundle().apply {
                    putLong("qnn_init_total_ns", initializeTotalNs)
                    putLong("qnn_library_load_ns", libraryLoadNs)
                    putLong("qnn_dsp_copy_ns", dspCopyNs)
                    putLong("qnn_dsp_setup_ns", dspSetupNs)
                    putLong("qnn_recognizer_prepare_ns", recognizerPrepareNs)
                    putString("qnn_graph_breakdown", "unavailable")
                }
            }.get()
        }
        override fun accept(samples: FloatArray): Bundle = worker.submit<Bundle> {
            val serviceStarted = System.nanoTime()
            eventBuffer = RecognitionEventBuffer()
            engine!!.accept(samples)
            resultBundle(System.nanoTime() - serviceStarted, "accept")
        }.get()
        override fun finish(): Bundle = worker.submit<Bundle> {
            val serviceStarted = System.nanoTime()
            eventBuffer = RecognitionEventBuffer()
            engine!!.finish()
            resultBundle(System.nanoTime() - serviceStarted, "finish")
        }.get()
        override fun shutdown() { worker.submit { engine?.release(); engine = null }.get() }
    }
    private fun resultBundle(serviceNanos: Long, stage: String): Bundle {
        val events = eventBuffer.snapshot()
        val stats = engine?.decodeStats ?: AsrDecodeStats()
        val pipeline = engine?.pipelineStats ?: AsrPipelineStats()
        acceptedCalls++
        if (stage == "finish" || acceptedCalls % 64L == 0L)
            Log.i(TAG, "profile stage=$stage session=$sessionId monotonic_ns=${System.nanoTime()} service_ns=$serviceNanos decode_calls=${stats.calls} decode_total_ns=${stats.totalNanos} audio_feed_total_ns=${pipeline.audioFeedNanos} result_total_ns=${pipeline.resultNanos} endpoint_check_total_ns=${pipeline.endpointCheckNanos} graph_breakdown=unavailable")
        return Bundle().apply {
            putIntArray("event_types", events.map { if (it is RecognitionEvent.Final) 1 else 0 }.toIntArray())
            putStringArrayList("event_texts", ArrayList(events.map { it.text }))
            putLong("decode_calls", stats.calls)
            putLong("decode_total_ns", stats.totalNanos)
            putLong("decode_mean_ns", stats.meanNanos)
            putLong("decode_p50_ns", stats.p50Nanos)
            putLong("decode_p95_ns", stats.p95Nanos)
            putLong("decode_max_ns", stats.maxNanos)
            putLongArray("decode_samples_ns", stats.samplesNanos.toLongArray())
            putLong("qnn_service_ns", serviceNanos)
            putLong("asr_audio_feed_ns", pipeline.audioFeedNanos)
            putLong("asr_result_ns", pipeline.resultNanos)
            putLong("asr_endpoint_check_ns", pipeline.endpointCheckNanos)
            putString("qnn_graph_breakdown", "unavailable: sherpa OnlineRecognizer.decode is combined")
        }
    }
    private inline fun measureNs(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return System.nanoTime() - started
    }
    override fun onBind(intent: Intent?) = binder
    override fun onDestroy() {
        worker.submit { engine?.release(); engine = null }; worker.shutdown()
        super.onDestroy()
    }

    private companion object { const val TAG = "LiveCaptionsQNN" }
}
