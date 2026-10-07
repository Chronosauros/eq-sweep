package app.eqsweep.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round-trip identity for the session persistence format: `sessionToJson` -> `sessionFromJson` ->
 * `validate` must reproduce the original state exactly (data class equality, so every field
 * including each band's `id` is checked), except that `playing` always comes back false - it is
 * never serialized in the first place (see [Export.sessionToJson]). Also checks that
 * re-serializing the round-tripped state reproduces byte-identical JSON.
 *
 * The hand-built states below only use `gain`/`q` values that are already fixed points of
 * [Store]'s own 0.1/0.01 grid rounding, and integral `fc` values - `cleanBand` re-applies that
 * rounding on the way back in, so a value that is not already on the grid (e.g. plain `0.7`,
 * which lands on `0.7000000000000001` after `Math.round(v / 0.01) * 0.01`) would make the
 * identity check below fail for a reason that has nothing to do with a real bug.
 */
class SessionRoundTripTest {

    /**
     * Same shape as `parity-vectors.json` `toPeqText[0].state`: 5 bands with one disabled (`b3`),
     * a "dip" draft, and a manual (non-auto) preamp of -3 dB. `playing = true` on purpose, to
     * prove it never survives the trip.
     */
    private val stateA = SessionState(
        version = 1,
        freq = 1234.5678,
        playing = true,
        levelDb = -18.0,
        eqOn = true,
        preampDb = -3.0,
        preampAuto = false,
        bands = listOf(
            Band(id = "b1", type = BandType.PK, fc = 3100.0, gain = -3.0, q = 2.5, enabled = true),
            Band(id = "b2", type = BandType.LSC, fc = 105.0, gain = 2.0, q = 0.71, enabled = true),
            Band(id = "b3", type = BandType.HSC, fc = 8000.0, gain = -3.0, q = 0.71, enabled = false),
            Band(id = "b4", type = BandType.PK, fc = 1000.0, gain = 3.0, q = 2.0, enabled = true),
            Band(id = "b5", type = BandType.PK, fc = 950.0, gain = -2.2, q = 2.7, enabled = true),
        ),
        selectedId = "b4",
        draft = Draft(kind = MarkKind.DIP, start = 2500.0, top = null, end = null),
        theme = "dark",
    )

    /**
     * 8 bands with `preampAuto = true`. Built via `Store.normalize` so the one
     * field that depends on the bands (`preampDb`) already matches what re-validating would
     * produce, making this state self-consistent before it ever goes through the round trip.
     */
    private val stateB = Store.normalize(
        SessionState(
            version = 1,
            freq = 440.0,
            playing = false,
            levelDb = -24.0,
            eqOn = false,
            preampDb = 0.0,
            preampAuto = true,
            bands = listOf(
                Band(id = "n1", type = BandType.LSC, fc = 40.0, gain = 4.0, q = 0.71, enabled = true),
                Band(id = "n2", type = BandType.PK, fc = 120.0, gain = -2.2, q = 1.2, enabled = true),
                Band(id = "n3", type = BandType.PK, fc = 300.0, gain = 3.5, q = 1.0, enabled = false),
                Band(id = "n4", type = BandType.PK, fc = 800.0, gain = -1.0, q = 2.0, enabled = true),
                Band(id = "n5", type = BandType.PK, fc = 2000.0, gain = 5.0, q = 1.5, enabled = true),
                Band(id = "n6", type = BandType.PK, fc = 4500.0, gain = -4.0, q = 0.9, enabled = true),
                Band(id = "n7", type = BandType.HSC, fc = 9000.0, gain = 2.0, q = 0.71, enabled = true),
                Band(id = "n8", type = BandType.PK, fc = 15000.0, gain = 6.0, q = 3.0, enabled = true),
            ),
            selectedId = "n5",
            draft = Draft(kind = MarkKind.PEAK, start = 500.0, top = 700.0, end = null),
            theme = "light",
        )
    )

    @Test
    fun defaultStateRoundTrips() {
        assertRoundTrips(Store.defaultState())
    }

    @Test
    fun fiveBandManualPreampStateRoundTrips() {
        assertRoundTrips(stateA)
    }

    @Test
    fun eightBandAutoPreampStateRoundTrips() {
        assertRoundTrips(stateB)
    }

    /**
     * `sessionToJson` -> `sessionFromJson` -> `validate` must reproduce [s] exactly except
     * `playing` (always forced to false), and re-serializing that result must byte-match the
     * original JSON.
     */
    private fun assertRoundTrips(s: SessionState) {
        val originalJson = Export.sessionToJson(s)
        val roundTripped = Store.validate(Export.sessionFromJson(originalJson))
        assertEquals(s.copy(playing = false), roundTripped, "round-tripped state differs from the original")
        assertEquals(originalJson, Export.sessionToJson(roundTripped), "re-serialized JSON differs from the original")
    }
}
