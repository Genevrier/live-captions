package com.asr.live.asr

import android.content.Context
import com.asr.live.model.EngineKind
import com.asr.live.model.ModelInfo
import com.asr.live.model.ModelStore

object EngineFactory {
    /**
     * @param language Whisper source-language hint (ISO code) or "" for auto; ignored by English engines.
     * @param whisperTask "transcribe" or "translate" (Whisper-only).
     */
    fun create(
        ctx: Context,
        info: ModelInfo,
        language: String,
        whisperTask: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
    ): AsrEngine {
        val dir = ModelStore.dir(ctx, info.id)
        return when (info.kind) {
            EngineKind.STREAMING_ZIPFORMER ->
                StreamingEngine(dir, info, onPartial, onFinal)
            EngineKind.NEMOTRON ->
                StreamingEngine(dir, info, onPartial, onFinal, language)
            EngineKind.OFFLINE_PARAKEET ->
                OfflineVadEngine(dir, ModelStore.vadPath(ctx), info, onPartial, onFinal)
            EngineKind.WHISPER ->
                WhisperVadEngine(dir, ModelStore.vadPath(ctx), info, language, whisperTask, onPartial, onFinal)
        }
    }
}
