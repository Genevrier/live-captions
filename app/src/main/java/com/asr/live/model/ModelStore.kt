package com.asr.live.model

import android.content.Context
import java.io.File

/** On-disk locations for downloaded models and the bundled VAD. */
object ModelStore {

    fun dir(ctx: Context, id: String): File = File(File(ctx.filesDir, "models"), id)

    fun isPresent(ctx: Context, info: ModelInfo): Boolean {
        val d = dir(ctx, info.id)
        val allPresent = info.requiredFiles.all { File(d, it).let { f -> f.exists() && f.length() > 0 } }
        // Reject truncated weights (e.g. an interrupted/partial download) so we show the
        // download gate again instead of feeding a corrupt model to native code (which aborts).
        val encoderOk = File(d, info.encoder).length() >= info.encoderMinBytes
        val decoderOk = File(d, info.decoder).length() >= info.decoderMinBytes
        return allPresent && encoderOk && decoderOk
    }

    /**
     * Silero VAD ships in assets; sherpa-onnx (with a null AssetManager) needs a real
     * filesystem path, so copy it out once into filesDir.
     */
    fun vadPath(ctx: Context): String {
        val out = File(ctx.filesDir, "silero_vad.onnx")
        if (!out.exists() || out.length() == 0L) {
            ctx.assets.open("silero_vad.onnx").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return out.absolutePath
    }
}
