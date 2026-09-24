package com.asr.live.model

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object VerifiedFiles {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun check(file: File, bytes: Long, sha: String) {
        require(sha.matches(Regex("[a-f0-9]{64}"))) { "Missing pinned SHA-256" }
        check(file.isFile && (bytes <= 0 || file.length() == bytes)) { "Model size mismatch" }
        check(sha256(file) == sha) { "Model SHA-256 mismatch" }
    }
    fun writeVerified(input: InputStream, target: File, bytes: Long, sha: String,
        cancelled: () -> Boolean = { false }, progress: (Long) -> Unit = {}) {
        target.parentFile!!.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        try {
            part.outputStream().use { output ->
                val buffer = ByteArray(65536); var read = 0L
                while (true) {
                    if (cancelled()) throw java.util.concurrent.CancellationException("Download cancelled")
                    val n = input.read(buffer); if (n < 0) break
                    output.write(buffer, 0, n); read += n; progress(read)
                }
                output.fd.sync()
            }
            check(part, bytes, sha)
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { part.delete() }
    }
}
