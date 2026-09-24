package com.asr.live

import com.asr.live.model.EngineKind
import com.asr.live.model.ModelCatalog
import com.asr.live.service.CaptionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionStateTest {
    @Test fun defaultProfileUsesMultilingualNemotron() {
        assertEquals(EngineKind.NEMOTRON, ModelCatalog.DEFAULT.kind)
        assertTrue(ModelCatalog.DEFAULT.isMultilingual)
        assertTrue(ModelCatalog.WHISPER_SMALL.isMultilingual)
        assertTrue(!ModelCatalog.PARAKEET.isMultilingual)
    }

    @Test fun staleTranslationCannotOverwriteNewerRevision() {
        CaptionState.clear()
        val id = CaptionState.appendSource("Goedemorgen")
        CaptionState.applyTranslation(id, 2, "Good morning")
        CaptionState.applyTranslation(id, 1, "Old result")
        assertEquals("Good morning", CaptionState.lines.value.last().text)
        assertEquals("Goedemorgen", CaptionState.lines.value.last().original)
    }
}
