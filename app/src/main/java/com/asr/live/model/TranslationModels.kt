package com.asr.live.model

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Properties

data class ModelFile(val path: String, val url: String, val size: Long, val sha256: String)
data class ModelBundle(val id: String, val label: String, val files: List<ModelFile>) {
    val size get() = files.sumOf { it.size }
}
object TranslationModels {
    fun bundle(ctx: Context, id: String): ModelBundle {
        val bundles = JSONObject(ctx.assets.open("translation-models.json").bufferedReader().use { it.readText() }).getJSONArray("bundles")
        for (i in 0 until bundles.length()) {
            val b = bundles.getJSONObject(i)
            if (b.getString("id") != id) continue
            val f = b.getJSONArray("files")
            return ModelBundle(id, b.getString("label"), (0 until f.length()).map { n ->
                val item = f.getJSONObject(n)
                ModelFile(item.getString("path"), item.getString("url"), item.getLong("size"), item.getString("sha256"))
            })
        }
        error("Unknown translation model: $id")
    }
    fun present(ctx: Context, id: String): Boolean = runCatching {
        val b = bundle(ctx, id); val d = ModelStore.dir(ctx, id)
        val p = Properties().apply { File(d, "verified.properties").inputStream().use { load(it) } }
        b.files.all { File(d, it.path).length() == it.size && p.getProperty(it.path) == it.sha256 }
    }.getOrDefault(false)
    fun verify(ctx: Context, id: String): File {
        check(present(ctx, id)) { "Download required translation models first" }
        val d = ModelStore.dir(ctx, id)
        bundle(ctx, id).files.forEach { VerifiedFiles.check(File(d, it.path), it.size, it.sha256) }
        return d
    }
}
