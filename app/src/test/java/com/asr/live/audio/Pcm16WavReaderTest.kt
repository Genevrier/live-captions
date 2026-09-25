package com.asr.live.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.ByteArrayInputStream

class Pcm16WavReaderTest {
    @Test fun streamsNormalizedPcm16SamplesWithoutChangingTheirValues() {
        Pcm16WavReader(ByteArrayInputStream(wav(rate = 16_000, channels = 1, samples = shortArrayOf(0, 16384, -16384, -32768)))).use { reader ->
            assertEquals(16_000, reader.sampleRateHz)
            assertEquals(1, reader.channels)
            assertEquals(4L, reader.frameCount)
            val chunk = FloatArray(3)
            assertEquals(3, reader.readSamples(chunk))
            assertEquals(0f, chunk[0], 0f)
            assertEquals(.5f, chunk[1], 0f)
            assertEquals(-.5f, chunk[2], 0f)
            assertEquals(1, reader.readSamples(chunk))
            assertEquals(-1f, chunk[0], 0f)
            assertEquals(0, reader.readSamples(chunk))
        }
    }

    @Test fun rejectsAudioThatWouldChangeTheComparedWaveform() {
        assertThrows(IllegalArgumentException::class.java) {
            Pcm16WavReader(ByteArrayInputStream(wav(rate = 48_000, channels = 1, samples = shortArrayOf(12))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Pcm16WavReader(ByteArrayInputStream(wav(rate = 16_000, channels = 2, samples = shortArrayOf(12))))
        }
    }

    private fun wav(rate: Int, channels: Int, samples: ShortArray): ByteArray {
        val pcm = ByteArrayOutputStream().also { output -> samples.forEach { sample ->
            output.write(sample.toInt() and 0xff)
            output.write((sample.toInt() shr 8) and 0xff)
        } }.toByteArray()
        val format = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeShortLE(1); out.writeShortLE(channels); out.writeIntLE(rate)
            out.writeIntLE(rate * channels * 2); out.writeShortLE(channels * 2); out.writeShortLE(16)
        } }.toByteArray()
        val body = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeBytes("fmt "); out.writeIntLE(format.size); out.write(format)
            out.writeBytes("data"); out.writeIntLE(pcm.size); out.write(pcm)
        } }.toByteArray()
        return ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeBytes("RIFF"); out.writeIntLE(body.size + 4); out.writeBytes("WAVE"); out.write(body)
        } }.toByteArray()
    }

    private fun DataOutputStream.writeShortLE(value: Int) {
        writeByte(value and 0xff); writeByte((value shr 8) and 0xff)
    }
    private fun DataOutputStream.writeIntLE(value: Int) {
        writeByte(value and 0xff); writeByte((value shr 8) and 0xff)
        writeByte((value shr 16) and 0xff); writeByte((value shr 24) and 0xff)
    }
}
