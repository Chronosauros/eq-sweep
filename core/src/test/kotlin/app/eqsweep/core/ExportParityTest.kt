package app.eqsweep.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Parity tests for [Export] against `parity-vectors.json` (generated from upstream JS by
 * `gen-vectors.mjs`), plus hand-written checks for `toGraphicEqText` (new, no upstream vectors)
 * and the `toFixed` rounding traps called out in the port instructions.
 */
class ExportParityTest {

    @Test
    fun toPeqTextMatchesVectors() {
        val cases = TestVectors.root.getValue("toPeqText").jsonArray
        assertTrue(cases.isNotEmpty(), "no toPeqText vectors")
        for (case in cases) {
            val obj = case.jsonObject
            val state = stateFromVector(obj.getValue("state").jsonObject)
            val expected = TestVectors.str(obj["out"])!!
            assertEquals(expected, Export.toPeqText(state))
        }
    }

    @Test
    fun sessionToJsonMatchesVectors() {
        val cases = TestVectors.root.getValue("sessionToJson").jsonArray
        assertTrue(cases.isNotEmpty(), "no sessionToJson vectors")
        for (case in cases) {
            val obj = case.jsonObject
            val state = stateFromVector(obj.getValue("state").jsonObject)
            val expected = TestVectors.str(obj["out"])!!
            assertEquals(expected, Export.sessionToJson(state))
        }
    }

    @Test
    fun sessionFromJsonMatchesVectors() {
        val cases = TestVectors.root.getValue("sessionFromJson").jsonArray
        assertTrue(cases.isNotEmpty(), "no sessionFromJson vectors")
        for (case in cases) {
            val obj = case.jsonObject
            val text = TestVectors.str(obj["text"])!!
            val ok = boolOrDefault(obj["ok"], false)
            if (ok) {
                val result = Export.sessionFromJson(text)
                assertTrue(result.containsKey("bands"), "expected parsed session to have bands for: $text")
            } else {
                assertFailsWith<IllegalArgumentException>("expected failure for: $text") {
                    Export.sessionFromJson(text)
                }
            }
        }
    }

    @Test
    fun graphicEqEmptyBandsAreAllZero() {
        val text = Export.toGraphicEqText(SessionState(bands = emptyList()))
        assertTrue(text.startsWith("GraphicEQ: 20 0.0; 21 0.0; 22 0.0;"))
        val entries = parseGraphicEq(text)
        assertEquals(127, entries.size)
        assertTrue(entries.all { it.second == "0.0" })
    }

    @Test
    fun graphicEqWithBandsMatchesGridAndValue() {
        val bands = listOf(
            Band(id = "p", type = BandType.PK, fc = 1000.0, gain = -3.0, q = 2.0, enabled = true),
            Band(id = "l", type = BandType.LSC, fc = 105.0, gain = 2.0, q = 0.7, enabled = false),
        )
        // 1000 Hz is not itself one of the 127 fixed grid points (the neighbours are 977 and
        // 1032), so there is no literal "1000" entry to look up. Instead this checks the same
        // responseDb + toFixed pipeline toGraphicEqText uses internally, evaluated exactly at
        // the enabled PK band's own center frequency: a peaking filter's magnitude response at
        // f == fc equals its configured gain exactly, so this must read "-3.0".
        assertEquals("-3.0", Export.toFixed(Dsp.responseDb(bands, 1000.0), 1))

        val text = Export.toGraphicEqText(SessionState(bands = bands))
        val entries = parseGraphicEq(text)
        assertEquals(127, entries.size)
        assertEquals(Export.GRAPHIC_EQ_FREQS.toList(), entries.map { it.first })

        // every exported value is the response of the enabled bands at that grid point, one decimal
        val enabled = bands.filter { it.enabled }
        for ((f, shown) in entries) {
            val fixed = Export.toFixed(Dsp.responseDb(enabled, f.toDouble()), 1)
            assertEquals(if (fixed == "-0.0") "0.0" else fixed, shown, "GraphicEQ value at $f Hz")
        }
        // the band is really in the output: the grid point nearest 1000 Hz is dipped
        assertTrue(entries.any { it.second.startsWith("-") }, "no negative value with a -3 dB peak")
        // the disabled shelf changes nothing
        assertEquals(text, Export.toGraphicEqText(SessionState(bands = enabled)))
    }

    @Test
    fun graphicEqValuesMatchOneDecimalFormat() {
        val regex = Regex("-?\\d+\\.\\d")

        val empty = parseGraphicEq(Export.toGraphicEqText(SessionState(bands = emptyList())))
        assertTrue(empty.all { regex.matches(it.second) })

        val withBands = parseGraphicEq(
            Export.toGraphicEqText(
                SessionState(
                    bands = listOf(
                        Band(id = "a", type = BandType.PK, fc = 100.0, gain = 15.0, q = 1.0, enabled = true),
                        Band(id = "b", type = BandType.HSC, fc = 5000.0, gain = -18.0, q = 0.6, enabled = true),
                    )
                )
            )
        )
        assertTrue(withBands.all { regex.matches(it.second) })
    }

    @Test
    fun toFixedMatchesJsSemantics() {
        assertEquals("2.67", Export.toFixed(2.675, 2))
        assertEquals("2.3", Export.toFixed(2.25, 1))
        assertEquals("-2.3", Export.toFixed(-2.25, 1))
        assertEquals("0.0", Export.toFixed(0.04, 1))
    }

    @Test
    fun sessionRoundTripPreservesBandsAndFreq() {
        val stateAJson = TestVectors.root.getValue("sessionToJson").jsonArray[0].jsonObject.getValue("state")
        val stateA = stateFromVector(stateAJson.jsonObject)

        val parsed = Export.sessionFromJson(Export.sessionToJson(stateA))

        val bandsElement = parsed.getValue("bands")
        assertTrue(bandsElement is JsonArray)
        assertEquals(stateA.bands.size, bandsElement.jsonArray.size)
        assertEquals(stateA.freq, TestVectors.num(parsed["freq"]))
    }

    @Test
    fun filenamesAreRenamedAwayFromUpstream() {
        assertEquals("eq-sweep-session.json", Export.SESSION_FILENAME)
        assertTrue(!Export.SESSION_FILENAME.contains("d" + "ms", ignoreCase = true))
    }
}

/** Builds a [SessionState] from a vector's `state` JSON object; missing fields fall back to defaults. */
private fun stateFromVector(obj: JsonObject): SessionState {
    val bands = (obj["bands"] as? JsonArray)?.map { bandFromVector(it.jsonObject) } ?: emptyList()

    val draftObj = obj["draft"] as? JsonObject
    val draft = if (draftObj == null) {
        Draft()
    } else {
        Draft(
            kind = MarkKind.fromJson(TestVectors.str(draftObj["kind"])),
            start = TestVectors.numOrNull(draftObj["start"]),
            top = TestVectors.numOrNull(draftObj["top"]),
            end = TestVectors.numOrNull(draftObj["end"]),
        )
    }

    return SessionState(
        version = obj["version"]?.let { TestVectors.num(it).toInt() } ?: 1,
        freq = obj["freq"]?.let { TestVectors.num(it) } ?: 1000.0,
        playing = boolOrDefault(obj["playing"], false),
        levelDb = obj["levelDb"]?.let { TestVectors.num(it) } ?: -18.0,
        eqOn = boolOrDefault(obj["eqOn"], true),
        preampDb = obj["preampDb"]?.let { TestVectors.num(it) } ?: 0.0,
        preampAuto = boolOrDefault(obj["preampAuto"], true),
        bands = bands,
        selectedId = TestVectors.str(obj["selectedId"]),
        draft = draft,
        theme = TestVectors.str(obj["theme"]) ?: "light",
    )
}

private fun bandFromVector(obj: JsonObject): Band = Band(
    id = TestVectors.str(obj["id"]) ?: "",
    type = BandType.valueOf(TestVectors.str(obj["type"]) ?: "PK"),
    fc = TestVectors.num(obj["fc"]),
    gain = TestVectors.num(obj["gain"]),
    q = TestVectors.num(obj["q"]),
    enabled = boolOrDefault(obj["enabled"], true),
)

private fun boolOrDefault(e: JsonElement?, default: Boolean): Boolean = when {
    e == null || e is JsonNull -> default
    e is JsonPrimitive -> e.booleanOrNull ?: default
    else -> default
}

/** Parses `"GraphicEQ: 20 -0.3; 21 -0.3; ..."` into `[(20, "-0.3"), (21, "-0.3"), ...]`. */
private fun parseGraphicEq(text: String): List<Pair<Int, String>> {
    require(text.startsWith("GraphicEQ: ")) { "missing GraphicEQ prefix: $text" }
    return text.removePrefix("GraphicEQ: ").split("; ").map { entry ->
        val sp = entry.indexOf(' ')
        entry.substring(0, sp).toInt() to entry.substring(sp + 1)
    }
}
