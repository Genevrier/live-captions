package com.asr.live.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException

/**
 * Continuously records 16 kHz mono audio and hands ~100 ms float chunks to [onChunk]
 * on a dedicated thread. The caller must hold RECORD_AUDIO before calling [start].
 */
class AudioCapture(private val onChunk: (FloatArray) -> Unit) {

    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "asr-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) throw IOException("AudioRecord unsupported on this device")
        val bufBytes = maxOf(minBuf, SAMPLE_RATE * 2) // >= 1 s of headroom

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, CHANNEL, ENCODING, bufBytes,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("AudioRecord failed to initialize")
        }

        val chunk = ShortArray(CHUNK_SAMPLES)
        try {
            recorder.startRecording()
            while (running) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n > 0) {
                    val out = FloatArray(n) { chunk[it] / 32768f }
                    onChunk(out)
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SAMPLES = 1600 // 100 ms
    }
}
