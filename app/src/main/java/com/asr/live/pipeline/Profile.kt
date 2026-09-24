package com.asr.live.pipeline

enum class Profile(val source: String, val target: String, val label: String) {
    DUTCH_ENGLISH("nl", "en", "Dutch → English"),
    CHINESE_ENGLISH("zh", "en", "Chinese → English"),
    ENGLISH_FRENCH("en", "fr", "English → French");

    val correctionSupported get() = source in setOf("nl", "en")
    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.name == id } ?: DUTCH_ENGLISH
    }
}

enum class TranslationQuality(val label: String) {
    ML_KIT("ML Kit · local fast translation")
}

data class SessionConfig(
    val profile: Profile = Profile.DUTCH_ENGLISH,
    val modelId: String = "nemotron-3.5-560ms-int8",
    val threads: Int = 6,
    val quality: TranslationQuality = TranslationQuality.ML_KIT,
    val correction: Boolean = false,
    val glossary: String = "",
) {
    init { require(threads in 1..8) }
}
