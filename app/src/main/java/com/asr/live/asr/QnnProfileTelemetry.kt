package com.asr.live.asr

import android.os.Bundle
import java.util.ArrayDeque
import kotlin.math.ceil

data class QnnProfileStats(
    val initializeTotalNanos: Long = 0,
    val libraryLoadNanos: Long = 0,
    val dspCopyNanos: Long = 0,
    val dspSetupNanos: Long = 0,
    val recognizerPrepareNanos: Long = 0,
    val rpcCalls: Long = 0,
    val clientQueueP50Nanos: Long = 0,
    val clientQueueP95Nanos: Long = 0,
    val rpcP50Nanos: Long = 0,
    val rpcP95Nanos: Long = 0,
    val serviceP50Nanos: Long = 0,
    val serviceP95Nanos: Long = 0,
    val binderAndMarshallingP50Nanos: Long = 0,
    val binderAndMarshallingP95Nanos: Long = 0,
    val audioFeedNanos: Long = 0,
    val combinedDecodeNanos: Long = 0,
    val resultNanos: Long = 0,
    val endpointCheckNanos: Long = 0,
    val graphBreakdown: String = "unavailable",
)

/** Separates client queue/RPC from QNN service work; sherpa exposes decode as a combined graph call. */
internal class QnnProfileTelemetry(private val sampleLimit: Int = 512) {
    private val queueSamples = ArrayDeque<Long>()
    private val rpcSamples = ArrayDeque<Long>()
    private val serviceSamples = ArrayDeque<Long>()
    private val binderSamples = ArrayDeque<Long>()
    private var calls = 0L
    private var initializeTotal = 0L
    private var libraryLoad = 0L
    private var dspCopy = 0L
    private var dspSetup = 0L
    private var recognizerPrepare = 0L
    private var stages = AsrPipelineStats()

    @Synchronized fun recordInitialization(bundle: Bundle) {
        initializeTotal = bundle.getLong("qnn_init_total_ns")
        libraryLoad = bundle.getLong("qnn_library_load_ns")
        dspCopy = bundle.getLong("qnn_dsp_copy_ns")
        dspSetup = bundle.getLong("qnn_dsp_setup_ns")
        recognizerPrepare = bundle.getLong("qnn_recognizer_prepare_ns")
    }

    @Synchronized fun recordDataCall(queueNs: Long, rpcNs: Long, serviceNs: Long, result: Bundle) {
        calls++
        add(queueSamples, queueNs)
        add(rpcSamples, rpcNs)
        add(serviceSamples, serviceNs)
        add(binderSamples, (rpcNs - queueNs - serviceNs).coerceAtLeast(0))
        stages = AsrPipelineStats(
            audioFeedNanos = result.getLong("asr_audio_feed_ns"),
            resultNanos = result.getLong("asr_result_ns"),
            endpointCheckNanos = result.getLong("asr_endpoint_check_ns"),
        )
    }

    @Synchronized fun snapshot(decodeNanos: Long = 0): QnnProfileStats = QnnProfileStats(
        initializeTotalNanos = initializeTotal,
        libraryLoadNanos = libraryLoad,
        dspCopyNanos = dspCopy,
        dspSetupNanos = dspSetup,
        recognizerPrepareNanos = recognizerPrepare,
        rpcCalls = calls,
        clientQueueP50Nanos = percentile(queueSamples, .50),
        clientQueueP95Nanos = percentile(queueSamples, .95),
        rpcP50Nanos = percentile(rpcSamples, .50),
        rpcP95Nanos = percentile(rpcSamples, .95),
        serviceP50Nanos = percentile(serviceSamples, .50),
        serviceP95Nanos = percentile(serviceSamples, .95),
        binderAndMarshallingP50Nanos = percentile(binderSamples, .50),
        binderAndMarshallingP95Nanos = percentile(binderSamples, .95),
        audioFeedNanos = stages.audioFeedNanos,
        combinedDecodeNanos = decodeNanos,
        resultNanos = stages.resultNanos,
        endpointCheckNanos = stages.endpointCheckNanos,
    )

    private fun add(samples: ArrayDeque<Long>, value: Long) {
        if (samples.size == sampleLimit) samples.removeFirst()
        samples.addLast(value.coerceAtLeast(0))
    }

    private fun percentile(samples: ArrayDeque<Long>, percentile: Double): Long {
        val sorted = samples.sorted()
        val index = (ceil(sorted.size * percentile).toInt() - 1).coerceIn(0, (sorted.size - 1).coerceAtLeast(0))
        return sorted.getOrNull(index) ?: 0L
    }
}
