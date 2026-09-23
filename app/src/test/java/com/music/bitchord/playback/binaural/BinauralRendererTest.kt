package com.music.bitchord.playback.binaural

import com.music.bitchord.playback.SpatialAudioProcessor
import com.music.bitchord.playback.SpatialMode
import com.music.bitchord.playback.audio.AudioBlock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The binaural renderer against its reference.
 *
 * `golden_<rate>.bin` holds windows of the reference chain's output (the Python implementation this was ported
 * from: upmixer + convolution with the shipped responses, before the output stage) for a deterministic test signal
 * that [testSignal] rebuilds sample for sample. The renderer is fed that signal in awkward block sizes, so the hop
 * buffering is exercised too, and must land on the same samples.
 */
class BinauralRendererTest {

    private fun moduleFile(path: String): File {
        val root = File(".").canonicalFile
        return listOf(File(root, "app/$path"), File(root, path)).first { it.exists() }
    }

    private fun shippedIrs(rate: Int): BinauralIrs =
        moduleFile("src/main/assets/binaural/v3_$rate.bin").inputStream().use { BinauralIrs.read(it) }

    /** Same generator as `export_assets.py:test_signal` (LCG noise + sines), interleaved stereo. */
    private fun testSignal(rate: Int, frames: Int): FloatArray {
        var s = 12345L
        fun next(): Double {
            s = (s * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return ((s ushr 8).toDouble() / 16777216.0) * 2.0 - 1.0
        }
        val out = FloatArray(2 * frames)
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val nl = next()
            val nr = 0.6 * nl + 0.4 * next()
            val env = 0.5 + 0.5 * sin(2 * PI * 0.9 * t)
            out[2 * i] = (0.25 * sin(2 * PI * 220 * t) + 0.15 * sin(2 * PI * 3100 * t + 0.5) +
                0.2 * env * nl + 0.1 * sin(2 * PI * 55 * t)).toFloat()
            out[2 * i + 1] = (0.25 * sin(2 * PI * 330 * t + 1.0) + 0.15 * sin(2 * PI * 5200 * t) +
                0.2 * (1 - env) * nr + 0.1 * sin(2 * PI * 55 * t)).toFloat()
        }
        return out
    }

    private fun matchesReference(rate: Int) {
        val golden = ByteBuffer.wrap(moduleFile("src/test/resources/binaural/golden_$rate.bin").readBytes())
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(rate, golden.int)
        val frames = golden.int
        val windows = golden.int

        val renderer = BinauralRenderer(rate, shippedIrs(rate), outputStage = false)
        val signal = testSignal(rate, frames)
        var done = 0
        var blockSize = 997
        while (done < frames) {
            val n = minOf(blockSize, frames - done)
            val block = signal.copyOfRange(2 * done, 2 * (done + n))
            renderer.process(block, n)
            System.arraycopy(block, 0, signal, 2 * done, 2 * n)
            done += n
            blockSize = if (blockSize == 997) 4096 else 997
        }

        repeat(windows) {
            val start = golden.int
            val count = golden.int
            var errSq = 0.0
            var refSq = 0.0
            var maxErr = 0.0
            for (i in 0 until 2 * count) {
                val ref = golden.float.toDouble()
                val got = signal[2 * start + i].toDouble()
                errSq += (got - ref) * (got - ref)
                refSq += ref * ref
                maxErr = max(maxErr, abs(got - ref))
            }
            val nullDb = 10 * log10(errSq / refSq)
            assertTrue("$rate Hz window @$start: residual $nullDb dB (max error $maxErr)", nullDb < -80.0)
        }
    }

    @Test
    fun `48 kHz output matches the reference chain`() = matchesReference(48000)

    @Test
    fun `44_1 kHz output matches the reference chain`() = matchesReference(44100)

    @Test
    fun `real FFT matches a direct DFT and inverts`() {
        for (n in intArrayOf(16, 2048)) {
            val fft = RealFft(n)
            val x = FloatArray(n) { sin(it * 0.37).toFloat() + (it % 7) * 0.1f }
            val re = FloatArray(n / 2 + 1)
            val im = FloatArray(n / 2 + 1)
            fft.forward(x, re, im)
            for (k in 0..n / 2 step maxOf(1, n / 64)) {
                var sr = 0.0
                var si = 0.0
                for (j in 0 until n) {
                    sr += x[j] * cos(2 * PI * j * k / n)
                    si -= x[j] * sin(2 * PI * j * k / n)
                }
                assertEquals("re[$k] n=$n", sr, re[k].toDouble(), 1e-3 * sqrt(n.toDouble()))
                assertEquals("im[$k] n=$n", si, im[k].toDouble(), 1e-3 * sqrt(n.toDouble()))
            }
            val back = FloatArray(n)
            fft.inverse(re, im, back)
            for (j in 0 until n) assertEquals(x[j].toDouble(), back[j] / n.toDouble(), 1e-4)
        }
    }

    @Test
    fun `limiter holds the ceiling and leaves quiet audio alone`() {
        val rate = 48000
        val limiter = PeakLimiter(rate, ceilingDb = -1f)
        val ceiling = Math.pow(10.0, -1.0 / 20).toFloat()
        val loud = FloatArray(2 * rate) { i -> (2.0 * sin(2 * PI * 100 * (i / 2) / rate)).toFloat() }
        limiter.process(loud, rate)
        assertTrue(loud.all { abs(it) <= ceiling + 1e-6f })

        val quiet = PeakLimiter(rate)
        val x = FloatArray(2 * 4800) { i -> (0.3 * sin(2 * PI * 440 * (i / 2) / rate)).toFloat() }
        val y = x.copyOf()
        quiet.process(y, 4800)
        val d = quiet.latencyFrames
        for (i in d until 4800) assertEquals(x[2 * (i - d)], y[2 * i], 1e-6f)
    }

    @Test
    fun `the tail flushes the delayed audio and the room`() {
        val rate = 48000
        val renderer = BinauralRenderer(rate, shippedIrs(rate))
        val burst = FloatArray(2 * 4096) { i -> if (i / 2 < 2048) (0.5 * sin(i * 0.01)).toFloat() else 0f }
        renderer.process(burst, 4096)
        val tail = FloatArray(2 * renderer.tailFrames)
        renderer.process(tail, renderer.tailFrames)
        val energy = { a: FloatArray, from: Int, to: Int -> (from until to).sumOf { (a[it] * a[it]).toDouble() } }
        assertTrue("delayed audio comes out in the tail", energy(tail, 0, tail.size) > 1e-3)
        val after = FloatArray(2 * 4096)
        renderer.process(after, 4096)
        assertTrue("nothing left after the tail", energy(after, 0, after.size) < 1e-12)
    }

    @Test
    fun `spatial processor renders binaurally and still widens`() {
        val rate = 48000
        BinauralIrStore.install(shippedIrs(rate))
        val processor = SpatialAudioProcessor()
        processor.configure(rate, 2)
        processor.enabled = true
        processor.mode = SpatialMode.BINAURAL

        val block = AudioBlock(2, 4096)
        val src = testSignal(rate, 4096)
        repeat(4) {
            System.arraycopy(src, 0, block.samples, 0, src.size)
            block.setFrameCount(4096)
            processor.process(block)
        }
        assertTrue(block.samples.all { it.isFinite() })
        assertTrue("binaural output is audible", block.samples.sumOf { (it * it).toDouble() } > 1.0)
        assertTrue(processor.tailFrames() > 0)

        processor.mode = SpatialMode.WIDEN
        System.arraycopy(src, 0, block.samples, 0, src.size)
        block.setFrameCount(4096)
        processor.process(block)
        assertEquals(0, processor.tailFrames())
        // widening keeps the mid (L+R) exactly, scaled by the output gain
        val i = 1000
        val mid = (src[2 * i] + src[2 * i + 1]) * 0.5f
        assertEquals(mid * 0.82f, (block.samples[2 * i] + block.samples[2 * i + 1]) * 0.5f, 0.2f)
    }
}
