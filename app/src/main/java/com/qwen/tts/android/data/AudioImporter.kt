package com.qwen.tts.android.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

data class ImportedAudio(val file: File, val durationMillis: Long)

object AudioImporter {
    private const val TARGET_RATE = 24000

    fun importToWav(context: Context, uri: Uri, output: File): ImportedAudio {
        val extractor = MediaExtractor()
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: error("Could not open selected audio")
        afd.use { extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length) }

        var track = -1
        var format: MediaFormat? = null
        for (index in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(index)
            if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = index
                format = candidate
                break
            }
        }
        require(track >= 0 && format != null) { "No audio track found" }
        extractor.selectTrack(track)

        val inputFormat = format
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Unknown audio format")
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
            inputFormat.getLong(MediaFormat.KEY_DURATION)
        } else 0L

        val pcm = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex) ?: error("No decoder input buffer")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10000)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = decoder.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (outputIndex >= 0) {
                    if (info.size > 0) {
                        val buffer = decoder.getOutputBuffer(outputIndex) ?: error("No decoder output buffer")
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buffer.get(bytes)
                        pcm.write(bytes)
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            extractor.release()
        }

        val mono = toMono(pcm.toByteArray(), channels)
        val converted = resample(mono, rate, TARGET_RATE)
        output.parentFile?.mkdirs()
        output.outputStream().use { writeWav(it, converted, TARGET_RATE) }

        val calculatedMs = (converted.size / 2L) * 1000L / TARGET_RATE
        return ImportedAudio(output, if (durationUs > 0L) durationUs / 1000L else calculatedMs)
    }

    private fun toMono(source: ByteArray, channels: Int): ShortArray {
        val count = channels.coerceAtLeast(1)
        val frames = (source.size / 2) / count
        val result = ShortArray(frames)
        var offset = 0
        for (frame in 0 until frames) {
            var sum = 0
            repeat(count) {
                val low = source[offset].toInt() and 0xff
                val high = source[offset + 1].toInt()
                offset += 2
                sum += ((high shl 8) or low).toShort().toInt()
            }
            result[frame] = (sum / count).toShort()
        }
        return result
    }

    private fun resample(source: ShortArray, from: Int, to: Int): ByteArray {
        require(source.isNotEmpty()) { "Decoded audio is empty" }
        require(from > 0) { "Invalid sample rate" }
        val size = (source.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val result = ByteArray(size * 2)
        for (index in 0 until size) {
            val position = index.toDouble() * from.toDouble() / to.toDouble()
            val left = position.toInt().coerceIn(0, source.lastIndex)
            val right = (left + 1).coerceAtMost(source.lastIndex)
            val fraction = position - left.toDouble()
            val value = (source[left] * (1.0 - fraction) + source[right] * fraction)
                .toInt().coerceIn(-32768, 32767)
            result[index * 2] = (value and 0xff).toByte()
            result[index * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        return result
    }

    private fun writeWav(out: OutputStream, pcm: ByteArray, rate: Int) {
        fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
        fun intLe(value: Int) {
            out.write(value and 0xff); out.write((value shr 8) and 0xff)
            out.write((value shr 16) and 0xff); out.write((value shr 24) and 0xff)
        }
        fun shortLe(value: Int) {
            out.write(value and 0xff); out.write((value shr 8) and 0xff)
        }
        ascii("RIFF"); intLe(36 + pcm.size); ascii("WAVE")
        ascii("fmt "); intLe(16); shortLe(1); shortLe(1)
        intLe(rate); intLe(rate * 2); shortLe(2); shortLe(16)
        ascii("data"); intLe(pcm.size); out.write(pcm)
    }
}
