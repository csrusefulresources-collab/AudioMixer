package com.audiomixer.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * The one and only player. Every preview and the final mix go through a single AudioTrack
 * fed from our own PCM mixer, so volume, position and locks change live without stopping.
 * Pause keeps the position (press again to resume). Nothing here blocks the UI thread.
 */
class MixPlayer(ctx: Context) {

    enum class Mode { NONE, FIRST, LAST, FINAL }

    companion object {
        private const val BLOCK = 2048
        private const val UNSET = Long.MIN_VALUE
    }

    private class Session(val mode: Mode, val preview: Track?, val list: List<Track>, val startHead: Long) {
        @Volatile var running = true
        @Volatile var head = startHead
        @Volatile var at: AudioTrack? = null
        @Volatile var framesWritten = 0L
        @Volatile var naturalEnd = false
    }

    private val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var focusReq: AudioFocusRequest? = null

    @Volatile private var session: Session? = null
    @Volatile private var lastMode = Mode.NONE
    private val saved = LongArray(Mode.values().size) { UNSET }
    @Volatile private var listener: ((Boolean) -> Unit)? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
    }

    fun setStateListener(l: (Boolean) -> Unit) {
        listener = l
    }

    val playing: Boolean get() = session?.running == true
    val mode: Mode get() = session?.let { if (it.running) it.mode else null } ?: Mode.NONE

    /** (position, total, mode) in frames, relative to the start of that mode's audio. */
    fun position(all: List<Track>): Triple<Long, Long, Mode> {
        val s = session
        val m = if (s != null && s.running) s.mode else lastMode
        if (m == Mode.NONE) return Triple(0L, 0L, m)
        val h = if (s != null && s.running) audible(s) else saved[m.ordinal]
        if (m == Mode.FINAL) {
            val usable = all.filter { it.frames > 0 }
            if (usable.isEmpty()) return Triple(0L, 0L, m)
            val o = MixLayout.origin(usable)
            val t = MixLayout.total(usable, o)
            val p = if (h == UNSET) 0L else (h - o).coerceIn(0L, t)
            return Triple(p, t, m)
        }
        val tr = (if (m == Mode.FIRST) all.firstOrNull() else all.lastOrNull()) ?: return Triple(0L, 0L, m)
        val t = tr.frames
        return Triple(if (h == UNSET) 0L else h.coerceIn(0L, t), t, m)
    }

    fun resetPositions() {
        for (i in saved.indices) saved[i] = UNSET
        lastMode = Mode.NONE
    }

    /** Starts (or resumes) a mode. Returns false if nothing can be played. */
    fun start(m: Mode, preview: Track?, all: List<Track>): Boolean {
        val usable = all.filter { it.frames > 0 }
        if (m == Mode.FINAL) {
            if (usable.isEmpty()) return false
        } else if (preview == null || preview.frames <= 0) return false

        detach(true)?.let { silence(it) }
        if (!requestFocus()) {
            listener?.invoke(false)
            return false
        }
        var head = saved[m.ordinal]
        if (m == Mode.FINAL) {
            val o = MixLayout.origin(usable)
            val t = MixLayout.total(usable, o)
            head = if (head == UNSET || head >= o + t) o else maxOf(head, o)
        } else {
            head = if (head == UNSET || head >= preview!!.frames) 0L else head
        }
        val s = Session(m, preview, usable, head)
        lastMode = m
        synchronized(lock) { session = s }
        val th = Thread({ run(s) }, "mix-player")
        listener?.invoke(true)
        th.start()
        return true
    }

    fun toggle(m: Mode, preview: Track?, all: List<Track>) {
        if (playing && mode == m) pause() else start(m, preview, all)
    }

    fun pause() {
        val s = detach(true) ?: return
        silence(s)
        abandonFocus()
        listener?.invoke(false)
    }

    /** Moves the final-mix playhead; keeps playing if it was playing. */
    fun seekFinal(all: List<Track>, rel: Long) {
        val usable = all.filter { it.frames > 0 }
        if (usable.isEmpty()) return
        val o = MixLayout.origin(usable)
        val t = MixLayout.total(usable, o)
        val target = o + rel.coerceIn(0L, maxOf(0L, t - 1))
        val cur = session
        val wasFinal = cur != null && cur.running && cur.mode == Mode.FINAL
        if (wasFinal) detach(false)?.let { silence(it) }
        saved[Mode.FINAL.ordinal] = target
        lastMode = Mode.FINAL
        if (wasFinal) start(Mode.FINAL, null, all)
    }

    private fun detach(save: Boolean): Session? = synchronized(lock) {
        val s = session ?: return@synchronized null
        if (save) saved[s.mode.ordinal] = audible(s)
        session = null
        s.running = false
        s
    }

    private fun silence(s: Session) {
        try { s.at?.pause() } catch (_: Throwable) {}
        try { s.at?.flush() } catch (_: Throwable) {}
    }

    private fun audible(s: Session): Long {
        val a = s.at ?: return s.startHead
        return try {
            val pos = a.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            minOf(s.startHead + pos, s.head)
        } catch (_: Throwable) {
            s.head
        }
    }

    private fun requestFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusReq = req
        return am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private fun abandonFocus() {
        focusReq?.let { try { am.abandonAudioFocusRequest(it) } catch (_: Throwable) {} }
    }

    private fun finish(s: Session) {
        var mine = false
        synchronized(lock) {
            if (session === s) {
                session = null
                saved[s.mode.ordinal] = if (s.naturalEnd) UNSET else audible(s)
                mine = true
            }
        }
        if (mine) {
            abandonFocus()
            listener?.invoke(false)
        }
    }

    private fun run(s: Session) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        var at: AudioTrack? = null
        var bus: MixBus? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                Fmt.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
            )
            val size = maxOf(if (minBuf > 0) minBuf * 2 else 0, BLOCK * Fmt.CHANNELS * 4 * 3)
            at = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(Fmt.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            s.at = at
            if (!s.running) return
            at.play()
            val b = MixBus()
            bus = b
            val buf = FloatArray(BLOCK * Fmt.CHANNELS)
            while (s.running) {
                val n: Int
                if (s.mode == Mode.FINAL) {
                    val o = MixLayout.origin(s.list)
                    val remaining = o + MixLayout.total(s.list, o) - s.head
                    if (remaining <= 0) { s.naturalEnd = true; break }
                    b.mix(s.list, s.head, buf, BLOCK)
                    n = minOf(BLOCK.toLong(), remaining).toInt()
                } else {
                    val t = s.preview ?: break
                    if (s.head >= t.frames) { s.naturalEnd = true; break }
                    n = b.mixSingle(t, s.head, buf, BLOCK)
                    if (n <= 0) { s.naturalEnd = true; break }
                }
                val total = n * Fmt.CHANNELS
                var w = 0
                while (w < total && s.running) {
                    val r = at.write(buf, w, total - w, AudioTrack.WRITE_BLOCKING)
                    if (r < 0) { s.running = false; break }
                    w += r
                }
                val frames = w / Fmt.CHANNELS
                s.head += frames
                s.framesWritten += frames
                if (w < total) break
            }
            if (s.naturalEnd && s.running) {
                // Let the last buffered audio finish instead of cutting the ending.
                at.stop()
                var waited = 0
                while (s.running && waited < 3000 &&
                    (at.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < s.framesWritten
                ) {
                    Thread.sleep(20)
                    waited += 20
                }
            }
        } catch (_: Throwable) {
        } finally {
            bus?.close()
            try { at?.pause() } catch (_: Throwable) {}
            try { at?.release() } catch (_: Throwable) {}
            s.running = false
            finish(s)
        }
    }
}
