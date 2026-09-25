package com.asr.live.i18n

import com.asr.live.pipeline.TranslationQuality

enum class TranslationBenchmarkStage {
    BACKEND_18B_Q4,
    MODEL_SIZE,
}

data class TranslationBenchmarkRun(
    val quality: TranslationQuality,
    val openCl: Boolean,
    val threads: Int,
    val batch: Int,
    val ubatch: Int,
)

/** Small, explicit comparisons: one variable changes within each stage. */
object TranslationBenchmarkPlan {
    fun runs(
        stage: TranslationBenchmarkStage,
        selectedOpenCl: Boolean,
        openClBuilt: Boolean,
        threads: Int,
        batch: Int,
        ubatch: Int,
    ): List<TranslationBenchmarkRun> {
        require(threads > 0 && batch > 0 && ubatch > 0)
        if (stage == TranslationBenchmarkStage.MODEL_SIZE && selectedOpenCl)
            require(openClBuilt) { "OpenCL is not present in this build" }
        val qualitiesAndBackends = when (stage) {
            TranslationBenchmarkStage.BACKEND_18B_Q4 -> buildList {
                add(TranslationQuality.HY_Q4 to false)
                if (openClBuilt) add(TranslationQuality.HY_Q4 to true)
            }
            TranslationBenchmarkStage.MODEL_SIZE -> listOf(
                TranslationQuality.HY_Q4 to selectedOpenCl,
                TranslationQuality.HY_7B_Q4 to selectedOpenCl,
            )
        }
        return qualitiesAndBackends.map { (quality, openCl) ->
            TranslationBenchmarkRun(quality, openCl, threads, batch, ubatch)
        }
    }
}
