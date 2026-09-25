package com.asr.live.asr

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Process
import com.asr.live.model.ModelCatalog
import java.io.File
import java.util.concurrent.Executors

/** Native QNN errors may call _Exit. Confine them to this private, non-exported process. */
class QnnService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private var engine: StreamingEngine? = null
    private var eventBuffer = RecognitionEventBuffer()
    private external fun configureDspPath(path: ByteArray)
    private val binder = object : IQnnRecognizer.Stub() {
        override fun pid() = Process.myPid()
        override fun initialize(directory: String, language: String, threads: Int) {
            worker.submit {
                System.loadLibrary("live-translator")
                val dsp = File(filesDir, "qnn-dsp").apply { mkdirs() }
                val skel = File(dsp, "libQnnHtpV79Skel.so")
                // Runtime asset is part of the signed APK, never remotely downloaded executable code.
                assets.open("qnn/libQnnHtpV79Skel.so").use { input -> skel.outputStream().use { input.copyTo(it) } }
                configureDspPath((dsp.absolutePath + ";" + applicationInfo.nativeLibraryDir + ";/vendor/lib/rfsa/adsp;/vendor/dsp;/system/lib/rfsa/adsp").toByteArray())
                eventBuffer = RecognitionEventBuffer()
                engine = StreamingEngine(File(directory), ModelCatalog.NEMOTRON_QNN,
                    { eventBuffer.partial(it) }, { eventBuffer.final(it) }, language, threads,
                    applicationInfo.nativeLibraryDir)
            }.get()
        }
        override fun accept(samples: FloatArray): Bundle = worker.submit<Bundle> {
            eventBuffer = RecognitionEventBuffer()
            engine!!.accept(samples)
            resultBundle()
        }.get()
        override fun finish(): Bundle = worker.submit<Bundle> {
            eventBuffer = RecognitionEventBuffer()
            engine!!.finish()
            resultBundle()
        }.get()
        override fun shutdown() { worker.submit { engine?.release(); engine = null }.get() }
    }
    private fun resultBundle(): Bundle {
        val events = eventBuffer.snapshot()
        val stats = engine?.decodeStats ?: AsrDecodeStats()
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
        }
    }
    override fun onBind(intent: Intent?) = binder
    override fun onDestroy() {
        worker.submit { engine?.release(); engine = null }; worker.shutdown()
        super.onDestroy()
    }
}
