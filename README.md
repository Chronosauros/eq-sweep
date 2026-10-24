<p align="center">
  <a href="https://eqsweep.app/"><img src="docs/images/teaser.png" width="820" alt="EQ Sweep - Tuning by ear, made easier. Now on Google Play."></a>
</p>

<p align="center">
  An Android app for tuning your headphones by ear.<br>
  <b>Now on Google Play - free, with no ads.</b><br>
  <a href="https://play.google.com/store/apps/details?id=app.eqsweep">Get it on Google Play</a> · <a href="https://eqsweep.app/">eqsweep.app</a>
</p>

## How it is released

- **The app is free, with no ads.**
- **Google Play:** [get EQ Sweep on Google Play](https://play.google.com/store/apps/details?id=app.eqsweep).
- **APK:** every version's APK is in [Releases](../../releases/latest). To get updates automatically, use
  [Obtainium](https://obtainium.imranr.dev/): tap Add app and paste `https://github.com/Chronosauros/eq-sweep`.
  The APK is signed with a different key than the Google Play version, so switching between the two means
  uninstalling first - your profiles carry over through a backup made with SAVE in the app.
- **The app is closed source.** The only code in this repository is the open-source [core](core/) - see below.
- News, and a version that runs in the browser: [eqsweep.app](https://eqsweep.app/) ([open it in the browser](https://eqsweep.app/web)).

## Licence and credits

EQ Sweep is built on **[eqbyear](https://github.com/DMS3tv/eqbyear)** by DMS
([eqbyear.com](https://eqbyear.com)), an open-source project released under the
[Apache License 2.0](https://github.com/DMS3tv/eqbyear/blob/main/LICENSE). Thank you to DMS for making it open.

- Four parts of eqbyear were ported from JavaScript to Kotlin for this app: the filter maths (`dsp.js`),
  the profile store (`store.js`), the export formats (`export.js`) and the test-tone synth (`audio.js`).
- **These four parts are open source.** The Kotlin port is published in the [core](core/) folder of this
  repository under the Apache License 2.0, with its tests that check it against the original JavaScript.
  It is pure Kotlin with no Android dependencies, so anyone building an EQ tool can use it.
- Every ported file keeps the Apache License 2.0, a note that it is derived from eqbyear and which file it
  came from. The app ships a full copy of the eqbyear licence and a credits and licences screen.
- Everything else - the interface, the design and the rest of the code - is original work and is not open source.

**EQ Sweep is an independent project. It is not affiliated with, sponsored by or endorsed by DMS or the
eqbyear project.** The names "DMS" and "EQ by ear" and their logo belong to their owner. They are used
here only to say where the code came from, and EQ Sweep does not use them as its name or brand.
