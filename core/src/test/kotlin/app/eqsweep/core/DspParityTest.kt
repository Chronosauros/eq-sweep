package app.eqsweep.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Shared plumbing for the parity suites: vector lookup, JSON -> model decoding and
 * counted assertions. Every comparison against a vector goes through one of the
 * `eqD` / `eqS` / `near` / `yes` / `isNull` / `nonNull` helpers so each test can
 * assert how many vector comparisons it actually ran.
 */
abstract class DspParityBase {

    /** Vector comparisons performed by the current test method (JUnit makes a fresh instance per test). */
    protected var checks: Int = 0

    protected fun group(name: String): JsonArray =
        TestVectors.root[name]?.jsonArray ?: error("vector group '$name' missing from parity-vectors.json")

    protected fun obj(name: String): JsonObject =
        TestVectors.root[name]?.jsonObject ?: error("vector group '$name' missing from parity-vectors.json")

    /** Exact equality (boxed, so -0.0 != 0.0 and NaN == NaN, like the JS vectors demand). */
    protected fun eqD(expected: Double, actual: Double, msg: String) {
        checks++
        assertEquals(expected, actual, msg)
    }

    protected fun eqS(expected: String, actual: String, msg: String) {
        checks++
        assertEquals(expected, actual, msg)
    }

    protected fun near(expected: Double, actual: Double, tol: Double, msg: String) {
        checks++
        val delta = abs(expected - actual)
        assertTrue(
            expected == actual || delta <= tol,
            "$msg: expected $expected, got $actual (delta $delta > tol $tol)",
        )
    }

    protected fun yes(condition: Boolean, msg: String) {
        checks++
        assertTrue(condition, msg)
    }

    protected fun isNull(value: Any?, msg: String) {
        checks++
        assertNull(value, msg)
    }

    protected fun <T : Any> nonNull(value: T?, msg: String): T {
        checks++
        return assertNotNull(value, msg)
    }

    /** Vector band shape: {type, fc, gain, q} plus an optional `enabled` (JS rule: anything but false is enabled). */
    protected fun bandOf(o: JsonObject, id: String = "t"): Band = Band(
        id = id,
        type = BandType.valueOf(requireNotNull(TestVectors.str(o["type"])) { "band without type: $o" }),
        fc = TestVectors.num(o["fc"]),
        gain = TestVectors.num(o["gain"]),
        q = TestVectors.num(o["q"]),
        enabled = enabledOf(o),
    )

    private fun enabledOf(o: JsonObject): Boolean {
        val e = o["enabled"]
        if (e == null || e is JsonNull) return true
        return (e as JsonPrimitive).booleanOrNull != false
    }

    protected fun bandsOf(a: JsonArray): List<Band> =
        a.mapIndexed { i, e -> bandOf(e.jsonObject, "b$i") }

    protected fun show(o: JsonObject, key: String): String = TestVectors.str(o[key]) ?: "null"
}

/** Constants and the log frequency axis: clampFreq, freqToX, xToFreq, sampleFreqs. */
class DspAxisParityTest : DspParityBase() {

    @Test
    fun constants() {
        val c = obj("constants")
        eqD(TestVectors.num(c["FMIN"]), Dsp.FMIN, "constants.FMIN")
        eqD(TestVectors.num(c["FMAX"]), Dsp.FMAX, "constants.FMAX")
        eqD(TestVectors.num(c["FS"]), Dsp.FS, "constants.FS")

        val major = requireNotNull(c["MAJOR_TICKS"]).jsonArray
        yes(major.size == Dsp.MAJOR_TICKS.size, "MAJOR_TICKS size: expected ${major.size}, got ${Dsp.MAJOR_TICKS.size}")
        major.forEachIndexed { i, e -> eqD(TestVectors.num(e), Dsp.MAJOR_TICKS[i], "MAJOR_TICKS[$i]") }

        val minor = requireNotNull(c["MINOR_TICKS"]).jsonArray
        yes(minor.size == Dsp.MINOR_TICKS.size, "MINOR_TICKS size: expected ${minor.size}, got ${Dsp.MINOR_TICKS.size}")
        minor.forEachIndexed { i, e -> eqD(TestVectors.num(e), Dsp.MINOR_TICKS[i], "MINOR_TICKS[$i]") }

        assertEquals(5 + major.size + minor.size, checks, "constants: assertion count")
    }

    @Test
    fun clampFreq() {
        val cases = group("clampFreq")
        cases.forEachIndexed { i, e ->
            val o = e.jsonObject
            val input = TestVectors.num(o["in"])
            eqD(TestVectors.num(o["out"]), Dsp.clampFreq(input), "clampFreq[$i] in=${show(o, "in")}")
        }
        assertEquals(cases.size, checks, "clampFreq: assertion count")
    }

    @Test
    fun freqToX() {
        val cases = group("freqToX")
        cases.forEachIndexed { i, e ->
            val o = e.jsonObject
            val f = TestVectors.num(o["f"])
            val w = TestVectors.num(o["w"])
            val x = Dsp.freqToX(f, w)
            near(TestVectors.num(o["x"]), x, 1e-9, "freqToX[$i] f=$f w=$w")
            near(f, Dsp.xToFreq(x, w), 1e-9, "freqToX[$i] round trip xToFreq(freqToX(f=$f, w=$w), w)")
        }
        assertEquals(2 * cases.size, checks, "freqToX: assertion count")
    }

    @Test
    fun xToFreq() {
        val cases = group("xToFreq")
        cases.forEachIndexed { i, e ->
            val o = e.jsonObject
            val x = TestVectors.num(o["x"])
            val w = TestVectors.num(o["w"])
            near(TestVectors.num(o["f"]), Dsp.xToFreq(x, w), 1e-9, "xToFreq[$i] x=$x w=$w")
        }
        assertEquals(cases.size, checks, "xToFreq: assertion count")
    }

    @Test
    fun sampleFreqs() {
        val v320 = requireNotNull(TestVectors.root["sampleFreqs320"]).jsonArray
        val got320 = Dsp.sampleFreqs(320)
        yes(got320.size == v320.size, "sampleFreqs(320) size: expected ${v320.size}, got ${got320.size}")
        v320.forEachIndexed { i, e -> near(TestVectors.num(e), got320[i], 1e-9, "sampleFreqs320[$i]") }

        val default = Dsp.sampleFreqs()
        yes(default.size == 320, "sampleFreqs() default n: expected 320, got ${default.size}")
        yes(default.contentEquals(got320), "sampleFreqs() must be identical to sampleFreqs(320)")

        val v5 = requireNotNull(TestVectors.root["sampleFreqs5"]).jsonArray
        val got5 = Dsp.sampleFreqs(5)
        yes(got5.size == v5.size, "sampleFreqs(5) size: expected ${v5.size}, got ${got5.size}")
        v5.forEachIndexed { i, e -> near(TestVectors.num(e), got5[i], 1e-9, "sampleFreqs5[$i]") }

        assertEquals(4 + v320.size + v5.size, checks, "sampleFreqs: assertion count")
    }
}

/** Filter maths: RBJ coefficients, single band magnitude, summed response. */
class DspFilterParityTest : DspParityBase() {

    @Test
    fun coeffs() {
        val cases = group("coeffs")
        cases.forEachIndexed { i, e ->
            val o = e.jsonObject
            val band = bandOf(requireNotNull(o["band"]).jsonObject)
            val want = requireNotNull(o["out"]).jsonObject
            val got = Dsp.coeffs(band)
            val label = "coeffs[$i] ${band.type} fc=${band.fc} gain=${band.gain} q=${band.q}"
            near(TestVectors.num(want["b0"]), got.b0, 1e-12, "$label b0")
            near(TestVectors.num(want["b1"]), got.b1, 1e-12, "$label b1")
            near(TestVectors.num(want["b2"]), got.b2, 1e-12, "$label b2")
            near(TestVectors.num(want["a0"]), got.a0, 1e-12, "$label a0")
            near(TestVectors.num(want["a1"]), got.a1, 1e-12, "$label a1")
            near(TestVectors.num(want["a2"]), got.a2, 1e-12, "$label a2")
        }
        assertEquals(6 * cases.size, checks, "coeffs: assertion count")
    }

    @Test
    fun magnitude() {
        val cases = group("magnitude")
        var expected = 0
        cases.forEachIndexed { i, e ->
            val o = e.jsonObject
            val band = bandOf(requireNotNull(o["band"]).jsonObject)
            val points = requireNotNull(o["points"]).jsonArray
            yes(points.size == 52, "magnitude[$i] point count: expected 52, got ${points.size}")
            expected += 1 + points.size
            val label = "magnitude[$i] ${band.type} fc=${band.fc} gain=${band.gain} q=${band.q}"
            points.forEach { p ->
                val pt = p.jsonArray
                val f = TestVectors.num(pt[0])
                near(TestVectors.num(pt[1]), Dsp.magnitudeDb(band, f), 1e-9, "$label @ f=$f")
            }
        }
        assertEquals(expected, checks, "magnitude: assertion count")
    }

    @Test
    fun response() {
        val cases = group("response")
        var expected = 0
        cases.forEachIndexed { i, e ->
            val o = e.jsonObject
            val bands = bandsOf(requireNotNull(o["bands"]).jsonArray)
            val points = requireNotNull(o["points"]).jsonArray
            yes(points.size == 52, "response[$i] point count: expected 52, got ${points.size}")
            expected += 1 + points.size
            val label = "response[$i] (${bands.size} bands, ${bands.count { it.enabled }} enabled)"
            val coefficients = bands.filter { it.enabled }.map(Dsp::coeffs)
            points.forEach { p ->
                val pt = p.jsonArray
                val f = TestVectors.num(pt[0])
                val direct = Dsp.responseDb(bands, f)
                near(TestVectors.num(pt[1]), direct, 1e-9, "$label @ f=$f")
                eqD(direct, Dsp.responseDbCoeffs(coefficients, f), "$label cached coeffs @ f=$f")
            }
        }
        assertEquals(expected + cases.sumOf { requireNotNull(it.jsonObject["points"]).jsonArray.size }, checks, "response: assertion count")
    }
}

/** Band derivation, preamp and the two label formatters. */
class DspFormatParityTest : DspParityBase() {

    @Test
    fun bandFromMarks() {
        val cases = group("bandFromMarks")
        var expected = 0
        cases.forEachIndexed { i, e ->
            val o = e.jsonObject
            val dj = requireNotNull(o["draft"]).jsonObject
            val draft = Draft(
                kind = MarkKind.fromJson(TestVectors.str(dj["kind"])),
                start = TestVectors.numOrNull(dj["start"]),
                top = TestVectors.numOrNull(dj["top"]),
                end = TestVectors.numOrNull(dj["end"]),
            )
            val got = Dsp.bandFromMarks(draft)
            val label = "bandFromMarks[$i] start=${draft.start} top=${draft.top} end=${draft.end} kind=${draft.kind}"
            val wantE = o["out"]
            if (wantE == null || wantE is JsonNull) {
                isNull(got, "$label: expected null")
                expected += 1
            } else {
                val want = wantE.jsonObject
                val band = nonNull(got, "$label: expected a band, got null")
                val wantType = BandType.valueOf(requireNotNull(TestVectors.str(want["type"])))
                yes(band.type == wantType, "$label type: expected $wantType, got ${band.type}")
                eqD(TestVectors.num(want["fc"]), band.fc, "$label fc")
                eqD(TestVectors.num(want["gain"]), band.gain, "$label gain")
                eqD(TestVectors.num(want["q"]), band.q, "$label q")
                expected += 5
            }
        }
        assertEquals(expected, checks, "bandFromMarks: assertion count")
    }

    @Test
    fun autoPreamp() {
        assertEquals(0.0, Dsp.autoPreamp(emptyList()))
        assertFalse(Dsp.autoPreamp(emptyList()).toBits() == (-0.0).toBits())
        val coincident = listOf(
            Band("a", BandType.PK, 1000.0, 12.0, 2.0),
            Band("b", BandType.PK, 1000.0, 12.0, 2.0),
        )
        assertTrue(Dsp.autoPreamp(coincident) <= -24.0)
        assertEquals(0.0, Dsp.autoPreamp(coincident.map { it.copy(enabled = false) }))

        // High-Q shelves can overshoot even when their gain is negative. The derived value
        // must cover the combined response rather than individual gain signs.
        val mixed = listOf(
            Band("low", BandType.LSC, 180.0, -16.0, 20.0),
            Band("high", BandType.HSC, 6000.0, -12.0, 20.0),
            Band("peak", BandType.PK, 1200.0, 9.0, 13.0),
        )
        val attenuation = Dsp.autoPreamp(mixed)
        for (i in 0..16384) {
            val f = Dsp.FMIN * (Dsp.FMAX / Dsp.FMIN).pow(i / 16384.0)
            assertTrue(Dsp.responseDb(mixed, f) + attenuation <= 0.11, "uncovered response at $f Hz")
        }
    }

    @Test
    fun autoPreampCoversShelfResonanceOutsideDisplayedRange() {
        for (band in listOf(
            Band("high", BandType.HSC, 19900.0, 24.0, 20.0),
            Band("low", BandType.LSC, 20.0, 24.0, 20.0),
        )) {
            val attenuation = Dsp.autoPreamp(listOf(band))
            for (i in 0..16384) {
                val f = (Dsp.FS / 2.0).pow(i / 16384.0)
                assertTrue(Dsp.responseDb(listOf(band), f) + attenuation <= 0.1, "Uncovered shelf at $f Hz")
            }
        }
    }

    @Test
    fun fmtHz() {
        val cases = group("fmtHz")
        cases.forEachIndexed { i, e ->
            val o = e.jsonObject
            val input = TestVectors.num(o["in"])
            val want = requireNotNull(TestVectors.str(o["out"])) { "fmtHz[$i] has no expected string" }
            eqS(want, Dsp.fmtHz(input), "fmtHz[$i] in=$input")
        }
        assertEquals(cases.size, checks, "fmtHz: assertion count")
    }

    @Test
    fun fmtK() {
        val cases = group("fmtK")
        cases.forEachIndexed { i, e ->
            val o = e.jsonObject
            val input = TestVectors.num(o["in"])
            val want = requireNotNull(TestVectors.str(o["out"])) { "fmtK[$i] has no expected string" }
            eqS(want, Dsp.fmtK(input), "fmtK[$i] in=$input")
        }
        assertEquals(cases.size, checks, "fmtK: assertion count")
    }
}
