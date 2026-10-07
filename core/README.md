# EQ Sweep core

The open-source building blocks of [EQ Sweep](https://eqsweep.app/), an Android app for tuning headphones by ear.
They are a Kotlin port of four parts of **[eqbyear](https://github.com/DMS3tv/eqbyear)** by DMS, released under the
[Apache License 2.0](LICENSE) like the original. The app itself is closed source; this folder is the part of it
that may be useful to others.

Pure Kotlin/JVM with no Android dependencies, so it runs anywhere the JVM does, and every part is covered by unit
tests on the plain JVM.

## What is inside

| File | Ported from | What it does |
| --- | --- | --- |
| `core/Dsp.kt` | `js/dsp.js` | Biquad coefficients for peak, low-shelf and high-shelf filters (RBJ cookbook), magnitude and summed response, log frequency axis, a band from three sweep marks, automatic preamp from the summed response |
| `core/Model.kt` | `js/store.js`, `js/dsp.js` | Data model: bands, marks, session state |
| `core/Store.kt` | `js/store.js` | Session state: validation of any JSON input, band editing, marks, undo |
| `core/Export.kt` | `js/export.js` | Export as a parametric EQ text, a GraphicEQ line, and the session JSON (with import) |
| `audio/ToneSynth.kt` | `js/audio.js` | The test-tone engine: a sine through the EQ chain with smoothed parameters, rendered into a float buffer - wire it to `AudioTrack` or any other output |

All paths are under `src/main/kotlin/app/eqsweep/`. Every ported file starts with a header naming the upstream file
it came from.

## Differences from eqbyear

The port matches the JavaScript original; the parity tests below prove it number by number. Where EQ Sweep changed
the behaviour on purpose, the code and the tests say so:

- up to 10 bands instead of 8;
- preamp limited to +-12 dB instead of +-24;
- the automatic preamp follows the summed response of all bands instead of the largest single band;
- the tone synth also runs at sample rates other than 48 kHz (the centre frequency is pre-warped).

## Build and test

Needs JDK 17 or newer.

```bash
./gradlew test
```

`DspParityTest`, `StoreParityTest` and `ExportParityTest` compare the Kotlin code with vectors generated from the
upstream JavaScript (`src/test/resources/parity-vectors.json`, from eqbyear commit `8ea6386`, 10.09.2026).
To regenerate them from a fresh clone of eqbyear:

```bash
EQBYEAR_DIR=/path/to/eqbyear node tools/gen-vectors.mjs
```

## Licence

Apache License 2.0 - see [LICENSE](LICENSE) and [NOTICE](NOTICE). This licence covers only this `core/` folder,
not the rest of the EQ Sweep app.

**EQ Sweep is an independent project. It is not affiliated with, sponsored by or endorsed by DMS or the eqbyear
project.** The names "DMS" and "EQ by ear" and their logo belong to their owner and are not licensed for use on
derived works. They are used here only to say where the code came from. Thank you to DMS for making eqbyear open.
