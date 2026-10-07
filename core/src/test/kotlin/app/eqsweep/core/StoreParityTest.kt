package app.eqsweep.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Snapshot parity test for [Store] against `parity-vectors.json` (generated from upstream
 * `js/store.js` by `gen-vectors.mjs`, which hard-codes upstream's own `MAX_BANDS = 8` - not
 * regenerated for [Store.MAX_BANDS] = 10). Two vector groups:
 *  - `validate`: arbitrary raw JSON -> [Store.validate] -> compare snapshot.
 *  - `storeScript`: a fixed sequence of actions replayed on top of `Store.validate(null)`,
 *    comparing the snapshot after every single step.
 * Plus a handful of hand-written edge-case checks that the vectors don't cover directly. A vector
 * built to exercise upstream's 8-band cap now hits this app's own, higher one instead: those cases
 * are called out below rather than made to agree with a cap the app no longer has. Likewise the
 * preamp: upstream clamps it to +-24 dB, this app to [Store.PREAMP_LIMIT] = 12, so a
 * vector's preamp is compared after this app's clamp (see [assertSnapEquals]).
 */
class StoreParityTest {

    @Test
    fun `validate matches upstream store_js for each vector`() {
        val cases = TestVectors.root["validate"]!!.jsonArray
        assertTrue(cases.isNotEmpty(), "no validate vectors")
        for ((i, caseEl) in cases.withIndex()) {
            val case = caseEl.jsonObject
            val actual = snap(Store.validate(case["raw"]))
            // A vector whose raw input has more bands than this app's own MAX_BANDS
            // exercises upstream's 8-band cap, which this app intentionally no longer has (MAX_BANDS
            // = 10); comparing it to the vector's out-of-date, 8-capped snapshot would fail for a
            // reason that is not a bug, so check only this app's own cap for it instead.
            val rawBands = ((case["raw"] as? JsonObject)?.get("bands") as? JsonArray)?.size ?: 0
            if (rawBands > Store.MAX_BANDS) {
                assertEquals(Store.MAX_BANDS, actual.bands.size, "validate[$i]: divergent MAX_BANDS cap (was 8 upstream, is ${Store.MAX_BANDS} here)")
                // Everything else still has to match the vector: the bands both sides keep (the first
                // 8; the skip relies on no vector having between 9 and 10 raw bands) and the
                // non-band fields. preampDb is left out: an automatic preamp follows the bands kept.
                val expected = snapFromJson(case["out"]!!.jsonObject)
                assertEquals(8, expected.bands.size, "validate[$i]: upstream's cap")
                val ctx = "validate[$i] (divergent cap)"
                assertEquals(expected.version, actual.version, "$ctx: version")
                assertEquals(expected.freq, actual.freq, EPS, "$ctx: freq")
                assertEquals(expected.playing, actual.playing, "$ctx: playing")
                assertEquals(expected.levelDb, actual.levelDb, EPS, "$ctx: levelDb")
                assertEquals(expected.eqOn, actual.eqOn, "$ctx: eqOn")
                assertEquals(expected.preampAuto, actual.preampAuto, "$ctx: preampAuto")
                assertEquals(expected.theme, actual.theme, "$ctx: theme")
                assertEquals(expected.draft, actual.draft, "$ctx: draft")
                for (b in expected.bands.indices) {
                    val e = expected.bands[b]
                    val a = actual.bands[b]
                    assertEquals(e.type, a.type, "$ctx: bands[$b].type")
                    assertEquals(e.fc, a.fc, EPS, "$ctx: bands[$b].fc")
                    assertEquals(e.gain, a.gain, EPS, "$ctx: bands[$b].gain")
                    assertEquals(e.q, a.q, EPS, "$ctx: bands[$b].q")
                    assertEquals(e.enabled, a.enabled, "$ctx: bands[$b].enabled")
                }
                continue
            }
            val expected = snapFromJson(case["out"]!!.jsonObject)
            assertSnapEquals(expected, actual, "validate[$i]")
        }
    }

    @Test
    fun `storeScript replay matches upstream store_js step by step`() {
        val steps = TestVectors.root["storeScript"]!!.jsonArray
        var state = Store.validate(null)
        var counter = 0
        val newId: () -> String = { counter += 1; "b$counter" }

        // Once the script pushes past upstream's 8-band cap, this app's own MAX_BANDS =
        // 10 intentionally keeps the band the vector's upstream refuses - that one step describes
        // upstream's refusal, not this app's behaviour, so it is not compared. From then on every step
        // is applied to a state rebuilt from the vector's own snapshot of the step before, so the
        // remaining steps (removeBand, selectBand, load, setPreampAuto, setFreq coercions) are still
        // compared instead of skipped.
        var diverged = false
        var compared = 0
        var refused = 0
        var previous: Snap? = null
        for ((i, stepEl) in steps.withIndex()) {
            val step = stepEl.jsonObject
            val action = TestVectors.str(step["action"])!!
            val args = step["args"]!!.jsonArray

            if (diverged) state = stateFromSnap(previous!!)
            state = applyAction(state, action, args, newId)

            val expected = snapFromJson(step["state"]!!.jsonObject)
            previous = expected
            if (expected.bands.size >= 8 && state.bands.size > expected.bands.size) {
                diverged = true
                refused++
                continue
            }
            assertSnapEquals(expected, snap(state), "storeScript[$i] $action")
            compared++
        }
        assertEquals(steps.size, compared + refused)
        assertTrue(refused in 1..3, "only the steps refused at upstream's cap are skipped, got $refused of ${steps.size}")
        assertTrue(diverged, "expected this script to exercise the MAX_BANDS = 10 vs upstream's 8 divergence")
    }

    @Test
    fun `an 11th band is refused and the draft resets`() {
        var state = Store.validate(null)
        for (i in 1..Store.MAX_BANDS) {
            val f = i * 100.0
            state = state.setFreq(f).mark(Mark.TOP).mark(Mark.START).mark(Mark.END)
        }
        assertEquals(Store.MAX_BANDS, state.bands.size, "setup should have committed exactly MAX_BANDS bands")
        val bandsBefore = state.bands

        state = state.setFreq((Store.MAX_BANDS + 1) * 100.0).mark(Mark.TOP).mark(Mark.START).mark(Mark.END)

        assertEquals(Store.MAX_BANDS, state.bands.size, "an 11th band must be refused")
        assertEquals(bandsBefore, state.bands, "the band list must be untouched by the refusal")
        assertEquals(Store.emptyDraft(MarkKind.PEAK), state.draft, "the draft must reset even when the commit is refused")
    }

    @Test
    fun `undo on a fresh state returns the same instance`() {
        val state = Store.validate(null)
        assertSame(state, state.undo(), "undo with no draft marks and no bands must be a no-op")
    }

    @Test
    fun `setFreq with the current value returns the same instance`() {
        val state = Store.validate(null)
        assertSame(state, state.setFreq(state.freq), "setFreq must short-circuit when the value is unchanged")
    }

    @Test
    fun `updateBand rounded no op preserves a normalized state instance`() {
        val band = Band("b", BandType.PK, 1000.0, Store.GAIN_LIMIT, 1.0)
        val state = Store.normalize(Store.defaultState().copy(bands = listOf(band)))

        assertSame(state, state.updateBand(band.id, BandPatch(gain = Store.GAIN_LIMIT + 1.0)))
    }

    @Test
    fun `updateBand rounded no op repairs an inconsistent automatic preamp`() {
        val band = Band("b", BandType.PK, 1000.0, Store.GAIN_LIMIT, 1.0)
        val normalized = Store.normalize(Store.defaultState().copy(bands = listOf(band)))
        val inconsistent = normalized.copy(preampDb = 0.0, preampAuto = true)

        assertEquals(normalized.preampDb, inconsistent.updateBand(band.id, BandPatch(gain = Store.GAIN_LIMIT + 1.0)).preampDb)
    }

    @Test
    fun `validate caps a 12-band session at MAX_BANDS`() {
        val bandsJson = (0 until 12).joinToString(",") { i ->
            """{"id":"m$i","type":"PK","fc":${100 * (i + 1)},"gain":${i - 4},"q":1}"""
        }
        val raw = Json.parseToJsonElement("""{"bands":[$bandsJson]}""")
        val state = Store.validate(raw)
        assertEquals(Store.MAX_BANDS, state.bands.size, "validate must cap bands at Store.MAX_BANDS")
    }

    @Test
    fun `duplicate imported band IDs are made unique before edits`() {
        val raw = Json.parseToJsonElement("""{"bands":[{"id":"x","fc":500},{"id":"x","fc":1000}]}""")
        val state = Store.validate(raw)
        assertEquals(2, state.bands.map { it.id }.toSet().size)
        val secondId = state.bands[1].id
        assertEquals(500.0, state.updateBand(secondId, BandPatch(fc = 1200.0)).bands[0].fc)
        assertEquals(1200.0, state.updateBand(secondId, BandPatch(fc = 1200.0)).bands[1].fc)
        assertEquals(listOf("x"), state.removeBand(secondId).bands.map { it.id })
    }

    @Test
    fun `auto preamp beyond the limit is held at it and survives session round trip`() {
        // The preamp is profile data inside +-PREAMP_LIMIT, the automatic one too; only the
        // tone's own headroom in the app takes the full Dsp.autoPreamp.
        val bands = List(4) { i -> Band("b$i", BandType.PK, 1000.0, 12.0, 2.0) }
        assertTrue(Dsp.autoPreamp(bands) < -Store.PREAMP_LIMIT, "setup should need more headroom than the limit")
        val state = Store.normalize(Store.defaultState().copy(bands = bands))
        assertEquals(-Store.PREAMP_LIMIT, state.preampDb)
        val parsed = Json.parseToJsonElement(Export.sessionToJson(state))
        assertEquals(state.preampDb, Store.validate(parsed).preampDb)
    }
}

// -----------------------------------------------------------------------------------------------
// storeScript action dispatch: turns one {action, args} vector step into a Store call.
// -----------------------------------------------------------------------------------------------

private fun applyAction(state: SessionState, action: String, args: JsonArray, newId: () -> String): SessionState =
    when (action) {
        "init" -> state
        "setFreq" -> state.setFreq(TestVectors.num(args[0]))
        "mark" -> state.mark(markFrom(TestVectors.str(args[0])), newId)
        "setDraftKind" -> state.setDraftKind(MarkKind.fromJson(TestVectors.str(args[0])))
        "setLevel" -> state.setLevel(TestVectors.num(args[0]))
        "setPreamp" -> state.setPreamp(TestVectors.num(args[0]))
        "setPreampAuto" -> state.setPreampAuto()
        "toggleEq" -> state.toggleEq()
        "setPlaying" -> state.setPlaying(boolOf(args[0]))
        "undo" -> state.undo()
        "commitDraft" -> state.commitDraft(newId)
        "clearDraft" -> state.clearDraft()
        "updateBand" -> {
            val id = state.bands[TestVectors.num(args[0]).toInt()].id
            state.updateBand(id, bandPatchFrom(args[1].jsonObject))
        }
        "removeBand" -> state.removeBand(resolveBandId(state, args[0]))
        "selectBand" -> state.selectBand(resolveBandId(state, args[0]))
        "load" -> state.load(args[0])
        else -> error("storeScript: unknown action \"$action\"")
    }

private fun markFrom(s: String?): Mark = when (s) {
    "start" -> Mark.START
    "top" -> Mark.TOP
    "end" -> Mark.END
    else -> error("storeScript: unknown mark \"$s\"")
}

/** The vector's raw values for booleans are always genuine JSON `true`/`false` literals. */
private fun boolOf(e: JsonElement): Boolean = (e as JsonPrimitive).content == "true"

/** `removeBand`/`selectBand` args: a band index (number) or a literal id string such as "nope". */
private fun resolveBandId(state: SessionState, arg: JsonElement): String =
    if (arg is JsonPrimitive && !arg.isString) {
        state.bands[TestVectors.num(arg).toInt()].id
    } else {
        TestVectors.str(arg) ?: error("storeScript: cannot resolve a band id from $arg")
    }

/** `updateBand`'s patch object: an absent key means "leave unchanged"; an invalid type string means null. */
private fun bandPatchFrom(o: JsonObject): BandPatch {
    val typeStr = o["type"]?.let { if (it is JsonPrimitive && it.isString) it.content else null }
    return BandPatch(
        type = Store.TYPES.firstOrNull { it.name == typeStr },
        fc = o["fc"]?.let { TestVectors.num(it) },
        gain = o["gain"]?.let { TestVectors.num(it) },
        q = o["q"]?.let { TestVectors.num(it) },
        enabled = o["enabled"]?.let { boolOf(it) },
    )
}

// -----------------------------------------------------------------------------------------------
// Snapshot model, mirroring gen-vectors.mjs's snap(): bands lose their ids, selectedId becomes an
// index into the bands list (or null).
// -----------------------------------------------------------------------------------------------

private data class BandSnap(val type: String, val fc: Double, val gain: Double, val q: Double, val enabled: Boolean)

private data class DraftSnap(val kind: String, val start: Double?, val top: Double?, val end: Double?)

private data class Snap(
    val version: Int,
    val freq: Double,
    val playing: Boolean,
    val levelDb: Double,
    val eqOn: Boolean,
    val preampDb: Double,
    val preampAuto: Boolean,
    val bands: List<BandSnap>,
    val selectedIndex: Int?,
    val draft: DraftSnap,
    val theme: String,
)

private fun snap(s: SessionState): Snap {
    val selectedIndex = if (s.selectedId == null) {
        null
    } else {
        s.bands.indexOfFirst { it.id == s.selectedId }.let { if (it < 0) null else it }
    }
    return Snap(
        version = s.version,
        freq = s.freq,
        playing = s.playing,
        levelDb = s.levelDb,
        eqOn = s.eqOn,
        preampDb = s.preampDb,
        preampAuto = s.preampAuto,
        bands = s.bands.map { BandSnap(it.type.name, it.fc, it.gain, it.q, it.enabled) },
        selectedIndex = selectedIndex,
        draft = DraftSnap(s.draft.kind.json, s.draft.start, s.draft.top, s.draft.end),
        theme = s.theme,
    )
}

/** A state rebuilt from a vector's snapshot (band ids are made up: the snapshot has none). */
private fun stateFromSnap(s: Snap): SessionState {
    val bands = s.bands.mapIndexed { i, b -> Band("r$i", BandType.valueOf(b.type), b.fc, b.gain, b.q, b.enabled) }
    return Store.normalize(SessionState(
        version = s.version, freq = s.freq, playing = s.playing, levelDb = s.levelDb, eqOn = s.eqOn,
        preampDb = s.preampDb, preampAuto = s.preampAuto, bands = bands,
        selectedId = s.selectedIndex?.let { bands.getOrNull(it)?.id },
        draft = Draft(MarkKind.fromJson(s.draft.kind), s.draft.start, s.draft.top, s.draft.end),
        theme = s.theme,
    ))
}

private fun snapFromJson(o: JsonObject): Snap {
    val bands = o["bands"]!!.jsonArray.map { b ->
        val bo = b.jsonObject
        BandSnap(
            type = TestVectors.str(bo["type"])!!,
            fc = TestVectors.num(bo["fc"]),
            gain = TestVectors.num(bo["gain"]),
            q = TestVectors.num(bo["q"]),
            enabled = boolOf(bo["enabled"]!!),
        )
    }
    val draftObj = o["draft"]!!.jsonObject
    val draft = DraftSnap(
        kind = TestVectors.str(draftObj["kind"])!!,
        start = TestVectors.numOrNull(draftObj["start"]),
        top = TestVectors.numOrNull(draftObj["top"]),
        end = TestVectors.numOrNull(draftObj["end"]),
    )
    return Snap(
        version = TestVectors.num(o["version"]).toInt(),
        freq = TestVectors.num(o["freq"]),
        playing = boolOf(o["playing"]!!),
        levelDb = TestVectors.num(o["levelDb"]),
        eqOn = boolOf(o["eqOn"]!!),
        preampDb = TestVectors.num(o["preampDb"]),
        preampAuto = boolOf(o["preampAuto"]!!),
        bands = bands,
        selectedIndex = TestVectors.numOrNull(o["selectedIndex"])?.toInt(),
        draft = draft,
        theme = TestVectors.str(o["theme"])!!,
    )
}

// -----------------------------------------------------------------------------------------------
// Field-by-field comparison: doubles with tolerance, everything else exact, clear failure messages.
// -----------------------------------------------------------------------------------------------

private const val EPS = 1e-9

private fun assertDoubleOrNullEquals(expected: Double?, actual: Double?, message: String) {
    if (expected == null || actual == null) {
        assertEquals(expected, actual, message)
    } else {
        assertEquals(expected, actual, EPS, message)
    }
}

private fun assertSnapEquals(expected: Snap, actual: Snap, context: String) {
    assertEquals(expected.version, actual.version, "$context: version")
    assertEquals(expected.freq, actual.freq, EPS, "$context: freq")
    assertEquals(expected.playing, actual.playing, "$context: playing")
    assertEquals(expected.levelDb, actual.levelDb, EPS, "$context: levelDb")
    assertEquals(expected.eqOn, actual.eqOn, "$context: eqOn")
    // Auto headroom now follows the summed response; upstream used just the largest band.
    // DspParityTest covers its response bound independently. Manual preamp stays compatible up to the
    // range: +-PREAMP_LIMIT (12) here, +-24 upstream. Upstream's clamp followed by this app's
    // is this app's clamp of the raw value, so the vector's result is clamped rather than the step skipped.
    val preamp = if (expected.preampAuto) Store.autoPreampDb(expected.bands.mapIndexed { i, b ->
        Band("$i", BandType.valueOf(b.type), b.fc, b.gain, b.q, b.enabled)
    }) else expected.preampDb.coerceIn(-Store.PREAMP_LIMIT, Store.PREAMP_LIMIT)
    assertEquals(preamp, actual.preampDb, EPS, "$context: preampDb")
    assertEquals(expected.preampAuto, actual.preampAuto, "$context: preampAuto")
    assertEquals(expected.theme, actual.theme, "$context: theme")
    assertEquals(expected.selectedIndex, actual.selectedIndex, "$context: selectedIndex")
    assertEquals(expected.draft.kind, actual.draft.kind, "$context: draft.kind")
    assertDoubleOrNullEquals(expected.draft.start, actual.draft.start, "$context: draft.start")
    assertDoubleOrNullEquals(expected.draft.top, actual.draft.top, "$context: draft.top")
    assertDoubleOrNullEquals(expected.draft.end, actual.draft.end, "$context: draft.end")

    assertEquals(expected.bands.size, actual.bands.size, "$context: bands.size")
    for (i in expected.bands.indices) {
        val e = expected.bands[i]
        val a = actual.bands[i]
        assertEquals(e.type, a.type, "$context: bands[$i].type")
        assertEquals(e.fc, a.fc, EPS, "$context: bands[$i].fc")
        assertEquals(e.gain, a.gain, EPS, "$context: bands[$i].gain")
        assertEquals(e.q, a.q, EPS, "$context: bands[$i].q")
        assertEquals(e.enabled, a.enabled, "$context: bands[$i].enabled")
    }
}
