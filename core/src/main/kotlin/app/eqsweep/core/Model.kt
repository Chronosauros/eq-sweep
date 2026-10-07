/*
 * Derived from EQ by ear (https://github.com/DMS3tv/eqbyear), files js/store.js and js/dsp.js.
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
 * Ported from JavaScript (the state shapes of js/store.js and js/dsp.js) to Kotlin for
 * EQ Sweep, 2026; modified.
 */
package app.eqsweep.core

/**
 * Shared model for the DSP core. Ported 1:1 from upstream `js/store.js` / `js/dsp.js` shapes.
 * Numbers stay Double everywhere (JS semantics); `fc` is always integral Hz.
 */
enum class BandType { PK, LSC, HSC }

enum class MarkKind(val json: String) {
    PEAK("peak"),
    DIP("dip");

    companion object {
        /** JS rule: anything but "dip" is "peak". */
        fun fromJson(value: String?): MarkKind = if (value == "dip") DIP else PEAK
    }
}

data class Band(
    val id: String,
    val type: BandType,
    val fc: Double,
    val gain: Double,
    val q: Double,
    val enabled: Boolean = true,
)

/** Spec of a band before it gets an id (output of [Dsp.bandFromMarks]). */
data class BandSpec(
    val type: BandType,
    val fc: Double,
    val gain: Double,
    val q: Double,
)

data class Draft(
    val kind: MarkKind = MarkKind.PEAK,
    val start: Double? = null,
    val top: Double? = null,
    val end: Double? = null,
)

/** Which draft mark a user action targets. */
enum class Mark { START, TOP, END }

/**
 * Whole session, same fields as the upstream store state (theme kept only for JSON compatibility;
 * the app is dark-only and ignores it).
 */
data class SessionState(
    val version: Int = 1,
    val freq: Double = 1000.0,
    val playing: Boolean = false,
    val levelDb: Double = -18.0,
    val eqOn: Boolean = true,
    val preampDb: Double = 0.0,
    val preampAuto: Boolean = true,
    val bands: List<Band> = emptyList(),
    val selectedId: String? = null,
    val draft: Draft = Draft(),
    val theme: String = "light",
)
