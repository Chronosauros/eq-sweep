/*
 * Derived from EQ by ear (https://github.com/DMS3tv/eqbyear), file js/audio.js.
 * Upstream licence statement, verbatim from its README.md:
 *   Apache License 2.0. See `LICENSE`. "DMS" and "EQ by ear" names and logo are not
 *   licensed for use on derived works.
 * Upstream names no copyright holder (its LICENSE appendix keeps the blank template),
 * so none is given here.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from JavaScript (js/audio.js) to Kotlin for EQ Sweep, 2026; modified.
 */
package app.eqsweep.audio

import app.eqsweep.core.Band
import app.eqsweep.core.BandType
import app.eqsweep.core.Dsp
import app.eqsweep.core.Store
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Mono sine generator with the upstream EQ chain, ported from the Web Audio engine of the upstream (js/audio.js):
 *
 * ```
 * sine -> toneGain (play/stop fade) -> preampGain -> biquad[0..7] -> levelGain
 *      (preampGain: the bands' headroom from the app, attenuation only, never the PREAMP)
 *      -> ceiling (x0.5) -> hard clipper (+/- 0.5)
 * ```
 *
 * Pure Kotlin/JVM: no `android.*` here, so the whole chain is unit-testable on the JVM.
 * The Android side (AudioTrack, audio focus, generator thread) lives outside this class.
 *
 * Threading: setters are called from a control thread (UI) and only write `@Volatile`/atomic
 * targets; every smoothed value lives on the audio thread and is advanced inside [render].
 * [render] allocates nothing except small short-lived [Dsp.Coeffs] while the EQ parameters glide.
 *
 * Deviations from the Web Audio original, both inaudible at the rate the engine actually uses:
 *  - [Dsp.coeffs] always designs at [Dsp.FS] (48 kHz). For a different [sampleRate] the centre
 *    frequency is pre-warped ([fcScale]) so the analogue prototype still lands on the intended
 *    frequency; at 48 kHz the scale is exactly 1.0 and the coefficients are bit-identical.
 *  - Filter coefficients are refreshed every [COEFF_BLOCK] samples instead of per sample
 *    (Web Audio uses 128-sample render quanta); fc/gain/q themselves are smoothed exactly.
 */
class ToneSynth(val sampleRate: Int = DEFAULT_SAMPLE_RATE) {

    companion object {
        /** Linear play/stop fade of the tone gain, seconds. */
        const val RAMP_S: Double = 0.015

        /** Frequency glide time constant (Web Audio `setTargetAtTime`), seconds. */
        const val GLIDE_S: Double = 0.008

        /** Level / preamp / filter parameter smoothing time constant, seconds. */
        const val SMOOTH_S: Double = 0.010

        /** Fixed -6 dBFS output ceiling; the hard clipper never lets a sample past it. */
        const val CEILING: Float = 0.5f

        /** Filters in the chain; unused ones sit flat, exactly like upstream. */
        val BANDS: Int = Store.MAX_BANDS

        const val DEFAULT_SAMPLE_RATE: Int = 48000
        const val DEFAULT_LEVEL_DB: Double = -18.0
        const val DEFAULT_FREQ_HZ: Double = 1000.0

        private const val CEILING_D: Double = 0.5
        private const val TWO_PI: Double = 2.0 * PI
        private const val COEFF_BLOCK: Int = 32
        // The old boosted biquads and their delay-line energy must decay before releasing
        // attenuation. This also covers the 10 ms coefficient smoothing tail.
        private const val HEADROOM_RELEASE_S: Double = 0.080
        private const val MIN_Q: Double = 0.05
        private const val DENORMAL: Double = 1e-30

        fun dbToGain(db: Double): Double = 10.0.pow(db / 20.0)
    }

    private val kGlide: Double = 1.0 - exp(-1.0 / (GLIDE_S * sampleRate))
    private val kSmooth: Double = 1.0 - exp(-1.0 / (SMOOTH_S * sampleRate))
    private val rampStep: Double = 1.0 / (RAMP_S * sampleRate)
    private val fcScale: Double = Dsp.FS / sampleRate
    private val maxFc: Double = sampleRate * 0.495

    // ---- targets: written by the control thread, read by the audio thread -------------------
    @Volatile private var targetFreq: Double = DEFAULT_FREQ_HZ
    @Volatile private var targetToneGain: Double = 0.0
    @Volatile private var targetLevelGain: Double = dbToGain(DEFAULT_LEVEL_DB)
    private val targetEq = AtomicReference(EqTarget(
        Array(BANDS) { BandTarget(BandType.PK, DEFAULT_FREQ_HZ, 0.0, 1.0) }, 1.0))

    // ---- audio-thread state ------------------------------------------------------------------
    @Volatile private var toneGain: Double = 0.0
    @Volatile private var freq: Double = DEFAULT_FREQ_HZ
    private var levelGain: Double = dbToGain(DEFAULT_LEVEL_DB)
    private var preampGain: Double = 1.0
    private var appliedEq: EqTarget = targetEq.get()
    private var releaseFrames: Int = 0
    private var phase: Double = 0.0
    private val filters: Array<BandFilter> = Array(BANDS) { BandFilter() }

    /** Smoothed frequency actually feeding the oscillator, Hz. */
    val currentFrequency: Double get() = freq

    /** Current play/stop fade value, 0..1. */
    val currentToneGain: Double get() = toneGain

    /** True when the tone is stopped and the fade has finished, so the engine may idle. */
    val isSilent: Boolean get() = targetToneGain == 0.0 && toneGain == 0.0

    /** Target frequency; the oscillator glides towards it with [GLIDE_S]. */
    fun setFrequency(hz: Double) {
        if (!hz.isFinite()) return
        targetFreq = min(maxFc, max(1.0, hz))
    }

    /** Fade the tone in over [RAMP_S] from wherever the gain is now (repeated calls never click). */
    fun play() {
        targetToneGain = 1.0
    }

    /** Fade the tone out over [RAMP_S]. */
    fun stop() {
        targetToneGain = 0.0
    }

    /** Output level in dBFS relative to the ceiling; smoothed with [SMOOTH_S]. */
    fun setLevelDb(db: Double) {
        if (!db.isFinite()) return
        targetLevelGain = dbToGain(db)
    }

    /**
     * Up to [BANDS] bands. Slots past the list, disabled bands and everything while `eqOn` is
     * false go flat (peaking, 0 dB, frequency and Q left where they were), and the preamp drops
     * to unity - same rules as upstream `AudioEngine.setBands`.
     */
    fun setBands(bands: List<Band>, eqOn: Boolean = true, preampDb: Double = 0.0) {
        val preamp = if (eqOn) dbToGain(preampDb) else 1.0
        targetEq.updateAndGet { prev ->
            val next = Array(BANDS) { i ->
                val b = bands.getOrNull(i)
                if (eqOn && b != null && b.enabled) {
                    BandTarget(b.type, b.fc, b.gain, b.q)
                } else {
                    BandTarget(BandType.PK, prev.bands[i].fc, 0.0, prev.bands[i].q)
                }
            }
            if (preamp == prev.preamp && next.indices.all { next[it] == prev.bands[it] }) prev
            else EqTarget(next, preamp)
        }
    }

    /** Fill [frames] mono samples starting at [offset]. Called from the audio thread only. */
    fun render(out: FloatArray, offset: Int = 0, frames: Int = out.size) {
        require(offset >= 0 && frames >= 0 && offset + frames <= out.size) {
            "render range out of bounds: offset=$offset frames=$frames size=${out.size}"
        }
        var done = 0
        while (done < frames) {
            val n = min(COEFF_BLOCK, frames - done)
            val eq = targetEq.get()
            if (eq !== appliedEq) {
                appliedEq = eq
                // Reducing boost: keep the old attenuation while coefficients and filter
                // memories settle. A subsequent edit restarts the hold from that edit.
                releaseFrames = if (eq.preamp > preampGain) {
                    (HEADROOM_RELEASE_S * sampleRate).toInt()
                } else 0
            }
            // Growing boost: establish headroom *before* changing any coefficients. The
            // preamp may take several blocks to ramp down; keep the previous filters flat.
            if (eq.preamp >= preampGain * 0.995) advanceFilters(n, eq.bands)
            if (releaseFrames > 0) releaseFrames = max(0, releaseFrames - n)
            val fTarget = targetFreq
            val gTarget = targetToneGain
            val lTarget = targetLevelGain
            val pTarget = if (releaseFrames > 0) min(preampGain, eq.preamp) else eq.preamp
            var g = toneGain
            var f = freq
            var lv = levelGain
            var pre = preampGain
            var ph = phase
            var i = 0
            while (i < n) {
                if (g < gTarget) g = min(gTarget, g + rampStep) else if (g > gTarget) g = max(gTarget, g - rampStep)
                if (f != fTarget) {
                    val next = f + (fTarget - f) * kGlide
                    f = if (abs(fTarget - next) <= 1e-6) fTarget else next
                }
                lv += (lTarget - lv) * kSmooth
                pre += (pTarget - pre) * kSmooth
                var x = pre * g * sin(ph)
                ph += TWO_PI * f / sampleRate
                if (ph >= TWO_PI) ph -= TWO_PI
                for (bq in filters) x = bq.process(x)
                var y = CEILING_D * lv * x
                if (y > CEILING_D) y = CEILING_D else if (y < -CEILING_D) y = -CEILING_D
                out[offset + done + i] = y.toFloat()
                i++
            }
            toneGain = g
            freq = f
            levelGain = lv
            preampGain = pre
            phase = ph
            done += n
        }
    }

    /** Smooth fc/gain/q by [n] samples and rebuild the coefficients of whatever moved. */
    private fun advanceFilters(n: Int, targets: Array<BandTarget>) {
        val decay = exp(-n.toDouble() / (SMOOTH_S * sampleRate))
        for (i in 0 until BANDS) {
            val t = targets[i]
            val f = filters[i]
            var dirty = false
            if (f.type != t.type) {
                f.type = t.type
                dirty = true
            }
            if (f.fc != t.fc) {
                f.fc = approach(f.fc, t.fc, decay, 1e-6)
                dirty = true
            }
            if (f.gain != t.gain) {
                f.gain = approach(f.gain, t.gain, decay, 1e-9)
                dirty = true
            }
            if (f.q != t.q) {
                f.q = approach(f.q, t.q, decay, 1e-9)
                dirty = true
            }
            if (dirty) f.recompute()
        }
    }

    private fun approach(value: Double, target: Double, decay: Double, eps: Double): Double {
        val next = target + (value - target) * decay
        return if (abs(target - next) <= eps) target else next
    }

    private data class BandTarget(val type: BandType, val fc: Double, val gain: Double, val q: Double)
    private class EqTarget(val bands: Array<BandTarget>, val preamp: Double)

    /** One RBJ biquad, transposed direct form II in Double. */
    private inner class BandFilter {
        var type: BandType = BandType.PK
        var fc: Double = DEFAULT_FREQ_HZ
        var gain: Double = 0.0
        var q: Double = 1.0
        private var b0: Double = 1.0
        private var b1: Double = 0.0
        private var b2: Double = 0.0
        private var a1: Double = 0.0
        private var a2: Double = 0.0
        private var z1: Double = 0.0
        private var z2: Double = 0.0

        init {
            recompute()
        }

        /**
         * Dividing every coefficient by `a0` (instead of multiplying by `1/a0`) matters: a flat
         * peaking band then yields b == a bit for bit, so an idle chain is an exact pass-through
         * and digital silence in stays digital silence out.
         */
        fun recompute() {
            val c = Dsp.coeffs(type, min(maxFc, max(1.0, fc)) * fcScale, gain, max(MIN_Q, q))
            b0 = c.b0 / c.a0
            b1 = c.b1 / c.a0
            b2 = c.b2 / c.a0
            a1 = c.a1 / c.a0
            a2 = c.a2 / c.a0
        }

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            if (abs(z1) < DENORMAL) z1 = 0.0
            if (abs(z2) < DENORMAL) z2 = 0.0
            return y
        }
    }
}
