package com.asr.live.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a model archive on first use and extracts just the files we need.
 * After this completes the model lives in filesDir and the app works fully offline.
 */
object ModelRepository {

    enum class Phase { DOWNLOAD, EXTRACT }

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Running(val id: String, val phase: Phase, val pct: Int) : DownloadState
        data class Failed(val id: String, val message: String) : DownloadState
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    @Volatile
    private var inFlight = false

    suspend fun download(ctx: Context, info: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        if (inFlight) return@withContext false
        inFlight = true
        val dir = ModelStore.dir(ctx, info.id)
        val tmp = File(ctx.cacheDir, "${info.id}.tar.bz2")
        try {
            _state.value = DownloadState.Running(info.id, Phase.DOWNLOAD, 0)
            downloadTo(info.url, tmp) { pct ->
                _state.value = DownloadState.Running(info.id, Phase.DOWNLOAD, pct)
            }
            if (info.sha256.isNotEmpty() && sha256(tmp) != info.sha256) error("model archive checksum mismatch")

            _state.value = DownloadState.Running(info.id, Phase.EXTRACT, 100)
            val staging = File(ctx.cacheDir, "${info.id}.extract")
            staging.deleteRecursively()
            staging.mkdirs()
            extract(tmp, staging, info.requiredFiles.toSet())
            if (!info.requiredFiles.all { File(staging, it).length() > 0 }) error("model archive incomplete")
            dir.deleteRecursively()
            if (!staging.renameTo(dir)) error("could not install model")

            if (!ModelStore.isPresent(ctx, info)) error("archive did not contain the expected model files")
            _state.value = DownloadState.Idle
            true
        } catch (t: Throwable) {
            dir.deleteRecursively()
            _state.value = DownloadState.Failed(info.id, t.message ?: t.javaClass.simpleName)
            false
        } finally {
            tmp.delete()
            inFlight = false
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private inline fun downloadTo(urlStr: String, dest: File, onProgress: (Int) -> Unit) {
        val conn = openFollowingRedirects(urlStr)
        try {
            val total = conn.contentLengthLong
            var read = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            if (total > 0 && read != total) error("incomplete download: $read of $total bytes")
        } finally {
            conn.disconnect()
        }
    }

    /** GitHub release assets redirect to a CDN; follow up to 5 hops across hosts/protocols. */
    private fun openFollowingRedirects(urlStr: String): HttpURLConnection {
        var url = URL(urlStr)
        var hops = 0
        while (true) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "asr-live/1.0")
            }
            when (val code = c.responseCode) {
                in 200..299 -> return c
                301, 302, 303, 307, 308 -> {
                    if (++hops > 5) error("too many redirects")
                    val loc = c.getHeaderField("Location") ?: error("redirect without Location")
                    url = URL(url, loc)
                    c.disconnect()
                }
                else -> error("HTTP $code")
            }
        }
    }

    private fun extract(archive: File, dir: File, wanted: Set<String>) {
        // `true` = decode ALL concatenated bz2 streams. Large model archives are compressed
        // with pbzip2 (multi-stream); without this only the first stream is read, which
        // silently truncates big files like the 652 MB Parakeet encoder.
        BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()), true).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val leaf = entry.name.substringAfterLast('/')
                    if (leaf in wanted) {
                        File(dir, leaf).outputStream().use { tar.copyTo(it) }
                    }
                }
            }
        }
    }
}
