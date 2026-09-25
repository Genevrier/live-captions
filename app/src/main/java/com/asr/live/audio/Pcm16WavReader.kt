package com.asr.live.audio

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream

/** Strict streaming reader for normalized 16 kHz mono PCM16 WAV benchmark inputs. */
class Pcm16WavReader(input: InputStream) : Closeable {
    private val stream = BufferedInputStream(input)
    val sampleRateHz: Int
    val channels: Int
    val bitsPerSample: Int
    val frameCount: Long
    private var dataBytesRemaining: Long

    init {
        val riff = readExact(12)
        require(ascii(riff, 0, 4) == "RIFF" && ascii(riff, 8, 4) == "WAVE") { "Expected a RIFF/WAVE file" }
        var parsedRate: Int? = null
        var parsedChannels: Int? = null
        var parsedBits: Int? = null
        var foundData: Long? = null
        while (foundData == null) {
            val chunkHeader = readExact(8)
            val chunkName = ascii(chunkHeader, 0, 4)
            val chunkSize = unsignedIntLe(chunkHeader, 4)
            require(chunkSize <= Int.MAX_VALUE) { "WAV chunk is too large" }
            when (chunkName) {
                "fmt " -> {
                    require(chunkSize >= 16) { "WAV format chunk is truncated" }
                    val format = readExact(16)
                    val encoding = unsignedShortLe(format, 0)
                    parsedChannels = unsignedShortLe(format, 2)
                    parsedRate = unsignedIntLe(format, 4).toInt()
                    parsedBits = unsignedShortLe(format, 14)
                    require(encoding == 1) { "Expected uncompressed PCM WAV" }
                    skipExact(chunkSize - 16)
                }
                "data" -> {
                    require(parsedRate != null) { "WAV format chunk must precede audio data" }
                    foundData = chunkSize
                }
                else -> skipExact(chunkSize)
            }
            if (chunkSize % 2L != 0L && chunkName != "data") skipExact(1)
        }
        sampleRateHz = checkNotNull(parsedRate)
        channels = checkNotNull(parsedChannels)
        bitsPerSample = checkNotNull(parsedBits)
        require(sampleRateHz == AudioCapture.SAMPLE_RATE && channels == 1 && bitsPerSample == 16) {
            "Expected 16 kHz mono PCM16 WAV, found ${sampleRateHz}Hz/${channels}ch/${bitsPerSample}bit"
        }
        dataBytesRemaining = checkNotNull(foundData)
        require(dataBytesRemaining % 2L == 0L) { "PCM16 data has an incomplete sample" }
        frameCount = dataBytesRemaining / 2
        require(frameCount > 0) { "WAV audio data is empty" }
    }

    fun readSamples(destination: FloatArray): Int {
        if (dataBytesRemaining == 0L) return 0
        val count = minOf(destination.size.toLong(), dataBytesRemaining / 2).toInt()
        val bytes = readExact(count * 2)
        for (index in 0 until count) {
            val low = bytes[index * 2].toInt() and 0xff
            val high = bytes[index * 2 + 1].toInt()
            destination[index] = ((low or (high shl 8)).toShort().toInt() / 32768f)
        }
        dataBytesRemaining -= count * 2L
        return count
    }

    private fun readExact(count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = stream.read(result, offset, count - offset)
            if (read < 0) throw EOFException("Truncated WAV file")
            offset += read
        }
        return result
    }

    private fun skipExact(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else {
                if (stream.read() < 0) throw EOFException("Truncated WAV chunk")
                remaining--
            }
        }
    }

    override fun close() = stream.close()

    private fun ascii(bytes: ByteArray, start: Int, length: Int) =
        String(bytes, start, length, Charsets.US_ASCII)

    private fun unsignedShortLe(bytes: ByteArray, offset: Int) =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun unsignedIntLe(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
}
