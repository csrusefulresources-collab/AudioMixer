package com.audiomixer.app

import android.net.Uri
import java.io.File

/**
 * One audio file inside the timeline.
 *
 * Audio is always stored on disk as raw PCM: 44100 Hz, stereo, signed 16-bit little endian.
 * Keeping the decoded data on disk (instead of RAM) is what lets the app hold many long
 * tracks on a cheap phone without running out of memory.
 */
class Track(
    val id: Int,
    val name: String,
    val uri: Uri?,
    @Volatile var anchor: Boolean
) {
    @Volatile var pcm: File? = null
    @Volatile var frames: Long = 0L

    /** Start position in the shared timeline, in frames. The first track is always 0. */
    @Volatile var offsetFrames: Long = 0L

    /** Linear gain applied while mixing. The first track is locked at 1.0. */
    @Volatile var gain: Float = 1f

    /** Muted tracks stay in the mix but are silent (volume setting is kept). */
    @Volatile var muted: Boolean = false

    /** A locked track cannot be moved any more (Add Mark). */
    @Volatile var locked: Boolean = false

    /** Last value of the Merge / Override slider (0..100) that produced [offsetFrames]. */
    @Volatile var slider: Int = 50

    @Volatile var decoding: Boolean = true
    @Volatile var error: String? = null

    val durationMs: Long get() = frames * 1000L / Fmt.SAMPLE_RATE
    val endFrame: Long get() = offsetFrames + frames
}
