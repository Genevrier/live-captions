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
    private var text = ""
    private var endpoint = false
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
                engine = StreamingEngine(File(directory), ModelCatalog.NEMOTRON_QNN,
                    { text = it }, { text = it; endpoint = true }, language, threads,
                    applicationInfo.nativeLibraryDir)
            }.get()
        }
        override fun accept(samples: FloatArray): Bundle = worker.submit<Bundle> {
            endpoint = false; text = ""
            engine!!.accept(samples)
            Bundle().apply { putString("text", text); putBoolean("endpoint", endpoint) }
        }.get()
        override fun shutdown() { worker.submit { engine?.release(); engine = null }.get() }
    }
    override fun onBind(intent: Intent?) = binder
    override fun onDestroy() {
        worker.submit { engine?.release(); engine = null }; worker.shutdown()
        super.onDestroy()
    }
}
