package com.audiomixer.app

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException

/**
 * Renders the whole timeline into one continuous file with the very same MixBus (and limiter)
 * that feeds the speakers: what you heard is what you get, no gaps, one single flow.
 */
object Exporter {

    enum class Format { WAV, MP3 }

    private const val BLOCK = 8192

    fun export(
        tracks: List<Track>,
        format: Format,
        out: File,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ): File {
        val usable = tracks.filter { it.frames > 0 }
        require(usable.isNotEmpty()) { "No audio to save" }
        val origin = MixLayout.origin(usable)
        val total = MixLayout.total(usable, origin)
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()

        val bus = MixBus()
        val buf = FloatArray(BLOCK * Fmt.CHANNELS)
        var ok = false
        try {
            when (format) {
                Format.WAV -> {
                    val w = WavWriter(out, total)
                    try {
                        var pos = 0L
                        while (pos < total) {
                            if (isCancelled()) throw CancellationException()
                            val n = minOf(BLOCK.toLong(), total - pos).toInt()
                            bus.mix(usable, origin + pos, buf, n)
                            val s = ShortArray(n * Fmt.CHANNELS)
                            for (i in 0 until n * Fmt.CHANNELS) {
                                val q = Math.round(buf[i] * 32767f)
                                s[i] = (if (q < -32768) -32768 else if (q > 32767) 32767 else q).toShort()
                            }
                            w.write(s, n * Fmt.CHANNELS)
                            pos += n
                            onProgress(((pos * 100) / total).toInt())
                        }
                    } finally {
                        w.close()
                    }
                }
                Format.MP3 -> {
                    val os = BufferedOutputStream(FileOutputStream(out), 1 shl 16)
                    try {
                        val enc = Mp3Encoder(os)
                        var pos = 0L
                        while (pos < total) {
                            if (isCancelled()) throw CancellationException()
                            val n = minOf(BLOCK.toLong(), total - pos).toInt()
                            bus.mix(usable, origin + pos, buf, n)
                            val l = IntArray(n)
                            val r = IntArray(n)
                            for (i in 0 until n) {
                                val lv = Math.round(buf[i * 2] * 32767f).coerceIn(-32768, 32767)
                                val rv = Math.round(buf[i * 2 + 1] * 32767f).coerceIn(-32768, 32767)
                                l[i] = lv shl 16
                                r[i] = rv shl 16
                            }
                            enc.write(l, r, n)
                            pos += n
                            onProgress(((pos * 100) / total).toInt())
                        }
                        enc.finish()
                    } finally {
                        os.close()
                    }
                }
            }
            ok = true
        } finally {
            bus.close()
            if (!ok) out.delete()
        }
        return out
    }
}
