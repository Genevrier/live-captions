package com.asr.live.model

import android.content.Context
import java.io.File
import java.util.Properties

object ModelStore {
    fun dir(ctx: Context, id: String): File = File(File(ctx.filesDir, "models"), id)
    fun isPresent(ctx: Context, info: ModelInfo): Boolean = isPresent(dir(ctx, info.id), info)
    fun isPresent(d: File, info: ModelInfo): Boolean = runCatching {
        val manifest = Properties().apply { File(d, "verified.properties").inputStream().use { load(it) } }
        manifest.getProperty("archive") == info.sha256 && info.requiredFiles.all {
            val file = File(d, it)
            file.isFile && file.length() > 0 && file.length().toString() == manifest.getProperty("size.$it")
        }
    }.getOrDefault(false)
    fun writeManifest(d: File, info: ModelInfo) {
        val manifest = Properties().apply {
            setProperty("archive", info.sha256)
            for (name in info.requiredFiles) {
                val file = File(d, name)
                check(file.isFile && file.length() > 0) { "Missing model file: $name" }
                setProperty("size.$name", file.length().toString())
                setProperty("sha.$name", VerifiedFiles.sha256(file))
            }
        }
        File(d, "verified.properties").outputStream().use { manifest.store(it, "Verified archive contents") }
    }
    fun verify(ctx: Context, info: ModelInfo) {
        val d = dir(ctx, info.id)
        check(isPresent(d, info)) { "Download required models first" }
        val manifest = Properties().apply { File(d, "verified.properties").inputStream().use { load(it) } }
        for (name in info.requiredFiles) VerifiedFiles.check(File(d, name), manifest.getProperty("size.$name").toLong(), manifest.getProperty("sha.$name"))
    }
    fun vadPath(ctx: Context): String = File(TranslationModels.verify(ctx, "silero-vad"), "model.onnx").absolutePath
}
