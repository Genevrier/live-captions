package com.asr.live

import com.asr.live.i18n.TranslationPrompt
import com.asr.live.pipeline.Profile
import org.junit.Assert.*
import org.junit.Test

class TranslationPromptTest {
    @Test fun allProfilesUseTheirTargetLanguage() {
        for (profile in Profile.entries) {
            val prompt = TranslationPrompt.build(profile, "source", "")
            assertTrue(prompt.contains(if (profile.target == "fr") "French" else "English"))
            assertTrue(prompt.endsWith("source"))
        }
    }
    @Test fun glossaryUsesDocumentedReferenceFormat() {
        val prompt = TranslationPrompt.build(Profile.CHINESE_ENGLISH, "晶圆", "晶圆 -> wafer\n套刻 -> overlay")
        assertTrue(prompt.contains("晶圆 translates to wafer"))
        assertTrue(prompt.contains("套刻 translates to overlay"))
    }
    @Test fun malformedGlossaryFailsVisibly() {
        assertThrows(IllegalArgumentException::class.java) { TranslationPrompt.build(Profile.CHINESE_ENGLISH, "x", "bad entry") }
        assertThrows(IllegalArgumentException::class.java) { TranslationPrompt.build(Profile.CHINESE_ENGLISH, "x", "a".repeat(2001)) }
    }
}
