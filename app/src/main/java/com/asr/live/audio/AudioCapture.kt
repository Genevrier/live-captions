package com.asr.live.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class AudioCapture(
    private val context: Context,
    private val onChunk: (FloatArray) -> Unit,
    private val onStarted: () -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 1600
        const val CHUNK_DURATION_MS = CHUNK_SAMPLES * 1000L / SAMPLE_RATE
    }
    private val active = AtomicBoolean(true)
    private var worker: Thread? = null
    fun start() { worker = Thread(::loop, "microphone").also { it.start() } }
    fun cancel() { active.set(false); worker?.interrupt() }
    fun join() { worker?.join() }
    @SuppressLint("MissingPermission")
    private fun loop() {
        var recorder: AudioRecord? = null
        try {
            if (!active.get()) return
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "16 kHz microphone capture unavailable" }
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 32000))
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            val manager = context.getSystemService(AudioManager::class.java)
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let {
                    check(recorder.setPreferredDevice(it)) { "Could not select built-in microphone" }
                }
            if (!active.get()) return
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            onStarted()
            val pcm = ShortArray(CHUNK_SAMPLES)
            var filled = 0
            while (active.get()) {
                val count = recorder.read(pcm, filled, pcm.size - filled, AudioRecord.READ_NON_BLOCKING)
                if (count < 0) throw IOException("Microphone read failed: $count")
                if (count == 0) Thread.sleep(10)
                else {
                    filled += count
                    if (filled == pcm.size) { onChunk(FloatArray(filled) { pcm[it] / 32768f }); filled = 0 }
                }
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) { if (active.get()) onError(t) }
        finally { recorder?.let { runCatching { it.stop() }; it.release() } }
    }
}
