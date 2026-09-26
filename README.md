<p align="center">
  <img src="docs/images/teaser.png" width="820" alt="EQ Sweep - Tuning by ear, made easier. Coming soon.">
</p>

<p align="center">
  An Android app for tuning your headphones by ear.<br>
  <b>Coming soon to Google Play.</b>
</p>

## How it will be released

- **Google Play only**, starting with a beta test. There is no release date yet.
- **The app is closed source.** This repository is a showcase: it holds no code and no builds.
- Releases and news will be posted here when the beta opens.

## Licence and credits

EQ Sweep is built on **[eqbyear](https://github.com/DMS3tv/eqbyear)** by DMS
([eqbyear.com](https://eqbyear.com)), an open-source project released under the
[Apache License 2.0](https://github.com/DMS3tv/eqbyear/blob/main/LICENSE). Thank you to DMS for making it open.

- Four parts of eqbyear were ported from JavaScript to Kotlin for this app: the filter maths (`dsp.js`),
  the profile store (`store.js`), the export formats (`export.js`) and the test-tone synth (`audio.js`).
- Every ported file keeps the Apache License 2.0, a note that it is derived from eqbyear and which file it
  came from. The app ships a full copy of the eqbyear licence.
- A credits and licences screen inside the app will ship with the first public release.
- Everything else - the interface, the design and the new code - is original work and is not open source.

**EQ Sweep is an independent project. It is not affiliated with, sponsored by or endorsed by DMS or the
eqbyear project.** The names "DMS" and "EQ by ear" and their logo belong to their owner. They are used
here only to say where the code came from, and EQ Sweep does not use them as its name or brand.
