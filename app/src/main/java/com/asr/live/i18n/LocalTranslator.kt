package com.asr.live.i18n

/** One instance per worker; create/use/close on that worker. */
interface LocalTranslator : AutoCloseable {
    fun translate(text: String): String
}
