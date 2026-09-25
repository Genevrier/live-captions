package com.asr.live.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionEventsTest {
    @Test fun finalThenEmptyPartialStaySeparateAndKeepTheirOrder() {
        val buffer = RecognitionEventBuffer()
        buffer.final("laatste resultaat")
        buffer.partial("")
        val events = buffer.snapshot()
        assertEquals(RecognitionEvent.Final("laatste resultaat"), events[0])
        assertEquals(RecognitionEvent.Partial(""), events[1])

        var finalText = ""
        var partialText = "vorige partiële tekst"
        dispatchRecognitionEvents(events,
            onPartial = { partialText = it },
            onFinal = { finalText = it })
        assertEquals("", partialText)
        assertEquals("laatste resultaat", finalText)
        assertTrue(events[0] is RecognitionEvent.Final)
    }
}
