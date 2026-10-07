package app.eqsweep.audio

import app.eqsweep.core.Band
import app.eqsweep.core.BandType
import app.eqsweep.core.Dsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** JVM-only checks of the ported Web Audio chain (no Android classes involved). */
class ToneSynthTest {

    private val fs = 48000
    private val ceiling = ToneSynth.CEILING.toDouble()

    @Test
    fun autoHeadroomTransitionsDoNotOvershootSteadyTone() {
        val boosted = listOf(Band("boost", BandType.PK, 1000.0, 24.0, 0.5))
        val auto = minOf(0.0, Dsp.autoPreamp(boosted))
        for (rate in listOf(48000, 44100)) {
            for (startBoosted in listOf(true, false)) {
                val synth = ToneSynth(rate)
                synth.setLevelDb(-12.0)
                synth.setFrequency(1000.0)
                synth.setBands(boosted, eqOn = startBoosted, preampDb = auto)
                synth.play()
                val settled = renderBlocks(synth, rate / 2)
                val before = settled.takeLast(rate / 10).maxOf { abs(it.toDouble()) }
                synth.setBands(boosted, eqOn = !startBoosted, preampDb = auto)
                val transition = renderBlocks(synth, rate / 10)
                val during = transition.maxOf { abs(it.toDouble()) }
                val after = renderBlocks(synth, rate / 2).takeLast(rate / 10)
                    .maxOf { abs(it.toDouble()) }
                val reference = maxOf(before, after)
                println("transition rate=$rate boosted->${!startBoosted} auto=$auto before=$before during=$during after=$after ratio=${during / reference}")
                assertTrue("non-finite transition at $rate", transition.all { it.isFinite() })
                assertTrue("$rate boosted=$startBoosted overshoot ${during / reference}",
                    during <= 1.5 * reference)
            }
        }
    }

    @Test
    fun removingBandAndRapidReversalsKeepFiniteHeadroom() {
        val boosted = listOf(Band("boost", BandType.PK, 1000.0, 24.0, 0.5))
        val auto = minOf(0.0, Dsp.autoPreamp(boosted))
        for (rate in listOf(48000, 44100)) {
            val synth = ToneSynth(rate)
            synth.setFrequency(1000.0)
            synth.setLevelDb(-12.0)
            synth.setBands(boosted, preampDb = auto)
            synth.play()
            val boostedPeak = renderBlocks(synth, rate / 2).takeLast(rate / 10)
                .maxOf { abs(it.toDouble()) }
            var worst = 0.0
            for (edit in 0 until 20) {
                // EQ stays on throughout: alternate actual band removal/addition every 20 ms.
                synth.setBands(if (edit % 2 == 0) emptyList() else boosted,
                    preampDb = if (edit % 2 == 0) 0.0 else auto)
                val samples = renderBlocks(synth, rate / 50)
                assertTrue("non-finite rapid edit at $rate edit $edit", samples.all { it.isFinite() })
                worst = maxOf(worst, samples.maxOf { abs(it.toDouble()) })
            }
            synth.setBands(emptyList(), preampDb = 0.0)
            val flatPeak = renderBlocks(synth, rate / 2).takeLast(rate / 10)
                .maxOf { abs(it.toDouble()) }
            println("rapid rate=$rate boosted=$boostedPeak worst=$worst flat=$flatPeak ratio=${worst / maxOf(boostedPeak, flatPeak)}")
            assertTrue("rapid edit overshoot at $rate: $worst",
                worst <= 1.5 * maxOf(boostedPeak, flatPeak))
        }
    }

    @Test
    fun nonFiniteAndOutOfRangeFrequencyAndLevelStayFiniteAndInsideTheCeiling() {
        val synth = ToneSynth(fs)
        synth.setLevelDb(-12.0)
        synth.setFrequency(1000.0)
        synth.play()
        renderBlocks(synth, fs / 10)
        val calls = listOf<Pair<String, () -> Unit>>(
            "freq NaN" to { synth.setFrequency(Double.NaN) },
            "freq +Inf" to { synth.setFrequency(Double.POSITIVE_INFINITY) },
            "freq 1e9" to { synth.setFrequency(1e9) },
            "freq -5" to { synth.setFrequency(-5.0) },
            "level NaN" to { synth.setLevelDb(Double.NaN) },
            "level -Inf" to { synth.setLevelDb(Double.NEGATIVE_INFINITY) },
            "level +60" to { synth.setLevelDb(60.0) },
        )
        for ((label, call) in calls) {
            call()
            val block = renderBlocks(synth, fs / 4)
            assertTrue("$label: non-finite sample", block.all { it.isFinite() })
            assertTrue("$label: sample above the ceiling", block.all { abs(it.toDouble()) <= ceiling })
            assertTrue("$label: frequency ${synth.currentFrequency}",
                synth.currentFrequency in 1.0..(0.495 * fs))
        }
    }

    private fun renderBlocks(synth: ToneSynth, frames: Int): FloatArray {
        val out = FloatArray(frames)
        var offset = 0
        while (offset < frames) {
            val count = minOf(32, frames - offset)
            synth.render(out, offset, count)
            offset += count
        }
        return out
    }

    @Test
    fun toneDominatesTheSpectrumAndSitsAtTheCeiling() {
        val synth = ToneSynth(fs)
        synth.setLevelDb(0.0)
        synth.setFrequency(1000.0)
        synth.play()

        val buf = FloatArray(fs)
        synth.render(buf)

        val from = fs / 10 // skip 100 ms of fade-in and level smoothing
        val to = buf.size
        val tone = magnitudeAt(buf, from, to, 1000.0)
        val others = doubleArrayOf(500.0, 900.0, 1100.0, 2000.0, 3000.0)
        for (f in others) {
            val m = magnitudeAt(buf, from, to, f)
            println("dft: 1000 Hz = $tone, $f Hz = $m, ratio = ${tone / m}")
            assertTrue("1000 Hz ($tone) must dominate $f Hz ($m)", tone > 20.0 * m)
        }

        var peak = 0.0
        for (i in from until to) peak = maxOf(peak, abs(buf[i].toDouble()))
        println("peak sample = $peak (ceiling $ceiling, error ${abs(peak - ceiling) / ceiling * 100} %)")
        assertTrue("peak $peak must be within 3 % of $ceiling", abs(peak - ceiling) <= 0.03 * ceiling)
    }

    @Test
    fun frequencyGlidesWithTheUpstreamTimeConstant() {
        val synth = ToneSynth(fs)
        synth.setFrequency(1000.0)
        synth.play()
        synth.render(FloatArray(fs / 10)) // settle

        synth.setFrequency(2000.0)
        val one = FloatArray(1)
        var reached = -1
        for (i in 0 until fs) {
            synth.render(one)
            if (synth.currentFrequency >= 1990.0) {
                reached = i + 1
                break
            }
        }
        val ms = reached * 1000.0 / fs
        println("glide to 99 % reached after $reached samples = $ms ms")
        assertTrue("glide never reached 1990 Hz", reached > 0)
        assertTrue("glide too slow: $ms ms", reached < 1920)
        assertTrue("glide too fast: $ms ms", reached > 960)
    }

    @Test
    fun clipperHoldsTheCeilingUnderExtremeBoost() {
        val synth = ToneSynth(fs)
        synth.setLevelDb(0.0)
        synth.setFrequency(1000.0)
        val bands = List(8) { i ->
            Band(id = "b$i", type = BandType.PK, fc = 1000.0, gain = 24.0, q = 0.5)
        }
        synth.setBands(bands, eqOn = true, preampDb = 24.0)
        synth.play()

        val buf = FloatArray(fs / 2)
        synth.render(buf)
        var peak = 0.0
        for (v in buf) peak = maxOf(peak, abs(v.toDouble()))
        println("ceiling test: max |sample| = $peak")
        assertTrue("clipper leaked: $peak", peak <= ceiling + 1e-6)
        assertTrue("boost never reached the ceiling: $peak", peak >= 0.49)
    }

    @Test
    fun playAndStopFadeWithoutClicks() {
        val synth = ToneSynth(fs)
        synth.setLevelDb(0.0)
        synth.setFrequency(1000.0)
        assertTrue("fresh synth must be silent", synth.isSilent)

        synth.play()
        val one = FloatArray(1)
        var rise = -1
        for (i in 0 until fs / 10) {
            synth.render(one)
            if (synth.currentToneGain >= 0.99) {
                rise = i + 1
                break
            }
        }
        val riseMs = rise * 1000.0 / fs
        println("fade-in to 0.99 after $rise samples = $riseMs ms")
        assertTrue("fade-in never completed", rise > 0)
        assertTrue("fade-in too slow: $riseMs ms", riseMs <= 16.0)

        synth.stop()
        synth.render(FloatArray((0.016 * fs).toInt())) // 16 ms of fade-out
        val tail = FloatArray(fs / 100)
        synth.render(tail)
        for ((i, v) in tail.withIndex()) {
            assertEquals("sample $i after the fade-out must be digital silence", 0.0f, v, 0.0f)
        }
        assertTrue("engine must report silence", synth.isSilent)
        assertEquals(0.0, synth.currentToneGain, 0.0)
    }

    @Test
    fun peakingBandAppliesItsGain() {
        val enabledPeak = peakWithBand(eqOn = true, enabled = true)
        val bypassPeak = peakWithBand(eqOn = false, enabled = true)
        val disabledPeak = peakWithBand(eqOn = true, enabled = false)

        val enabledDb = 20.0 * log10(enabledPeak / bypassPeak)
        val disabledDb = 20.0 * log10(disabledPeak / bypassPeak)
        println("eq on = $enabledPeak, eq off = $bypassPeak -> $enabledDb dB")
        println("band disabled = $disabledPeak -> $disabledDb dB")
        assertTrue("expected -12 dB, measured $enabledDb dB", abs(enabledDb - (-12.0)) <= 0.5)
        assertTrue("disabled band must be transparent, measured $disabledDb dB", abs(disabledDb) <= 0.1)
    }

    /** Peak amplitude of the last 200 ms of a 1 s render with one PK band at the tone frequency. */
    private fun peakWithBand(eqOn: Boolean, enabled: Boolean): Double {
        val synth = ToneSynth(fs)
        synth.setLevelDb(0.0)
        synth.setFrequency(1000.0)
        synth.setBands(
            listOf(Band(id = "b0", type = BandType.PK, fc = 1000.0, gain = -12.0, q = 2.0, enabled = enabled)),
            eqOn = eqOn,
            preampDb = 0.0,
        )
        synth.play()
        val buf = FloatArray(fs)
        synth.render(buf)
        var peak = 0.0
        for (i in buf.size - fs / 5 until buf.size) peak = maxOf(peak, abs(buf[i].toDouble()))
        return peak
    }

    /** Amplitude of the [hz] component over `[from, to)`, by direct DFT sum. */
    private fun magnitudeAt(buf: FloatArray, from: Int, to: Int, hz: Double): Double {
        val w = 2.0 * PI * hz / fs
        var re = 0.0
        var im = 0.0
        for (i in from until to) {
            val p = w * (i - from)
            re += buf[i] * cos(p)
            im -= buf[i] * sin(p)
        }
        val n = (to - from).toDouble()
        return 2.0 * sqrt(re * re + im * im) / n
    }
}
