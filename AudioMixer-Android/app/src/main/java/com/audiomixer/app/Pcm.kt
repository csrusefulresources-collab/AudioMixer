package com.audiomixer.app

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Locale

/** Single audio format used everywhere inside the app. */
object Fmt {
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2
    const val BYTES_PER_FRAME = CHANNELS * 2

    fun framesToMs(f: Long): Long = f * 1000L / SAMPLE_RATE

    /** mm:ss.d — tenths of a second, so short overlaps are readable. */
    fun time(frames: Long): String {
        val ms = framesToMs(if (frames < 0) 0 else frames)
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        val d = (ms % 1000) / 100
        return String.format(Locale.US, "%02d:%02d.%d", m, s, d)
    }

    /** +mm:ss.d / -mm:ss.d, for positions that can be before the first file. */
    fun signed(frames: Long): String {
        val sign = if (frames < 0) "-" else "+"
        return sign + time(if (frames < 0) -frames else frames)
    }

    /** Plain words for screen readers: "2 minutes 30 seconds". */
    fun spoken(frames: Long): String {
        val ms = framesToMs(if (frames < 0) -frames else frames)
        val totalSec = ms / 1000
        val m = totalSec / 60
        val sec = totalSec % 60
        val tenth = (ms % 1000) / 100
        val parts = ArrayList<String>()
        if (m > 0) parts.add(if (m == 1L) "1 minute" else "$m minutes")
        if (sec > 0 || m == 0L) {
            val secText = if (tenth > 0) "$sec point $tenth" else "$sec"
            parts.add(if (sec == 1L && tenth == 0L) "1 second" else "$secText seconds")
        }
        return parts.joinToString(" ")
    }
}

/** Random access reader over a raw PCM file (44100/16/2). */
class PcmReader(val file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val frames: Long = raf.length() / Fmt.BYTES_PER_FRAME
    private var bytes = ByteArray(0)

    fun read(startFrame: Long, dest: ShortArray, destOffset: Int, count: Int): Int {
        if (count <= 0 || startFrame >= frames) return 0
        val want = minOf(count.toLong(), frames - startFrame).toInt()
        val need = want * Fmt.BYTES_PER_FRAME
        if (bytes.size < need) bytes = ByteArray(need)
        raf.seek(startFrame * Fmt.BYTES_PER_FRAME)
        var read = 0
        while (read < need) {
            val r = raf.read(bytes, read, need - read)
            if (r <= 0) break
            read += r
        }
        val n = read / Fmt.BYTES_PER_FRAME
        for (i in 0 until n * Fmt.CHANNELS) {
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            dest[destOffset + i] = ((hi shl 8) or lo).toShort()
        }
        return n
    }

    override fun close() {
        try {
            raf.close()
        } catch (_: Throwable) {
        }
    }
}

/** Converts any decoder output (any rate, any channel count, short or float) to 44100/16/2. */
class PcmSink(out: OutputStream, srcRate: Int, srcChannels: Int) {
    private val os = if (out is BufferedOutputStream) out else BufferedOutputStream(out, 1 shl 16)
    private var step = 1.0
    private var srcCh = 2
    private var t = 0.0
    private val prev = FloatArray(2)
    private val ob = ByteArray(1 shl 14)
    private var oi = 0
    private var cur: FloatArray = FloatArray(0)
    var frames: Long = 0L
        private set

    init {
        reset(srcRate, srcChannels)
    }

    fun reset(sampleRate: Int, channels: Int) {
        val r = if (sampleRate <= 0) Fmt.SAMPLE_RATE else sampleRate
        step = r.toDouble() / Fmt.SAMPLE_RATE
        srcCh = if (channels < 1) 1 else channels
        t = 0.0
        prev[0] = 0f
        prev[1] = 0f
    }

    fun writeShort(src: ShortArray, framesIn: Int) {
        if (framesIn <= 0) return
        val f = FloatArray(src.size)
        var i = 0
        while (i < src.size) {
            f[i] = src[i] / 32768f
            i++
        }
        writeFloat(f, framesIn)
    }

    fun writeFloat(src: FloatArray, framesIn: Int) {
        if (framesIn <= 0) return
        cur = src
        val vCount = framesIn + 1
        while (t + 1.0 < vCount) {
            val i = t.toInt()
            val frac = (t - i).toFloat()
            val l0 = sample(i, 0)
            val l1 = sample(i + 1, 0)
            val r0 = if (srcCh > 1) sample(i, 1) else l0
            val r1 = if (srcCh > 1) sample(i + 1, 1) else l1
            emit(l0 + (l1 - l0) * frac, r0 + (r1 - r0) * frac)
            t += step
        }
        t -= framesIn
        prev[0] = sample(framesIn - 1, 0)
        prev[1] = if (srcCh > 1) sample(framesIn - 1, 1) else prev[0]
    }

    private fun sample(k: Int, c: Int): Float {
        if (k <= 0) return prev[c]
        val idx = (k - 1) * srcCh + (if (c < srcCh) c else srcCh - 1)
        return if (idx < 0 || idx >= cur.size) 0f else cur[idx]
    }

    private fun emit(l: Float, r: Float) {
        put(clamp(l))
        put(clamp(r))
        frames++
    }

    private fun clamp(v: Float): Int {
        val s = (v * 32767f).toInt()
        return if (s < -32768) -32768 else if (s > 32767) 32767 else s
    }

    private fun put(s: Int) {
        if (oi + 2 > ob.size) flushOb()
        ob[oi++] = (s and 0xFF).toByte()
        ob[oi++] = ((s shr 8) and 0xFF).toByte()
    }

    private fun flushOb() {
        if (oi > 0) {
            os.write(ob, 0, oi)
            oi = 0
        }
    }

    fun finish() {
        flushOb()
        os.flush()
    }
}

/** Timeline helpers shared by the live player and the exporter. */
object MixLayout {
    fun origin(tracks: List<Track>): Long {
        var o = 0L
        for (t in tracks) if (t.frames > 0 && t.offsetFrames < o) o = t.offsetFrames
        return o
    }

    fun total(tracks: List<Track>, origin: Long): Long {
        var e = 0L
        for (t in tracks) if (t.frames > 0) {
            val x = t.offsetFrames + t.frames
            if (x > e) e = x
        }
        val len = e - origin
        return if (len < 1) 1 else len
    }
}

/**
 * The mixer itself. Reads every track straight from disk for one block of the timeline and
 * sums them into [out]. Gains are ramped inside the block so live volume changes never click.
 * A soft limiter is applied at the end, identically for the speakers and for the saved file,
 * so overlapping loud audios never clip harshly and "what you hear is what you save".
 * One MixBus belongs to exactly one thread.
 */
class MixBus {
    private val readers = HashMap<Int, PcmReader?>()
    private val lastGain = HashMap<Int, Float>()
    private var scratch = ShortArray(Fmt.CHANNELS * 8192)

    private fun readerFor(t: Track): PcmReader? {
        if (readers.containsKey(t.id)) return readers[t.id]
        val f = t.pcm
        val r = if (f != null && f.exists()) try {
            PcmReader(f)
        } catch (_: Throwable) {
            null
        } else null
        readers[t.id] = r
        return r
    }

    fun close() {
        for (r in readers.values) r?.close()
        readers.clear()
        lastGain.clear()
    }

    /** Mixes the whole timeline. [absStart] is an absolute timeline frame. */
    fun mix(tracks: List<Track>, absStart: Long, out: FloatArray, frames: Int) {
        java.util.Arrays.fill(out, 0f)
        for (t in tracks) addTrack(t, t.offsetFrames, absStart, out, frames)
        limit(out, frames * Fmt.CHANNELS)
    }

    /** Plays one track alone from [start] (frames inside the file). Returns frames produced. */
    fun mixSingle(t: Track, start: Long, out: FloatArray, frames: Int): Int {
        java.util.Arrays.fill(out, 0f)
        return addTrack(t, 0L, start, out, frames)
    }

    private fun addTrack(t: Track, offset: Long, absStart: Long, out: FloatArray, frames: Int): Int {
        val len = t.frames
        if (len <= 0) return 0
        val s = maxOf(offset, absStart)
        val e = minOf(offset + len, absStart + frames)
        if (e <= s) return 0
        val n = (e - s).toInt()
        val reader = readerFor(t) ?: return 0
        if (scratch.size < n * Fmt.CHANNELS) scratch = ShortArray(n * Fmt.CHANNELS)
        val got = reader.read(s - offset, scratch, 0, n)
        if (got <= 0) return 0
        val target = if (t.anchor) 1f else t.gain
        val from = lastGain[t.id] ?: target
        lastGain[t.id] = target
        val base = ((s - absStart) * Fmt.CHANNELS).toInt()
        val delta = if (got > 1) (target - from) / (got - 1) else 0f
        var i = 0
        while (i < got) {
            val g = from + delta * i
            out[base + i * 2] += (scratch[i * 2] / 32768f) * g
            out[base + i * 2 + 1] += (scratch[i * 2 + 1] / 32768f) * g
            i++
        }
        return got
    }

    private fun limit(out: FloatArray, count: Int) {
        val knee = 0.9f
        val room = 1f - knee
        var i = 0
        while (i < count) {
            val v = out[i]
            val a = if (v < 0f) -v else v
            if (a > knee) {
                val soft = knee + room * kotlin.math.tanh((a - knee) / room)
                out[i] = if (v < 0f) -soft else soft
            }
            i++
        }
    }
}

/** Writes a 16-bit PCM WAV file with a header that is exact from the first byte. */
class WavWriter(file: File, totalFrames: Long) {
    private val os = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var written = 0L

    init {
        val dataBytes = totalFrames * Fmt.BYTES_PER_FRAME
        os.write("RIFF".toByteArray())
        os.write(le32((36 + dataBytes).toInt()))
        os.write("WAVE".toByteArray())
        os.write("fmt ".toByteArray())
        os.write(le32(16))
        os.write(le16(1))                     // PCM
        os.write(le16(Fmt.CHANNELS))
        os.write(le32(Fmt.SAMPLE_RATE))
        os.write(le32(Fmt.SAMPLE_RATE * Fmt.BYTES_PER_FRAME))
        os.write(le16(Fmt.BYTES_PER_FRAME))
        os.write(le16(16))
        os.write("data".toByteArray())
        os.write(le32(dataBytes.toInt()))
    }

    fun write(shorts: ShortArray, count: Int) {
        val b = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = shorts[i].toInt()
            b[i * 2] = (v and 0xFF).toByte()
            b[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        os.write(b)
        written += count / Fmt.CHANNELS
    }

    fun close() {
        os.flush()
        os.close()
    }

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
}
