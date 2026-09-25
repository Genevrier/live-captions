package com.asr.live.asr

/** Callback records cross the QNN binder as distinct events; an empty partial cannot erase a final. */
internal sealed interface RecognitionEvent {
    val text: String
    data class Partial(override val text: String) : RecognitionEvent
    data class Final(override val text: String) : RecognitionEvent
}

internal class RecognitionEventBuffer {
    private val events = mutableListOf<RecognitionEvent>()
    fun partial(text: String) { events += RecognitionEvent.Partial(text) }
    fun final(text: String) { events += RecognitionEvent.Final(text) }
    fun snapshot(): List<RecognitionEvent> = events.toList()
}

internal fun dispatchRecognitionEvents(
    events: List<RecognitionEvent>,
    onPartial: (String) -> Unit,
    onFinal: (String) -> Unit,
) {
    events.forEach { event ->
        when (event) {
            is RecognitionEvent.Partial -> onPartial(event.text)
            is RecognitionEvent.Final -> onFinal(event.text)
        }
    }
}
