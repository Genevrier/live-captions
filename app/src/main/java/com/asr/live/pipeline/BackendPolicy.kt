package com.asr.live.pipeline

object BackendPolicy {
    fun nemotronLanguage(source: String) = when (source) { "nl" -> "nl-NL"; "en" -> "en-US"; "zh" -> "zh-CN"; else -> error("Unsupported Nemotron profile") }
    fun qnnEligible(soc: String, compiled: Boolean) = compiled && soc.equals("SM8750", ignoreCase = true)
}
