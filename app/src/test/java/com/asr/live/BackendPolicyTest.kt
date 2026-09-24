package com.asr.live
import com.asr.live.pipeline.BackendPolicy
import com.asr.live.model.ModelCatalog
import org.junit.Assert.*
import org.junit.Test
class BackendPolicyTest {
    @Test fun qnnRequiresExactTargetAndCompiledSupport() {
        assertTrue(BackendPolicy.qnnEligible("SM8750", true))
        assertFalse(BackendPolicy.qnnEligible("SM8650", true))
        assertFalse(BackendPolicy.qnnEligible("SM8750", false))
        assertFalse(BackendPolicy.qnnEligible("unknown", true))
    }
    @Test fun contextsAndCpuGraphsAreSeparatePinnedModels() {
        assertEquals("encoder.bin", ModelCatalog.NEMOTRON_QNN.encoder)
        assertEquals("encoder.int8.onnx", ModelCatalog.NEMOTRON.encoder)
        assertNotEquals(ModelCatalog.NEMOTRON_QNN.id, ModelCatalog.NEMOTRON.id)
        assertEquals(64, ModelCatalog.NEMOTRON_QNN.sha256.length)
    }
}
