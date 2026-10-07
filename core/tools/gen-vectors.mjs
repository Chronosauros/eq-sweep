// Generates parity vectors for the Kotlin :core port from the upstream JS (read-only clone).
// Run from anywhere:  EQBYEAR_DIR=/path/to/eqbyear node <core>/tools/gen-vectors.mjs
// Output: <core>/src/test/resources/parity-vectors.json (committed; Kotlin tests read it).
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..', '..', '..');
// EQBYEAR_DIR: a clone of https://github.com/DMS3tv/eqbyear (default: upstream-eqbyear/ at the repo root)
const up = path.join(process.env.EQBYEAR_DIR ?? path.join(root, 'upstream-eqbyear'), 'js');
if (!fs.existsSync(up)) throw new Error(`no eqbyear clone at ${up} - set EQBYEAR_DIR`);
const dsp = await import(pathToFileURL(path.join(up, 'dsp.js')).href);
const exp = await import(pathToFileURL(path.join(up, 'export.js')).href);
const store = await import(pathToFileURL(path.join(up, 'store.js')).href);

const OUT = path.join(here, '..', 'src', 'test', 'resources', 'parity-vectors.json');

const enc = (v) => (Number.isNaN(v) ? 'NaN' : v === Infinity ? 'Infinity' : v === -Infinity ? '-Infinity' : v);

// ---- frequencies: 45 log-spaced + edge cases --------------------------------
const FREQS = [];
for (let i = 0; i < 45; i++) FREQS.push(20 * Math.pow(1000, i / 44));
FREQS.push(20, 105, 440, 1000, 3100, 8000, 20000);

const BANDS = [
  { type: 'PK', fc: 3100, gain: -4.5, q: 2.6 },
  { type: 'PK', fc: 1000, gain: -3, q: 2 },
  { type: 'PK', fc: 105, gain: 6, q: 0.7 },
  { type: 'PK', fc: 8000, gain: -3, q: 10 },
  { type: 'PK', fc: 20, gain: 3, q: 0.3 },
  { type: 'PK', fc: 20000, gain: -6, q: 1.41 },
  { type: 'LSC', fc: 105, gain: 2, q: 0.7 },
  { type: 'LSC', fc: 250, gain: -4, q: 1 },
  { type: 'LSC', fc: 60, gain: 6, q: 0.5 },
  { type: 'HSC', fc: 8000, gain: -3, q: 0.7 },
  { type: 'HSC', fc: 3000, gain: 4, q: 1.2 },
  { type: 'HSC', fc: 12000, gain: -6, q: 2 },
];

const out = {};
out.constants = {
  FMIN: dsp.FMIN, FMAX: dsp.FMAX, FS: dsp.FS,
  MAJOR_TICKS: dsp.MAJOR_TICKS, MINOR_TICKS: dsp.MINOR_TICKS,
  MAX_BANDS: store.MAX_BANDS, TYPES: store.TYPES,
};

out.clampFreq = [NaN, Infinity, -Infinity, 5, 30000, 440, 20, 20000, 19.999, 20000.001, 0, -100, 1234.5678]
  .map((f) => ({ in: enc(f), out: dsp.clampFreq(f) }));

out.freqToX = [];
out.xToFreq = [];
for (const w of [800, 1000, 1440, 360.5]) {
  for (const f of [20, 105, 440, 1000, 3100, 8000, 20000, 33.3, 12345.678]) {
    out.freqToX.push({ f, w, x: dsp.freqToX(f, w) });
  }
  for (const x of [-50, 0, 1, w * 0.25, w * 0.5, w * 0.5663233347786729, w * 0.75, w - 1, w, w + 50]) {
    out.xToFreq.push({ x, w, f: dsp.xToFreq(x, w) });
  }
}

out.sampleFreqs320 = dsp.sampleFreqs(320);
out.sampleFreqs5 = dsp.sampleFreqs(5);

out.coeffs = BANDS.map((band) => ({ band, out: dsp.coeffs(band) }));

out.magnitude = BANDS.map((band) => ({
  band,
  points: FREQS.map((f) => [f, dsp.magnitudeDb(band, f)]),
}));

const RESP_SETS = [
  { bands: [] },
  { bands: [{ ...BANDS[0], enabled: true }, { ...BANDS[6], enabled: true }, { ...BANDS[9], enabled: false }] },
  { bands: BANDS.map((b, i) => ({ ...b, enabled: i % 3 !== 2 })) },
  { bands: [{ ...BANDS[1] }] }, // enabled undefined counts as enabled
];
out.response = RESP_SETS.map(({ bands }) => ({
  bands,
  points: FREQS.map((f) => [f, dsp.responseDb(bands, f)]),
}));

const DRAFTS = [
  { start: 2500, top: 3100, end: 3750, kind: 'peak' },
  { start: 2500, top: 3100, end: 3750, kind: 'dip' },
  { start: 3750, top: 3100, end: 2500, kind: 'peak' },
  { start: 2500, top: null, end: 3750, kind: 'peak' },
  { start: null, top: 3100, end: null, kind: 'dip' },
  { start: 2500, top: null, end: null, kind: 'peak' },
  { start: null, top: null, end: 3750, kind: 'peak' },
  { start: null, top: null, end: null, kind: 'peak' },
  { start: 1000, top: 1000, end: 1000, kind: 'peak' },
  { start: 990, top: 1000, end: 1010, kind: 'peak' },
  { start: 20, top: 1000, end: 20000, kind: 'peak' },
  { start: 100.4, top: 1234.56, end: 5000.5, kind: 'dip' },
  { start: 3000, top: 3000.5, end: 4200, kind: 'peak' },
  { start: 300, top: 447.2, end: 700, kind: 'peak' },
];
out.bandFromMarks = DRAFTS.map((draft) => ({ draft, out: dsp.bandFromMarks(draft) }));

const PRE_SETS = [
  [],
  [{ fc: 105, gain: 2, q: 0.7, type: 'LSC', enabled: true }, { fc: 3100, gain: -4.5, q: 2.6, type: 'PK', enabled: true }],
  [{ fc: 105, gain: 2, q: 0.7, type: 'LSC', enabled: false }, { fc: 3100, gain: -4.5, q: 2.6, type: 'PK', enabled: true }],
  [{ fc: 1000, gain: 3, q: 2, type: 'PK', enabled: true }, { fc: 2000, gain: 5.55, q: 2, type: 'PK', enabled: true }],
  [{ fc: 1000, gain: 0.04, q: 2, type: 'PK', enabled: true }],
  [{ fc: 1000, gain: 0.05, q: 2, type: 'PK', enabled: true }],
  [{ fc: 1000, gain: -3, q: 2, type: 'PK' }],
  [{ fc: 1000, gain: 12.34, q: 2, type: 'PK', enabled: true }, { fc: 5000, gain: 12.35, q: 2, type: 'PK', enabled: true }],
];
out.autoPreamp = PRE_SETS.map((bands) => ({ bands, out: dsp.autoPreamp(bands) }));

out.fmtHz = [3100, 20, 999, 1000, 1000.4, 1000.5, 12345.678, 20000, 0, -1500, -20, 100000, 1234567].map((f) => ({ in: f, out: dsp.fmtHz(f) }));
out.fmtK = [20, 105, 999, 999.6, 1000, 1000.4, 1050, 1234, 1500, 2500, 3100, 4287, 8000, 10000, 12137, 19871, 20000, 2.5, 0].map((f) => ({ in: f, out: dsp.fmtK(f) }));

// ---- export -----------------------------------------------------------------
const stateA = {
  ...store.defaultState(),
  preampDb: -3,
  preampAuto: false,
  bands: [
    { id: 'b1', type: 'PK', fc: 3100, gain: -3, q: 2.5, enabled: true },
    { id: 'b2', type: 'LSC', fc: 105, gain: 2, q: 0.7, enabled: true },
    { id: 'b3', type: 'HSC', fc: 8000, gain: -3, q: 0.7, enabled: false },
    { id: 'b4', type: 'PK', fc: 1000, gain: 3, q: 2, enabled: true },
    { id: 'b5', type: 'PK', fc: 999.6, gain: -2.25, q: 2.675, enabled: true },
  ],
  selectedId: 'b4',
  draft: { kind: 'dip', start: 2500, top: null, end: null },
  freq: 1234.5678,
  levelDb: -18,
  theme: 'dark',
};
const stateB = { ...store.defaultState() };
const stateC = { ...store.defaultState(), preampDb: 0, preampAuto: true, bands: [{ id: 'x', type: 'PK', fc: 440, gain: -3, q: 2, enabled: true }] };
const stateD = { ...store.defaultState(), preampDb: -0, bands: [{ id: 'y', type: 'PK', fc: 440, gain: 0, q: 1, enabled: true }] };
out.toPeqText = [stateA, stateB, stateC, stateD].map((state) => ({ state, out: exp.toPeqText(state) }));
out.sessionToJson = [stateA, stateB, stateC].map((state) => ({ state, out: exp.sessionToJson(state) }));

const fromCases = [
  exp.sessionToJson(stateA),
  '{"bands":[]}',
  '{"version":1,"bands":[{"fc":"440","gain":1}]}',
  '[]',
  '"str"',
  '{"version":1}',
  '{"bands":[{"gain":1}]}',
  '{"bands":[null]}',
  '{"bands":[{"fc":"abc"}]}',
  'not json',
];
out.sessionFromJson = fromCases.map((text) => {
  try {
    exp.sessionFromJson(text);
    return { text, ok: true };
  } catch (e) {
    return { text, ok: false };
  }
});

// ---- store: validate + scripted reducer -----------------------------------
function snap(s) {
  const bands = s.bands.map(({ id, ...rest }) => rest);
  const selectedIndex = s.selectedId == null ? null : s.bands.findIndex((b) => b.id === s.selectedId);
  return {
    version: s.version, freq: s.freq, playing: s.playing, levelDb: s.levelDb, eqOn: s.eqOn,
    preampDb: s.preampDb, preampAuto: s.preampAuto, bands, selectedIndex: selectedIndex === -1 ? null : selectedIndex,
    draft: s.draft, theme: s.theme,
  };
}

const RAW = [
  null,
  {},
  { freq: 'abc', levelDb: 5, preampDb: -99, bands: 'nope', draft: 7, theme: 'dark', eqOn: false, preampAuto: false },
  { freq: 12.5, levelDb: -70, preampDb: 3.14159, preampAuto: true, bands: [
    { id: 'k1', type: 'LSC', fc: 105.4, gain: 25, q: 0.001, enabled: false },
    { id: '', type: 'XX', fc: '3100', gain: '-4.55', q: '2.675' },
    { fc: 50000 }, null, 'junk', { type: 'HSC', fc: 8000, gain: -3, q: 0.7, enabled: 0 },
  ], selectedId: 'k1', draft: { kind: 'dip', start: 10, top: '3100', end: 'x' } },
  { bands: Array.from({ length: 12 }, (_, i) => ({ id: `m${i}`, type: 'PK', fc: 100 * (i + 1), gain: i - 4, q: 1 })), selectedId: 'm9', preampAuto: true },
  { bands: [{ id: 'a', type: 'PK', fc: 1000, gain: 2.25, q: 1.005 }], selectedId: 'a', preampAuto: false, preampDb: 1.25, levelDb: -12.6, freq: 20000.5 },
];
out.validate = RAW.map((raw) => ({ raw, out: snap(store.validate(raw)) }));

const s = store.createStore(null);
const steps = [];
function step(action, args, fn) {
  fn();
  steps.push({ action, args, state: snap(s.get()) });
}
step('init', [], () => {});
step('setFreq', [2500], () => s.setFreq(2500));
step('mark', ['start'], () => s.mark('start'));
step('setFreq', [3100], () => s.setFreq(3100));
step('mark', ['top'], () => s.mark('top'));
step('setFreq', [3750], () => s.setFreq(3750));
step('mark', ['end'], () => s.mark('end'));
step('setDraftKind', ['dip'], () => s.setDraftKind('dip'));
step('setFreq', [6000], () => s.setFreq(6000));
step('mark', ['start'], () => s.mark('start'));
step('setFreq', [7000], () => s.setFreq(7000));
step('mark', ['end'], () => s.mark('end'));
step('setFreq', [6400.7], () => s.setFreq(6400.7));
step('mark', ['top'], () => s.mark('top'));
step('setLevel', [-12.4], () => s.setLevel(-12.4));
step('setLevel', [-99], () => s.setLevel(-99));
step('setLevel', [0], () => s.setLevel(0));
step('setPreamp', [1.25], () => s.setPreamp(1.25));
step('setPreamp', [-30], () => s.setPreamp(-30));
step('setPreampAuto', [], () => s.setPreampAuto());
step('toggleEq', [], () => s.toggleEq());
step('toggleEq', [], () => s.toggleEq());
step('setPlaying', [true], () => s.setPlaying(true));
step('undo', [], () => s.undo());
step('mark', ['start'], () => s.mark('start'));
step('mark', ['end'], () => s.mark('end'));
step('undo', [], () => s.undo());
step('undo', [], () => s.undo());
step('undo', [], () => s.undo());
step('undo', [], () => s.undo());
step('setFreq', [440], () => s.setFreq(440));
step('mark', ['top'], () => s.mark('top'));
step('commitDraft', [], () => s.commitDraft());
step('mark', ['start'], () => s.mark('start'));
step('clearDraft', [], () => s.clearDraft());
step('clearDraft', [], () => s.clearDraft());
step('updateBand', [0, { gain: 2.55, q: 3.14159, type: 'LSC', fc: 999.4 }], () => s.updateBand(s.get().bands[0].id, { gain: 2.55, q: 3.14159, type: 'LSC', fc: 999.4 }));
step('updateBand', [0, { type: 'BAD', enabled: false }], () => s.updateBand(s.get().bands[0].id, { type: 'BAD', enabled: false }));
step('updateBand', [0, { enabled: true, gain: 99 }], () => s.updateBand(s.get().bands[0].id, { enabled: true, gain: 99 }));
step('selectBand', [0], () => s.selectBand(s.get().bands[0].id));
step('selectBand', ['nope'], () => s.selectBand('nope'));
step('setDraftKind', ['peak'], () => s.setDraftKind('peak'));
for (let i = 0; i < 9; i++) {
  step('setFreq', [100 * (i + 2)], () => s.setFreq(100 * (i + 2)));
  step('mark', ['top'], () => s.mark('top'));
  step('mark', ['start'], () => s.mark('start'));
  step('mark', ['end'], () => s.mark('end'));
}
step('removeBand', [1], () => s.removeBand(s.get().bands[1].id));
step('removeBand', ['nope'], () => s.removeBand('nope'));
step('selectBand', [0], () => s.selectBand(s.get().bands[0].id));
step('removeBand', [0], () => s.removeBand(s.get().bands[0].id));
step('load', [RAW[3]], () => s.load(RAW[3]));
step('setPreampAuto', [], () => s.setPreampAuto());
step('setFreq', ['2000'], () => s.setFreq('2000'));
step('setFreq', [NaN], () => s.setFreq(NaN));
out.storeScript = steps;

fs.writeFileSync(OUT, JSON.stringify(out, null, 1) + '\n');
let count = 0;
for (const k of Object.keys(out)) count += Array.isArray(out[k]) ? out[k].length : 1;
console.log(`wrote ${OUT} (${count} top-level cases, ${fs.statSync(OUT).size} bytes)`);
