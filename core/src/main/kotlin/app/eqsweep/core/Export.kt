/*
 * Derived from EQ by ear (https://github.com/DMS3tv/eqbyear), file js/export.js.
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
 * Ported from JavaScript (js/export.js) to Kotlin for EQ Sweep, 2026; modified.
 */
package app.eqsweep.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * PEQ text, GraphicEQ text, and session JSON.
 *
 * `toPeqText`, `sessionToJson` and `sessionFromJson` are 1:1 ports of upstream `js/export.js`.
 * `toGraphicEqText` is new (not present upstream). Pure JVM: no Android imports.
 */
object Export {
    const val SESSION_FILENAME = "eq-sweep-session.json"

    /** Fixed 127-point Wavelet/AutoEq GraphicEQ frequency grid (Hz), ascending. */
    val GRAPHIC_EQ_FREQS: IntArray = intArrayOf(
        20, 21, 22, 23, 24, 26, 27, 29, 30, 32,
        34, 36, 38, 40, 43, 45, 48, 50, 53, 56,
        59, 63, 66, 70, 74, 78, 83, 87, 92, 97,
        103, 109, 115, 121, 128, 136, 143, 151, 160, 169,
        178, 188, 199, 210, 222, 235, 248, 262, 277, 292,
        309, 326, 345, 364, 385, 406, 429, 453, 479, 506,
        534, 565, 596, 630, 665, 703, 743, 784, 829, 875,
        924, 977, 1032, 1090, 1151, 1216, 1284, 1357, 1433, 1514,
        1599, 1689, 1784, 1885, 1991, 2103, 2221, 2347, 2479, 2618,
        2766, 2921, 3086, 3260, 3443, 3637, 3842, 4058, 4287, 4528,
        4783, 5052, 5337, 5637, 5955, 6290, 6644, 7018, 7414, 7831,
        8272, 8738, 9230, 9749, 10298, 10878, 11490, 12137, 12821, 13543,
        14305, 15110, 15961, 16860, 17809, 18812, 19871,
    )

    /** Generic parametric EQ text. Enabled bands only, sorted by fc, numbered from 1. */
    fun toPeqText(state: SessionState): String {
        val raw = state.preampDb
        // JS: Number(state.preampDb) || 0 -> NaN and +/-0 all become plain 0.
        val preamp = if (raw.isNaN() || raw == 0.0) 0.0 else raw
        val lines = mutableListOf("Preamp: ${toFixed(preamp, 1)} dB")
        val on = state.bands.filter { it.enabled }.sortedBy { it.fc }
        on.forEachIndexed { i, b ->
            lines.add(
                "Filter ${i + 1}: ON ${b.type.name} Fc ${Math.round(b.fc)} Hz " +
                    "Gain ${toFixed(b.gain, 1)} dB Q ${toFixed(b.q, 2)}"
            )
        }
        return lines.joinToString("\n")
    }

    /**
     * Everything except the transient playing flag. Byte-identical to JS
     * `JSON.stringify({...}, null, 2)`. Hand-rolled (not via kotlinx's pretty printer) so the
     * number formatting (`Dsp.jsNumberToString`), key order and indentation match exactly.
     */
    fun sessionToJson(state: SessionState): String = buildString {
        append("{\n")
        append("  \"version\": 1,\n")
        append("  \"freq\": ").append(Dsp.jsNumberToString(state.freq)).append(",\n")
        append("  \"levelDb\": ").append(Dsp.jsNumberToString(state.levelDb)).append(",\n")
        append("  \"eqOn\": ").append(state.eqOn).append(",\n")
        append("  \"preampDb\": ").append(Dsp.jsNumberToString(state.preampDb)).append(",\n")
        append("  \"preampAuto\": ").append(state.preampAuto).append(",\n")
        append("  \"bands\": ").append(bandsJson(state.bands, "  ")).append(",\n")
        append("  \"selectedId\": ")
        if (state.selectedId == null) append("null") else append(jsonString(state.selectedId))
        append(",\n")
        append("  \"draft\": ").append(draftJson(state.draft, "  ")).append(",\n")
        append("  \"theme\": ").append(jsonString(state.theme)).append("\n")
        append("}")
    }

    private fun bandsJson(bands: List<Band>, indent: String): String {
        if (bands.isEmpty()) return "[]"
        val inner = "$indent  "
        return buildString {
            append("[\n")
            bands.forEachIndexed { idx, b ->
                append(inner).append(bandJson(b, inner))
                append(if (idx != bands.lastIndex) ",\n" else "\n")
            }
            append(indent).append("]")
        }
    }

    private fun bandJson(b: Band, indent: String): String {
        val inner = "$indent  "
        return buildString {
            append("{\n")
            append(inner).append("\"id\": ").append(jsonString(b.id)).append(",\n")
            append(inner).append("\"type\": ").append(jsonString(b.type.name)).append(",\n")
            append(inner).append("\"fc\": ").append(Dsp.jsNumberToString(b.fc)).append(",\n")
            append(inner).append("\"gain\": ").append(Dsp.jsNumberToString(b.gain)).append(",\n")
            append(inner).append("\"q\": ").append(Dsp.jsNumberToString(b.q)).append(",\n")
            append(inner).append("\"enabled\": ").append(b.enabled).append("\n")
            append(indent).append("}")
        }
    }

    private fun draftJson(d: Draft, indent: String): String {
        val inner = "$indent  "
        return buildString {
            append("{\n")
            append(inner).append("\"kind\": ").append(jsonString(d.kind.json)).append(",\n")
            append(inner).append("\"start\": ").append(numOrNullJson(d.start)).append(",\n")
            append(inner).append("\"top\": ").append(numOrNullJson(d.top)).append(",\n")
            append(inner).append("\"end\": ").append(numOrNullJson(d.end)).append("\n")
            append(indent).append("}")
        }
    }

    private fun numOrNullJson(v: Double?): String = if (v == null) "null" else Dsp.jsNumberToString(v)

    /** JSON-escapes a string the way `JSON.stringify` does. */
    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Port of JS `sessionFromJson`: parses and validates shape only (no clamping/normalising).
     * Throws [IllegalArgumentException] for invalid JSON or a shape that isn't a usable session.
     */
    fun sessionFromJson(text: String): JsonObject {
        val parsed: JsonElement = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid JSON.", e)
        }
        if (parsed !is JsonObject) throw IllegalArgumentException("Not a session file.")
        val bandsElement = parsed["bands"]
        if (bandsElement !is JsonArray) throw IllegalArgumentException("Session has no bands.")
        for (bandElement in bandsElement) {
            if (bandElement !is JsonObject) throw IllegalArgumentException("Session has a malformed band.")
            val fc = jsNumberFromElement(bandElement["fc"])
            if (!fc.isFinite()) throw IllegalArgumentException("Session has a malformed band.")
        }
        return parsed
    }

    /**
     * JS `Number(v)` restricted to what the `fc` finiteness check needs: a missing key is JS
     * `undefined` (`Number(undefined)` is NaN), a present JSON `null` is JS `null` (`Number(null)`
     * is 0), strings are trimmed and parsed (empty string is 0), booleans are 1/0.
     */
    private fun jsNumberFromElement(e: JsonElement?): Double {
        if (e == null) return Double.NaN
        if (e is JsonNull) return 0.0
        if (e !is JsonPrimitive) return Double.NaN
        if (e.isString) {
            val t = e.content.trim()
            return if (t.isEmpty()) 0.0 else (t.toDoubleOrNull() ?: Double.NaN)
        }
        val b = e.booleanOrNull
        if (b != null) return if (b) 1.0 else 0.0
        return e.doubleOrNull ?: Double.NaN
    }

    /**
     * NEW (not in upstream JS): Wavelet/AutoEq GraphicEQ text on the fixed 127-frequency grid.
     * Enabled-bands-only response (via [Dsp.responseDb]); no preamp added; `eqOn` ignored.
     */
    fun toGraphicEqText(state: SessionState): String {
        val entries = GRAPHIC_EQ_FREQS.joinToString("; ") { f ->
            val db = Dsp.responseDb(state.bands, f.toDouble())
            val fixed = toFixed(db, 1)
            val shown = if (fixed == "-0.0") "0.0" else fixed
            "$f $shown"
        }
        return "GraphicEQ: $entries"
    }

    /**
     * JS `Number.prototype.toFixed(places)`: rounds the EXACT binary value of the double
     * half-up at `places` decimals. Never use `String.format("%.nf")` here (it rounds the
     * shortest decimal representation, not the exact binary value, so e.g. 2.675 would give
     * "2.68" where JS/BigDecimal give "2.67").
     */
    internal fun toFixed(x: Double, places: Int): String {
        if (x.isNaN()) return "NaN"
        if (x == Double.POSITIVE_INFINITY) return "Infinity"
        if (x == Double.NEGATIVE_INFINITY) return "-Infinity"
        return java.math.BigDecimal(x).setScale(places, java.math.RoundingMode.HALF_UP).toPlainString()
    }
}
