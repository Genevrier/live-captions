package com.asr.live

import com.asr.live.model.VerifiedFiles
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VerifiedFilesTest {
    private val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    @Test fun validDownloadIsPublishedAtomically() {
        val root = Files.createTempDirectory("models").toFile()
        try {
            val target = File(root,"nested/model")
            VerifiedFiles.writeVerified("abc".byteInputStream(), target, 3, sha)
            assertEquals("abc", target.readText()); assertFalse(File(target.parentFile,"model.part").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun corruptAndInterruptedDownloadsPreserveExistingModel() {
        val root = Files.createTempDirectory("models").toFile()
        try {
            val target = File(root,"model").apply { writeText("existing") }
            for (bad in listOf("ab", "abd")) {
                assertThrows(IllegalStateException::class.java) { VerifiedFiles.writeVerified(bad.byteInputStream(), target, 3, sha) }
                assertEquals("existing", target.readText()); assertFalse(File(root,"model.part").exists())
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationCannotPublishPartFile() {
        val root = Files.createTempDirectory("models").toFile()
        try {
            val target = File(root,"model")
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                VerifiedFiles.writeVerified("abc".byteInputStream(), target, 3, sha, { true })
            }
            assertFalse(target.exists()); assertFalse(File(root,"model.part").exists())
        } finally { root.deleteRecursively() }
    }
}
