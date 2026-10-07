/*
 * Derived from EQ by ear (https://github.com/DMS3tv/eqbyear), file js/store.js.
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
 * Ported from JavaScript (js/store.js) to Kotlin for EQ Sweep, 2026; modified.
 */
package app.eqsweep.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Pure, immutable reducer port of upstream `js/store.js`. No subscriptions, no mutation: every
 * action is a plain `SessionState -> SessionState` function (see the extension functions below).
 * `object Store` only holds the constants/constructors and the two functions (`validate`,
 * `normalize`) that upstream keeps as free functions outside `createStore`.
 */
object Store {
    const val MAX_BANDS = 10
    val TYPES: List<BandType> = listOf(BandType.PK, BandType.LSC, BandType.HSC)

    const val LEVEL_MIN = -60.0
    const val LEVEL_MAX = -6.0
    const val GAIN_LIMIT = 24.0
    const val Q_MIN = 0.1
    const val Q_MAX = 20.0
    /** +-12 dB (upstream: +-24). The preamp is profile data for the export, never heard in the app. */
    const val PREAMP_LIMIT = 12.0

    /** The automatic preamp, inside [PREAMP_LIMIT]. */
    fun autoPreampDb(bands: List<Band>): Double = Dsp.autoPreamp(bands).coerceIn(-PREAMP_LIMIT, PREAMP_LIMIT)

    /** JS falls back to a `bN-<timestamp>` scheme without `crypto.randomUUID`; the JVM always has UUID. */
    fun nextId(): String = UUID.randomUUID().toString()

    fun emptyDraft(kind: MarkKind = MarkKind.PEAK): Draft = Draft(kind = kind, start = null, top = null, end = null)

    fun defaultState(): SessionState = SessionState(
        version = 1,
        freq = 1000.0,
        playing = false,
        levelDb = -18.0,
        eqOn = true,
        preampDb = 0.0,
        preampAuto = true,
        bands = emptyList(),
        selectedId = null,
        draft = emptyDraft(),
        theme = "light",
    )

    /** Validate an arbitrary JSON value into a full, in-range state. Never throws. */
    fun validate(raw: JsonElement?): SessionState {
        val obj = (raw as? JsonObject) ?: JsonObject(emptyMap())

        val bands = ArrayList<Band>()
        val seenIds = HashSet<String>()
        val bandsElement = obj["bands"]
        if (bandsElement is JsonArray) {
            for (b in bandsElement) {
                val cb = cleanBand(b)
                if (cb != null) {
                    var id = cb.id
                    while (!seenIds.add(id)) id = nextId()
                    bands.add(if (id == cb.id) cb else cb.copy(id = id))
                }
                if (bands.size >= MAX_BANDS) break
            }
        }

        val rawDraft = obj["draft"] as? JsonObject
        val draft = Draft(
            kind = MarkKind.fromJson(strOrNull(rawDraft?.get("kind"))),
            start = markHz(rawDraft?.get("start")),
            top = markHz(rawDraft?.get("top")),
            end = markHz(rawDraft?.get("end")),
        )

        val selectedIdRaw = strOrNull(obj["selectedId"])
        val selectedId = if (selectedIdRaw != null && bands.any { it.id == selectedIdRaw }) selectedIdRaw else null

        return normalize(
            SessionState(
                version = 1,
                freq = num(obj["freq"], Dsp.FMIN, Dsp.FMAX, 1000.0),
                playing = false,
                levelDb = num(obj["levelDb"], LEVEL_MIN, LEVEL_MAX, -18.0),
                eqOn = !isExplicitFalse(obj["eqOn"]),
                preampDb = num(obj["preampDb"], -PREAMP_LIMIT, PREAMP_LIMIT, 0.0),
                preampAuto = !isExplicitFalse(obj["preampAuto"]),
                bands = bands,
                selectedId = selectedId,
                draft = draft,
                theme = if (strOrNull(obj["theme"]) == "dark") "dark" else "light",
            )
        )
    }

    /** Keeps derived fields consistent: auto preamp follows the enabled bands. */
    fun normalize(s: SessionState): SessionState {
        if (s.preampAuto) {
            val auto = autoPreampDb(s.bands)
            if (auto != s.preampDb) return s.copy(preampDb = auto)
        }
        return s
    }
}

/** A partial update for [SessionState.updateBand]; a `null` field means "leave unchanged". */
data class BandPatch(
    val type: BandType? = null,
    val fc: Double? = null,
    val gain: Double? = null,
    val q: Double? = null,
    val enabled: Boolean? = null,
)

// -----------------------------------------------------------------------------------------------
// File-private helpers shared by Store.validate()/cleanBand() and the extension functions below.
// Declared at file scope (not as `Store` members) because `private` object members are not visible
// to top-level extension functions, even from the same file.
// -----------------------------------------------------------------------------------------------

/**
 * First step of JS `num()`'s dynamic coercion: turn an arbitrary JSON value into a Double or NaN.
 * number primitive -> its double; string primitive -> trimmed (empty counts as 0, like `Number("")`);
 * the literal booleans `true`/`false` -> 1/0; JSON `null` -> 0 (see [markHz] for the one place upstream
 * short-circuits null *before* this coercion instead); arrays/objects -> NaN.
 */
private fun coerce(v: JsonElement): Double = when (v) {
    is JsonNull -> 0.0
    is JsonArray -> Double.NaN
    is JsonObject -> Double.NaN
    is JsonPrimitive -> if (v.isString) {
        val t = v.content.trim()
        if (t.isEmpty()) 0.0 else t.toDoubleOrNull() ?: Double.NaN
    } else {
        when (v.content) {
            "true" -> 1.0
            "false" -> 0.0
            else -> v.content.toDoubleOrNull() ?: Double.NaN
        }
    }
    else -> Double.NaN
}

/** JS `num(v, min, max, fallback)` for a raw, dynamically-typed JSON value (a missing key -> fallback). */
private fun num(v: JsonElement?, min: Double, max: Double, fallback: Double): Double {
    if (v == null) return fallback
    val n = coerce(v)
    if (!n.isFinite()) return fallback
    return Math.min(max, Math.max(min, n))
}

/** Same clamp-or-fallback shape for values that are already typed as Double (public API entry points). */
private fun num(v: Double, min: Double, max: Double, fallback: Double): Double {
    if (!v.isFinite()) return fallback
    return Math.min(max, Math.max(min, v))
}

/** JS `round(v, step) = Math.round(v / step) * step`; half-up via java.lang.Math.round, bit-identical to JS. */
private fun round(v: Double, step: Double): Double = Math.round(v / step) * step

/** `num` with an implicit null fallback, for `fc` where an invalid value means "no band" rather than 0. */
private fun numOrNull(v: JsonElement?, min: Double, max: Double): Double? {
    if (v == null) return null
    val n = coerce(v)
    if (!n.isFinite()) return null
    return Math.min(max, Math.max(min, n))
}

/** JS `markHz(v)`: `v == null` (missing key OR explicit JSON null) short-circuits to null before `num()` runs. */
private fun markHz(v: JsonElement?): Double? {
    if (v == null || v is JsonNull) return null
    return numOrNull(v, Dsp.FMIN, Dsp.FMAX)
}

/** True only for the JSON boolean literal `false` (JS `x !== false`: strings/0/null/missing all stay enabled). */
private fun isExplicitFalse(v: JsonElement?): Boolean = v is JsonPrimitive && !v.isString && v.content == "false"

private fun strOrNull(v: JsonElement?): String? = if (v is JsonPrimitive && v.isString) v.content else null

private fun cleanBand(raw: JsonElement?): Band? {
    val obj = raw as? JsonObject ?: return null

    val typeElem = obj["type"]
    val type = if (typeElem is JsonPrimitive && typeElem.isString) {
        Store.TYPES.firstOrNull { it.name == typeElem.content } ?: BandType.PK
    } else {
        BandType.PK
    }

    val fc = numOrNull(obj["fc"], Dsp.FMIN, Dsp.FMAX) ?: return null

    val idElem = obj["id"]
    val id = if (idElem is JsonPrimitive && idElem.isString && idElem.content.isNotEmpty()) {
        idElem.content
    } else {
        Store.nextId()
    }

    val gain = round(num(obj["gain"], -Store.GAIN_LIMIT, Store.GAIN_LIMIT, 0.0), 0.1)
    val q = round(num(obj["q"], Store.Q_MIN, Store.Q_MAX, 1.0), 0.01)
    val enabled = !isExplicitFalse(obj["enabled"])

    return Band(id = id, type = type, fc = Math.round(fc).toDouble(), gain = gain, q = q, enabled = enabled)
}

/** Shared by `mark`/`commitDraft`: try to turn a draft into a band, respecting MAX_BANDS. Always normalizes. */
private fun commitWith(state: SessionState, draft: Draft, newId: () -> String): SessionState {
    val spec = Dsp.bandFromMarks(draft)
        ?: return Store.normalize(state.copy(draft = draft))
    if (state.bands.size >= Store.MAX_BANDS) {
        // Refused: drop the draft so the panel does not stay half-marked.
        return Store.normalize(state.copy(draft = Store.emptyDraft(draft.kind)))
    }
    val band = Band(id = newId(), type = spec.type, fc = spec.fc, gain = spec.gain, q = spec.q, enabled = true)
    return Store.normalize(
        state.copy(bands = state.bands + band, selectedId = band.id, draft = Store.emptyDraft(draft.kind))
    )
}

// -----------------------------------------------------------------------------------------------
// Public, pure reducer API. Each function returns the normalized next state (or `this` unchanged
// when upstream short-circuits before calling `set()`).
// -----------------------------------------------------------------------------------------------

fun SessionState.setFreq(hz: Double): SessionState {
    val f = Dsp.clampFreq(hz)
    if (f == freq) return this
    return Store.normalize(copy(freq = f))
}

fun SessionState.setPlaying(on: Boolean): SessionState {
    if (on == playing) return this
    return Store.normalize(copy(playing = on))
}

fun SessionState.setLevel(db: Double): SessionState {
    val v = Math.round(num(db, Store.LEVEL_MIN, Store.LEVEL_MAX, levelDb)).toDouble()
    if (v == levelDb) return this
    return Store.normalize(copy(levelDb = v))
}

fun SessionState.toggleEq(): SessionState = Store.normalize(copy(eqOn = !eqOn))

fun SessionState.setPreamp(db: Double): SessionState {
    val v = round(num(db, -Store.PREAMP_LIMIT, Store.PREAMP_LIMIT, preampDb), 0.1)
    return Store.normalize(copy(preampDb = v, preampAuto = false))
}

fun SessionState.setPreampAuto(): SessionState = Store.normalize(copy(preampAuto = true))

fun SessionState.setDraftKind(kind: MarkKind): SessionState {
    if (kind == draft.kind) return this
    return Store.normalize(copy(draft = draft.copy(kind = kind)))
}

/** Records the current frequency into the draft. Commits automatically once all three marks exist. */
fun SessionState.mark(which: Mark, newId: () -> String = Store::nextId): SessionState {
    val newDraft = when (which) {
        Mark.START -> draft.copy(start = freq)
        Mark.TOP -> draft.copy(top = freq)
        Mark.END -> draft.copy(end = freq)
    }
    return if (newDraft.start != null && newDraft.top != null && newDraft.end != null) {
        commitWith(this, newDraft, newId)
    } else {
        Store.normalize(copy(draft = newDraft))
    }
}

fun SessionState.commitDraft(newId: () -> String = Store::nextId): SessionState = commitWith(this, draft, newId)

fun SessionState.clearDraft(): SessionState {
    if (draft.start == null && draft.top == null && draft.end == null) return this
    return Store.normalize(copy(draft = Store.emptyDraft(draft.kind)))
}

/** Removes the last draft mark (end, then top, then start); with an empty draft, the newest band. */
fun SessionState.undo(): SessionState {
    val d = draft
    if (d.end != null) return Store.normalize(copy(draft = d.copy(end = null)))
    if (d.top != null) return Store.normalize(copy(draft = d.copy(top = null)))
    if (d.start != null) return Store.normalize(copy(draft = d.copy(start = null)))
    if (bands.isEmpty()) return this
    val gone = bands.last()
    val newBands = bands.dropLast(1)
    val newSelected = if (selectedId == gone.id) null else selectedId
    return Store.normalize(copy(bands = newBands, selectedId = newSelected))
}

fun SessionState.updateBand(id: String, patch: BandPatch): SessionState {
    val i = bands.indexOfFirst { it.id == id }
    if (i < 0) return this
    val prev = bands[i]
    val next = Band(
        id = prev.id,
        type = patch.type ?: prev.type,
        fc = if (patch.fc != null) Math.round(num(patch.fc, Dsp.FMIN, Dsp.FMAX, prev.fc)).toDouble() else prev.fc,
        gain = if (patch.gain != null) {
            round(num(patch.gain, -Store.GAIN_LIMIT, Store.GAIN_LIMIT, prev.gain), 0.1)
        } else {
            prev.gain
        },
        q = if (patch.q != null) round(num(patch.q, Store.Q_MIN, Store.Q_MAX, prev.q), 0.01) else prev.q,
        enabled = patch.enabled ?: prev.enabled,
    )
    if (next == prev) {
        if (!preampAuto) return this
        val auto = Store.autoPreampDb(bands)
        return if (auto == preampDb) this else copy(preampDb = auto)
    }
    val newBands = bands.toMutableList()
    newBands[i] = next
    return Store.normalize(copy(bands = newBands))
}

fun SessionState.removeBand(id: String): SessionState {
    if (bands.none { it.id == id }) return this
    val newBands = bands.filter { it.id != id }
    val newSelected = if (selectedId == id) null else selectedId
    return Store.normalize(copy(bands = newBands, selectedId = newSelected))
}

fun SessionState.selectBand(id: String?): SessionState {
    val v = if (id != null && bands.any { it.id == id }) id else null
    if (v == selectedId) return this
    return Store.normalize(copy(selectedId = v))
}

/** Replace the whole state, validating shape and clamping ranges. */
fun SessionState.load(raw: JsonElement?): SessionState = Store.validate(raw)
