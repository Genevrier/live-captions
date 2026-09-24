package com.asr.live.asr

import android.content.Context
import com.asr.live.model.EngineKind
import com.asr.live.model.ModelInfo
import com.asr.live.model.ModelStore

object EngineFactory {
    /**
     * @param language Source language; passed to model-specific runtime prompting.
     * @param whisperTask "transcribe" or "translate" (Whisper-only).
     */
    fun create(
        ctx: Context,
        info: ModelInfo,
        language: String,
        whisperTask: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        threads: Int = 6,
    ): AsrEngine {
        val dir = ModelStore.dir(ctx, info.id)
        return when (info.kind) {
            EngineKind.STREAMING_ZIPFORMER ->
                StreamingEngine(dir, info, onPartial, onFinal, language, threads)
            EngineKind.NEMOTRON ->
                StreamingEngine(dir, info, onPartial, onFinal, language, threads)
            EngineKind.QWEN3 -> QwenVadEngine(dir, ModelStore.vadPath(ctx), threads, onFinal)
            EngineKind.OFFLINE_PARAKEET ->
                OfflineVadEngine(dir, ModelStore.vadPath(ctx), info, onPartial, onFinal)
            EngineKind.WHISPER ->
                WhisperVadEngine(dir, ModelStore.vadPath(ctx), info, language, whisperTask, onPartial, onFinal)
        }
    }
}
