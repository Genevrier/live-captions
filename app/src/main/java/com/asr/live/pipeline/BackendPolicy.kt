package com.asr.live.pipeline

object BackendPolicy {
    fun qnnEligible(soc: String, compiled: Boolean) = compiled && soc.equals("SM8750", ignoreCase = true)
}
