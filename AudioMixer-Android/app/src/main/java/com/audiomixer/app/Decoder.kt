package com.audiomixer.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.ReturnCode
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteOrder

/**
 * Turns any audio file the user picks into 44100 Hz / stereo / 16-bit PCM.
 *
 * Two engines, tried in order:
 *  1. Android's own MediaExtractor + MediaCodec  — fast hardware/software decode, covers
 *     MP3, WAV, M4A/AAC, FLAC, OGG/Opus, AMR, 3GP, MP4 audio and more.
 *  2. FFmpeg (ffmpeg-kit, audio build)           — fallback for everything the platform
 *     does not know, e.g. WMA, APE, AIFF, DSD, odd containers, broken headers.
 * Because of the fallback no file is refused just because of its extension.
 */
object Decoder {

    class Result(val file: File, val frames: Long)

    fun decode(context: Context, uri: Uri, out: File): Result {
        if (out.exists()) out.delete()
        if (out.parentFile?.exists() == false) out.parentFile?.mkdirs()

        val native = try {
            decodeWithPlatform(context, uri, out)
        } catch (_: Throwable) {
            false
        }
        if (native && out.length() > 0L) return Result(out, out.length() / Fmt.BYTES_PER_FRAME)

        if (out.exists()) out.delete()
        val ff = try {
            decodeWithFfmpeg(context, uri, out)
        } catch (_: Throwable) {
            false
        }
        if (ff && out.length() > 0L) return Result(out, out.length() / Fmt.BYTES_PER_FRAME)

        if (out.exists()) out.delete()
        throw IOException("This file could not be decoded")
    }

    /** Engine 1: the platform decoders. */
    private fun decodeWithPlatform(context: Context, uri: Uri, out: File): Boolean {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return false
        pfd.use { p ->
            val ex = MediaExtractor()
            try {
                ex.setDataSource(p.fileDescriptor)
                var index = -1
                var format: MediaFormat? = null
                var i = 0
                while (i < ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME)
                    if (mime != null && mime.startsWith("audio/")) {
                        index = i
                        format = f
                        break
                    }
                    i++
                }
                val fmt = format ?: return false
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return false
                var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else Fmt.SAMPLE_RATE
                var chans = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else Fmt.CHANNELS
                ex.selectTrack(index)

                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(fmt, null, null, 0)
                codec.start()

                val sink = PcmSink(BufferedOutputStream(FileOutputStream(out), 1 shl 16), rate, chans)
                var isFloat = false
                var sawOutput = false
                var inputDone = false
                var outputDone = false
                var idle = 0
                val info = MediaCodec.BufferInfo()

                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)
                            if (buf == null) {
                                inputDone = true
                            } else {
                                val size = ex.readSampleData(buf, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                                    ex.advance()
                                }
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10000)
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (inputDone && ++idle > 300) break
                    } else idle = 0
                    if (outIdx >= 0) {
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(outIdx)
                            if (ob != null) {
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                ob.order(ByteOrder.nativeOrder())
                                if (isFloat) {
                                    val fb = ob.asFloatBuffer()
                                    val arr = FloatArray(fb.remaining())
                                    fb.get(arr)
                                    sink.writeFloat(arr, arr.size / chans)
                                } else {
                                    val sb = ob.asShortBuffer()
                                    val arr = ShortArray(sb.remaining())
                                    sb.get(arr)
                                    sink.writeShort(arr, arr.size / chans)
                                }
                                sawOutput = true
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val nf = codec.outputFormat
                        if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) chans = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = nf.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        if (chans < 1) chans = Fmt.CHANNELS
                        sink.reset(rate, chans)
                    }
                }
                sink.finish()
                try {
                    codec.stop()
                } catch (_: Throwable) {
                }
                codec.release()
                return sawOutput
            } finally {
                try {
                    ex.release()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** Engine 2: FFmpeg. Handles the formats Android itself cannot open. */
    private fun decodeWithFfmpeg(context: Context, uri: Uri, out: File): Boolean {
        val input: String = if (uri.scheme == "file") {
            uri.path ?: return false
        } else {
            try {
                FFmpegKitConfig.getSafParameterForRead(context, uri)
            } catch (_: Throwable) {
                return false
            }
        }
        val args = arrayOf(
            "-hide_banner", "-nostdin", "-y",
            "-i", input,
            "-vn", "-sn", "-dn",
            "-map", "0:a:0",
            "-ac", Fmt.CHANNELS.toString(),
            "-ar", Fmt.SAMPLE_RATE.toString(),
            "-f", "s16le",
            "-acodec", "pcm_s16le",
            out.absolutePath
        )
        val session = FFmpegKit.executeWithArguments(args)
        val rc = session.returnCode ?: return false
        return ReturnCode.isSuccess(rc)
    }
}
