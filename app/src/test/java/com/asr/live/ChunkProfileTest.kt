package com.asr.live

import com.asr.live.model.*
import com.asr.live.pipeline.SessionConfig
import org.junit.Assert.*
import org.junit.Test

class ChunkProfileTest {
    @Test fun cpuChunksAreDistinctPinnedDownloadsWithNoRuntimeScalar() {
        val models = ModelCatalog.chunkProfiles(false)
        assertEquals(listOf(160, 320, 560, 1120), models.map { it.chunkMs })
        assertEquals(4, models.map { it.id }.toSet().size)
        assertEquals(4, models.map { it.url }.toSet().size)
        assertEquals(4, models.map { it.sha256 }.toSet().size)
        models.forEach {
            assertEquals(64, it.sha256.length)
            assertTrue(it.archiveBytes > 0)
            assertTrue(it.url.contains("-${it.chunkMs}ms-int8-"))
            assertEquals(it, ModelCatalog.byId(it.id))
            assertTrue(it.supports("nl"))
        }
    }
    @Test fun qnnCannotSelectUntestedChunksOrCpuGraphsAsContexts() {
        assertEquals(listOf(560), ModelCatalog.chunkProfiles(true).map { it.chunkMs })
        assertEquals(560, ModelCatalog.NEMOTRON_QNN.chunkMs)
        assertTrue(ModelCatalog.NEMOTRON_QNN.encoder.endsWith(".bin"))
        assertFalse(ModelCatalog.NEMOTRON_PROFILES.contains(ModelCatalog.NEMOTRON_QNN))
    }
    @Test fun initialDutchProfileRetains560msCpuDefault() {
        val config = SessionConfig()
        assertEquals(560, ModelCatalog.byId(config.modelId)?.chunkMs)
        assertFalse(config.qnn)
    }
}
