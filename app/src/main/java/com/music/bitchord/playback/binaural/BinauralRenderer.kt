package com.music.bitchord.playback.binaural

import kotlin.math.pow

/**
 * Stereo -> binaural renderer: the "v3" spatializer. Upmix to 5.1 ([ScottyUpmixer]), then convolve the six
 * channels with fixed binaural room responses ([PartitionedConvolver], [BinauralIrs]) into two ears, then the output
 * stage (headroom + [PeakLimiter]).
 *
 * Works in place on interleaved stereo Float32 of any block length, so it slots into the precision sink's DSP
 * chain like the other processors. Internally it runs one upmixer hop at a time; the output is the input delayed by
 * [latencyFrames] (about 48 ms), and after the last input sample [tailFrames] more frames of silence flush out the
 * delayed audio and the room's reverberant tail.
 *
 * @param outputStage false only for tests comparing the raw chain against the reference
 */
class BinauralRenderer(
    val sampleRate: Int,
    irs: BinauralIrs,
    private val outputStage: Boolean = true,
) {
    init { require(irs.sampleRate == sampleRate) { "responses are for ${irs.sampleRate} Hz, stream is $sampleRate Hz" } }

    private val tables = ScottyTables(sampleRate)
    private val upmixer = ScottyUpmixer(tables)
    val hop: Int = upmixer.hop
    private val convolver = PartitionedConvolver(irs.spectra(hop))
    private val limiter = if (outputStage) PeakLimiter(sampleRate) else null
    private val gain = if (outputStage) 10.0.pow(HEADROOM_DB / 20.0).toFloat() else 1f

    val latencyFrames: Int = upmixer.latencyFrames + (limiter?.latencyFrames ?: 0)
    val tailFrames: Int = latencyFrames + irs.taps

    private val inL = FloatArray(hop)
    private val inR = FloatArray(hop)
    private var fill = 0
    private val six = Array(6) { FloatArray(hop) }
    private val earL = FloatArray(hop)
    private val earR = FloatArray(hop)

    /** Output queue (interleaved), primed with one hop of silence: the upmixer's input buffering. */
    private val queue = FloatArray(4 * hop)
    private var qRead = 0
    private var qCount = 0

    init { reset() }

    fun reset() {
        upmixer.reset()
        convolver.reset()
        limiter?.reset()
        fill = 0
        queue.fill(0f)
        qRead = 0
        qCount = hop
    }

    /** [samples]: interleaved stereo, [frames] frames, processed in place. */
    fun process(samples: FloatArray, frames: Int) {
        val cap = queue.size / 2
        for (i in 0 until frames) {
            inL[fill] = samples[2 * i]
            inR[fill] = samples[2 * i + 1]
            if (++fill == hop) {
                fill = 0
                upmixer.processHop(inL, inR, six)
                convolver.process(six, earL, earR)
                var w = (qRead + qCount) % cap
                for (k in 0 until hop) {
                    queue[2 * w] = earL[k] * gain
                    queue[2 * w + 1] = earR[k] * gain
                    w = if (w + 1 == cap) 0 else w + 1
                }
                qCount += hop
            }
            samples[2 * i] = queue[2 * qRead]
            samples[2 * i + 1] = queue[2 * qRead + 1]
            qRead = if (qRead + 1 == cap) 0 else qRead + 1
            qCount--
        }
        limiter?.process(samples, frames)
    }

    companion object {
        /**
         * Output trim. The upmix + room raise peaks by up to ~9 dB over the stereo source (mostly bass, through the
         * LFE path) while loudness rises ~1.5 dB; -8 dB keeps [PeakLimiter] idle almost all of the time on loud,
         * bass-heavy masters, so the binaural sound is not squashed. It plays ~6 dB quieter than the stereo source.
         */
        const val HEADROOM_DB = -8.0
    }
}
