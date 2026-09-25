package com.asr.live.asr

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QnnProfileTelemetryTest {
    @Test fun reportsPreparationAndSeparatesRpcServiceAndBinderCosts() {
        val telemetry = QnnProfileTelemetry()
        telemetry.recordInitialization(Bundle().apply {
            putLong("qnn_init_total_ns", 100)
            putLong("qnn_library_load_ns", 10)
            putLong("qnn_dsp_copy_ns", 20)
            putLong("qnn_dsp_setup_ns", 30)
            putLong("qnn_recognizer_prepare_ns", 40)
        })
        fun response(serviceNs: Long) = Bundle().apply {
            putLong("qnn_service_ns", serviceNs)
            putLong("asr_audio_feed_ns", 7)
            putLong("asr_result_ns", 3)
            putLong("asr_endpoint_check_ns", 2)
        }
        telemetry.recordDataCall(queueNs = 1, rpcNs = 10, serviceNs = 6, result = response(6))
        telemetry.recordDataCall(queueNs = 3, rpcNs = 20, serviceNs = 12, result = response(12))

        val stats = telemetry.snapshot(decodeNanos = 50)
        assertEquals(100L, stats.initializeTotalNanos)
        assertEquals(40L, stats.recognizerPrepareNanos)
        assertEquals(2L, stats.rpcCalls)
        assertEquals(1L, stats.clientQueueP50Nanos)
        assertEquals(3L, stats.clientQueueP95Nanos)
        assertEquals(10L, stats.rpcP50Nanos)
        assertEquals(20L, stats.rpcP95Nanos)
        assertEquals(6L, stats.serviceP50Nanos)
        assertEquals(12L, stats.serviceP95Nanos)
        assertEquals(3L, stats.binderAndMarshallingP50Nanos)
        assertEquals(5L, stats.binderAndMarshallingP95Nanos)
        assertEquals(50L, stats.combinedDecodeNanos)
        assertEquals(7L, stats.audioFeedNanos)
    }
}
