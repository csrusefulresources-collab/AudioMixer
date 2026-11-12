package com.audiomixer.app

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mpg.MPGLib
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.PsyModel
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import java.io.OutputStream

/**
 * MP3 encoder. Android ships a decoder but no MP3 encoder at all, so the LAME engine
 * (pure Java port, jump3r) is compiled into the app and used to write real .mp3 files.
 */
class Mp3Encoder(private val out: OutputStream, bitrate: Int = 192) {

    private val lame = Lame()
    private val gfp: LameGlobalFlags
    private val mp3 = ByteArray(7200 + 8192 * 5 / 4 + 7200)

    init {
        val ga = GainAnalysis()
        val bs = BitStream()
        val p = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val mpg = MPGLib()
        val psy = PsyModel()

        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        p.setModules(lame)
        qupvt.setModules(tak, rv, psy)
        qu.setModules(bs, rv, qupvt, tak)
        vbr.setModules(lame, bs, ver)
        tak.setModules(qupvt)
        rv.setModules(bs)
        id3.setModules(bs, ver)

        gfp = lame.lame_init()
        gfp.num_channels = Fmt.CHANNELS
        gfp.in_samplerate = Fmt.SAMPLE_RATE
        gfp.brate = bitrate
        gfp.mode = MPEGMode.STEREO
        gfp.quality = 5
        gfp.VBR = VbrMode.vbr_off
        gfp.write_id3tag_automatic = false
        gfp.findReplayGain = false
        gfp.bWriteVbrTag = false
        lame.lame_init_params(gfp)
    }

    fun write(left: IntArray, right: IntArray, frames: Int) {
        if (frames <= 0) return
        var done = 0
        val step = 4096
        while (done < frames) {
            val n = minOf(step, frames - done)
            val l = if (done == 0 && n == frames) left else left.copyOfRange(done, done + n)
            val r = if (done == 0 && n == frames) right else right.copyOfRange(done, done + n)
            val got = lame.lame_encode_buffer_int(gfp, l, r, n, mp3, 0, mp3.size)
            if (got > 0) out.write(mp3, 0, got)
            done += n
        }
    }

    fun finish() {
        val got = lame.lame_encode_flush(gfp, mp3, 0, mp3.size)
        if (got > 0) out.write(mp3, 0, got)
        out.flush()
        lame.lame_close(gfp)
    }
}
