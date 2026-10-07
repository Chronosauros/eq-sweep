/*
 * Derived from EQ by ear (https://github.com/DMS3tv/eqbyear), file js/dsp.js.
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
 * Ported from JavaScript (js/dsp.js) to Kotlin for EQ Sweep, 2026; modified.
 */
package app.eqsweep.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure DSP + axis math, ported 1:1 from upstream `js/dsp.js`.
 * Rounding uses java.lang.Math.round (half up, like JS Math.round); never kotlin.math.round (half even).
 */
object Dsp {
    const val FMIN: Double = 20.0
    const val FMAX: Double = 20000.0
    const val FS: Double = 48000.0

    private val LOG_RANGE: Double = ln(FMAX / FMIN)

    val MAJOR_TICKS: List<Double> = listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)
    val MINOR_TICKS: List<Double> = listOf(
        30.0, 40.0, 60.0, 70.0, 80.0, 90.0,
        300.0, 400.0, 600.0, 700.0, 800.0, 900.0,
        3000.0, 4000.0, 6000.0, 7000.0, 8000.0, 9000.0,
    )

    /** JS: non-finite -> 1000, else clamp to [FMIN, FMAX]. */
    fun clampFreq(f: Double): Double {
        if (!f.isFinite()) return 1000.0
        return min(FMAX, max(FMIN, f))
    }

    fun freqToX(f: Double, width: Double): Double = (width * ln(clampFreq(f) / FMIN)) / LOG_RANGE

    fun xToFreq(x: Double, width: Double): Double {
        val t = min(1.0, max(0.0, x / width))
        return FMIN * exp(t * LOG_RANGE)
    }

    data class Coeffs(val b0: Double, val b1: Double, val b2: Double, val a0: Double, val a1: Double, val a2: Double)

    /** RBJ Audio EQ Cookbook, Q form. Unnormalised coefficients. */
    fun coeffs(type: BandType, fc: Double, gain: Double, q: Double): Coeffs {
        val a = 10.0.pow(gain / 40.0)
        val w0 = (2.0 * Math.PI * fc) / FS
        val c = cos(w0)
        val s = sin(w0)
        val al = s / (2.0 * q)
        if (type == BandType.PK) {
            return Coeffs(
                b0 = 1 + al * a, b1 = -2 * c, b2 = 1 - al * a,
                a0 = 1 + al / a, a1 = -2 * c, a2 = 1 - al / a,
            )
        }
        val sa = 2.0 * sqrt(a) * al
        if (type == BandType.LSC) {
            return Coeffs(
                b0 = a * ((a + 1) - (a - 1) * c + sa),
                b1 = 2 * a * ((a - 1) - (a + 1) * c),
                b2 = a * ((a + 1) - (a - 1) * c - sa),
                a0 = (a + 1) + (a - 1) * c + sa,
                a1 = -2 * ((a - 1) + (a + 1) * c),
                a2 = (a + 1) + (a - 1) * c - sa,
            )
        }
        // HSC
        return Coeffs(
            b0 = a * ((a + 1) + (a - 1) * c + sa),
            b1 = -2 * a * ((a - 1) + (a + 1) * c),
            b2 = a * ((a + 1) + (a - 1) * c - sa),
            a0 = (a + 1) - (a - 1) * c + sa,
            a1 = 2 * ((a - 1) - (a + 1) * c),
            a2 = (a + 1) - (a - 1) * c - sa,
        )
    }

    fun coeffs(band: Band): Coeffs = coeffs(band.type, band.fc, band.gain, band.q)

    /** Magnitude of already-designed coefficients. Useful when plotting a whole response. */
    fun magnitudeDb(coeffs: Coeffs, f: Double): Double {
        val (b0, b1, b2, a0, a1, a2) = coeffs
        val w = (2.0 * Math.PI * f) / FS
        val cw = cos(w)
        val sw = sin(w)
        val c2w = cos(2 * w)
        val s2w = sin(2 * w)
        val nr = b0 + b1 * cw + b2 * c2w
        val ni = -b1 * sw - b2 * s2w
        val dr = a0 + a1 * cw + a2 * c2w
        val di = -a1 * sw - a2 * s2w
        val num = nr * nr + ni * ni
        val den = dr * dr + di * di
        return 10 * log10(num / den)
    }

    fun magnitudeDb(type: BandType, fc: Double, gain: Double, q: Double, f: Double): Double =
        magnitudeDb(coeffs(type, fc, gain, q), f)

    fun magnitudeDb(band: Band, f: Double): Double = magnitudeDb(band.type, band.fc, band.gain, band.q, f)

    /** Sum of enabled bands only. */
    fun responseDb(bands: List<Band>, f: Double): Double {
        var sum = 0.0
        for (b in bands) if (b.enabled) sum += magnitudeDb(b, f)
        return sum
    }

    /** Sum the response of enabled bands whose coefficients have already been designed. */
    fun responseDbCoeffs(coefficients: List<Coeffs>, f: Double): Double {
        var sum = 0.0
        for (coeffs in coefficients) sum += magnitudeDb(coeffs, f)
        return sum
    }

    /** Log-spaced sample frequencies for drawing curves. */
    fun sampleFreqs(n: Int = 320): DoubleArray {
        val out = DoubleArray(n)
        for (i in 0 until n) out[i] = FMIN * (FMAX / FMIN).pow(i.toDouble() / (n - 1))
        return out
    }

    /**
     * Three marks -> one band. fc = top, or sqrt(start*end) without top; null when neither.
     * Q = fc/|end-start| clamped to 0.3..10 and rounded to 0.05 (default 2.0). Gain -3 for peak, +3 for dip.
     */
    fun bandFromMarks(draft: Draft): BandSpec? {
        val start = draft.start
        val top = draft.top
        val end = draft.end
        val fc: Double = when {
            top != null -> top
            start != null && end != null -> sqrt(start * end)
            else -> return null
        }
        var q = 2.0
        if (start != null && end != null && abs(end - start) > 0) {
            q = fc / abs(end - start)
        }
        q = min(10.0, max(0.3, q))
        q = Math.round(q * 20).toDouble() / 20
        return BandSpec(
            type = BandType.PK,
            fc = Math.round(fc).toDouble(),
            gain = if (draft.kind == MarkKind.DIP) 3.0 else -3.0,
            q = q,
        )
    }

    private data class PreampCache(val bands: List<Band>, val db: Double)

    @Volatile private var preampCache: PreampCache? = null

    /** Attenuation for the highest *combined* response, rounded outward to 0.1 dB. */
    fun autoPreamp(bands: List<Band>): Double {
        preampCache?.let { if (it.bands == bands) return it.db }
        val active = bands.filter { it.enabled && it.gain != 0.0 }
        val result = if (active.isEmpty()) 0.0 else {
            val coefficients = active.map { coeffs(it) }
            fun responseAt(f: Double): Double {
                val w = 2.0 * Math.PI * f / FS
                val c = cos(w)
                val s = sin(w)
                val c2 = 2.0 * c * c - 1.0
                val s2 = 2.0 * s * c
                var db = 0.0
                for (b in coefficients) {
                    val nr = b.b0 + b.b1 * c + b.b2 * c2
                    val ni = -b.b1 * s - b.b2 * s2
                    val dr = b.a0 + b.a1 * c + b.a2 * c2
                    val di = -b.a1 * s - b.a2 * s2
                    db += 10.0 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
                }
                return db
            }

            // Shelf resonances can fall outside the displayed 20 Hz..20 kHz range.
            // Cover the full digital audio band, including DC and Nyquist endpoints.
            val samples = 1024
            val logMin = ln(1.0)
            val logStep = (ln(FS / 2.0) - logMin) / (samples - 1)
            val values = DoubleArray(samples) { i -> responseAt(exp(logMin + i * logStep)) }
            var peak = max(responseAt(0.0), responseAt(FS / 2.0))
            for (v in values) peak = max(peak, v)
            for (band in active) peak = max(peak, responseAt(band.fc))

            // Refine every local maximum in log frequency; each interval is only two grid
            // steps wide, so resonant shelves are not rounded down by grid placement.
            val ratio = (sqrt(5.0) - 1.0) / 2.0
            for (i in 1 until samples - 1) {
                if (values[i] < values[i - 1] || values[i] < values[i + 1]) continue
                var lo = logMin + (i - 1) * logStep
                var hi = logMin + (i + 1) * logStep
                var x1 = hi - ratio * (hi - lo)
                var x2 = lo + ratio * (hi - lo)
                var y1 = responseAt(exp(x1))
                var y2 = responseAt(exp(x2))
                repeat(18) {
                    if (y1 < y2) {
                        lo = x1
                        x1 = x2
                        y1 = y2
                        x2 = lo + ratio * (hi - lo)
                        y2 = responseAt(exp(x2))
                    } else {
                        hi = x2
                        x2 = x1
                        y2 = y1
                        x1 = hi - ratio * (hi - lo)
                        y1 = responseAt(exp(x1))
                    }
                }
                peak = max(peak, max(y1, y2))
            }
            if (peak <= 1e-6) 0.0 else -Math.ceil((peak + 1e-6) * 10.0) / 10.0
        }
        // Copy the list: callers may hold a mutable List despite the immutable reducer API.
        preampCache = PreampCache(bands.toList(), result)
        return result
    }

    /** "3 100" with a thin space (U+2009) as thousands separator, integer Hz. */
    fun fmtHz(f: Double): String {
        val n = Math.round(f)
        val s = abs(n).toString()
        val parts = ArrayList<String>()
        var i = s.length
        while (i > 0) {
            parts.add(0, s.substring(max(0, i - 3), i))
            i -= 3
        }
        return (if (n < 0) "-" else "") + parts.joinToString(" ")
    }

    /** Tick labels: 105 -> "105", 2500 -> "2.5k", 20000 -> "20k". */
    fun fmtK(f: Double): String {
        if (f >= 1000) {
            val v = f / 1000
            val shown = if (isInteger(v)) v else toFixedNumber(v, 2)
            return jsNumberToString(shown) + "k"
        }
        return Math.round(f).toString()
    }

    private fun isInteger(v: Double): Boolean = v.isFinite() && v == Math.floor(v)

    /** JS `Number(v.toFixed(places))`: round half up at `places` decimals, then back to a number. */
    internal fun toFixedNumber(v: Double, places: Int): Double =
        java.math.BigDecimal(v).setScale(places, java.math.RoundingMode.HALF_UP).toDouble()

    /** JS `String(number)` for the value ranges used here (no exponent forms). */
    internal fun jsNumberToString(v: Double): String {
        if (v == 0.0) return "0"
        if (isInteger(v) && abs(v) < 1e15) return v.toLong().toString()
        return v.toString()
    }
}
