package com.asr.live.i18n

import com.asr.live.pipeline.TranslationQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBenchmarkPlanTest {
    @Test fun backendStageChangesOnlyCpuVersusOpenClForThe18bQ4Model() {
        val runs = TranslationBenchmarkPlan.runs(TranslationBenchmarkStage.BACKEND_18B_Q4,
            selectedOpenCl = false, openClBuilt = true, threads = 4, batch = 256, ubatch = 128)
        assertEquals(2, runs.size)
        assertTrue(runs.all { it.quality == TranslationQuality.HY_Q4 })
        assertEquals(listOf(false, true), runs.map { it.openCl })
        assertTrue(runs.all { it.threads == 4 && it.batch == 256 && it.ubatch == 128 })
    }

    @Test fun modelStageChangesOnlyModelOnTheManuallySelectedBackend() {
        val runs = TranslationBenchmarkPlan.runs(TranslationBenchmarkStage.MODEL_SIZE,
            selectedOpenCl = true, openClBuilt = true, threads = 2, batch = 128, ubatch = 64)
        assertEquals(listOf(TranslationQuality.HY_Q4, TranslationQuality.HY_7B_Q4), runs.map { it.quality })
        assertTrue(runs.all { it.openCl && it.threads == 2 && it.batch == 128 && it.ubatch == 64 })
    }

    @Test fun fastBuildDoesNotExpandIntoThe7bModelAndUnbuiltOpenClIsNotClaimed() {
        val cpuOnly = TranslationBenchmarkPlan.runs(TranslationBenchmarkStage.BACKEND_18B_Q4,
            selectedOpenCl = false, openClBuilt = false, threads = 4, batch = 256, ubatch = 128)
        assertEquals(1, cpuOnly.size)
        assertFalse(cpuOnly.single().openCl)
        assertEquals(TranslationQuality.HY_Q4, cpuOnly.single().quality)
        assertThrows(IllegalArgumentException::class.java) {
            TranslationBenchmarkPlan.runs(TranslationBenchmarkStage.MODEL_SIZE,
                selectedOpenCl = true, openClBuilt = false, threads = 4, batch = 256, ubatch = 128)
        }
    }
}
