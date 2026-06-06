package com.asr.live.i18n

/** A language usable both as a Whisper source and an ML Kit translation target. */
data class Lang(val code: String, val name: String)

object Languages {
    /** Sentinel: no translation. */
    const val OFF = "off"

    /** Codes are ISO-639-1, accepted by both Whisper (`language`) and ML Kit (BCP-47 tag). */
    val list = listOf(
        Lang("en", "English"),
        Lang("tr", "Turkish"),
        Lang("de", "German"),
        Lang("es", "Spanish"),
        Lang("fr", "French"),
        Lang("it", "Italian"),
        Lang("pt", "Portuguese"),
        Lang("nl", "Dutch"),
        Lang("ru", "Russian"),
        Lang("uk", "Ukrainian"),
        Lang("pl", "Polish"),
        Lang("ar", "Arabic"),
        Lang("zh", "Chinese"),
        Lang("ja", "Japanese"),
        Lang("ko", "Korean"),
        Lang("hi", "Hindi"),
    )

    fun name(code: String): String =
        if (code == OFF) "Off" else list.firstOrNull { it.code == code }?.name ?: code
}
