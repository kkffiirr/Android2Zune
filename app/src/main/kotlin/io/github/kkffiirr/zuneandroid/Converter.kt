package io.github.kkffiirr.zuneandroid

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts any audio file this phone can decode (FLAC, Ogg Vorbis, Opus, WAV, ADTS AAC, ...) to MP3 with LAME.
 * Android decodes to PCM (MediaExtractor + MediaCodec); LAME resamples and encodes.
 * MP3 because the Zune plays it and the upload sequence is verified with it; WMA Lossless would be the lossless match
 * but no encoder for it exists on Android.
 */
object Converter {
    private const val EOS = MediaCodec.BUFFER_FLAG_END_OF_STREAM

    /** Returns null on success, otherwise a short error message. [onProgress] gets 0..100. */
    fun toMp3(src: File, dst: File, vbrQuality: Int, onProgress: (Int) -> Unit): String? {
        val ex = MediaExtractor()
        try { ex.setDataSource(src.path) } catch (e: Exception) { ex.release(); return "cannot open file (${e.message})" }
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { ex.selectTrack(i); fmt = f; break }
        }
        if (fmt == null) { ex.release(); return "no audio track" }
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L

        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var enc = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT

        val raw = mime == "audio/raw"                    // WAV: already PCM, nothing to decode
        var codec: MediaCodec? = null
        if (!raw) {
            try {
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(fmt, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                codec?.release(); ex.release()
                return "this phone cannot decode $mime"
            }
        }

        var handle = 0L
        var lastPct = -1
        var failure: String? = null

        fun feed(buf: ByteBuffer, timeUs: Long) {
            if (failure != null) return
            if (handle == 0L) {
                handle = Native.mp3Open(rate, if (ch >= 2) 2 else 1, vbrQuality, dst.path)
                if (handle == 0L) { failure = "could not start the MP3 encoder"; return }
            }
            val pcm = toShorts(buf, enc, ch)
            val outCh = if (ch >= 2) 2 else 1
            val rc = Native.mp3Write(handle, pcm, pcm.size / outCh)
            if (rc != 0) failure = "MP3 encoder error $rc"
            if (durationUs > 0) {
                val pct = minOf(99L, timeUs * 100 / durationUs).toInt()
                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
            }
        }

        try {
            if (raw) {
                val buf = ByteBuffer.allocate(1 shl 16)
                while (failure == null) {
                    buf.clear()
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    buf.limit(n); buf.position(0)
                    feed(buf, ex.sampleTime)
                    ex.advance()
                }
            } else {
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone && failure == null) {
                    if (!inputDone) {
                        val ii = codec!!.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val ib = codec.getInputBuffer(ii)!!
                            val n = ex.readSampleData(ib, 0)
                            if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, EOS); inputDone = true }
                            else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val oi = codec!!.dequeueOutputBuffer(info, 10_000)
                    if (oi >= 0) {
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(oi)!!
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            feed(ob, info.presentationTimeUs)
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and EOS != 0) outputDone = true
                    } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        enc = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    }
                }
            }
        } catch (e: Exception) {
            failure = "conversion failed (${e.message})"
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release(); ex.release()
            if (handle != 0L) Native.mp3Close(handle)
        }
        if (failure == null && handle == 0L) failure = "no audio decoded"
        if (failure != null) { dst.delete(); return failure }
        onProgress(100)
        return null
    }

    /** Interleaved PCM in any common encoding -> 16-bit samples, mono or stereo (extra channels beyond two are dropped). */
    private fun toShorts(buf: ByteBuffer, enc: Int, ch: Int): ShortArray {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val bytesPer = when (enc) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        val frames = buf.remaining() / (bytesPer * ch)
        val outCh = if (ch >= 2) 2 else 1
        val out = ShortArray(frames * outCh)
        val base = buf.position()
        for (f in 0 until frames) {
            for (c in 0 until outCh) {
                val p = base + (f * ch + c) * bytesPer
                val v: Int = when (enc) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((buf.get(p).toInt() and 0xFF) - 128) shl 8
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> (buf.get(p + 2).toInt() shl 8) or (buf.get(p + 1).toInt() and 0xFF)
                    AudioFormat.ENCODING_PCM_32BIT -> buf.getInt(p) shr 16
                    AudioFormat.ENCODING_PCM_FLOAT -> (buf.getFloat(p).coerceIn(-1f, 1f) * 32767f).toInt()
                    else -> buf.getShort(p).toInt()
                }
                out[f * outCh + c] = v.toShort()
            }
        }
        return out
    }
}
