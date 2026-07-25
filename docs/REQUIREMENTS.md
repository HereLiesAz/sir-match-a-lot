# Resolved requirements

Every line in `docs/prompts.md`, reduced to a single non-contradictory
specification. Where a later prompt revised an earlier one, only the later
version appears; the superseded reading is noted so the history stays traceable.

Each item carries a status: **done**, **partial**, or **planned (phase N)**,
keyed to the phases in `docs/ARCHITECTURE.md`.

## A. Analysis — measured, never invented

| # | Requirement | Status |
| :-- | :--- | :--- |
| A1 | BPM detected from the audio signal | done (`dsp/TempoDetector`) |
| A2 | Beat phase and a beat grid, so sync has something to align to | done (`dsp/BeatGrid`) |
| A3 | Musical key detected from the audio, mapped to Camelot | done (`dsp/KeyDetector` + `HarmonicEngine.camelotFor`) |
| A4 | Energy over time, for the energy ring and track ranking | done (`dsp/EnergyCurve`) |
| A5 | Real peak envelope for drawing | done (`dsp/PeakEnvelope`) |
| A6 | Silence trimmed from the start and end of every track | done (`audio/AudioDecoder`) |
| A7 | Analysis runs over whole tracks, not excerpts | done (one decode feeds every measurement) |
| A8 | Loop candidates found automatically from the playlist's songs | done (`dsp/StructureFinder.findLoops`, bar-aligned self-similarity); surfaced in the sampler by "Fill from track" |
| A9 | Points of interest tagged (drop, break, vocal entry) | partial — drops, breakdowns, builds and peaks are detected and snapped to bar lines (`StructureFinder.findPointsOfInterest`); vocal detection would need a separate model |
| A10 | Replace the filename-hash and random-number "analysis" entirely | **done** — `ai/SongAnalyzer.kt` deleted, `analysis/TrackAnalyzer` measures from audio |

## B. Audio engine

| # | Requirement | Status |
| :-- | :--- | :--- |
| B1 | Own mixer; one output stream, not one player per clip | done (`audio/Mixer`, `audio/AudioEngine`); the ExoPlayer-per-clip path is deleted, so `AudioEngine` is now the only way audio reaches the output |
| B2 | Sample-accurate scratching | done (`dsp/Resampler` + `audio/ScratchModel`) |
| B3 | Deceleration through zero into reverse playback, on a non-linear curve | done (`audio/ScratchModel`, continuity tested) |
| B4 | Tempo change independent of pitch ("auto stretch") | done (`dsp/TimeStretcher`, WSOLA); quality gap 3 in AUDIO_QUALITY.md |
| B5 | Pitch shift independent of tempo ("auto pitch") | done (`dsp/PitchShifter`); large deliberate ratios still read through the spline — the remainder of gap 1 |
| B6 | Per-deck EQ and bass boost in the music's signal path | done (`audio/Deck`, routed and tested) |
| B7 | Crossfade with a correct gain law (no mid-fade level jump) | done (`audio/Crossfade`, equal power); the UI crossfader now drives it, instead of setting two linear per-player gains that dipped ~3 dB at centre |
| B8 | Beat sync that converges, driven from the audio thread not the UI | done — `BeatSync` computes the correction and `AudioEngine.applyAlignment` applies it once to the deck rate and playhead, rather than chasing drift with repeated seeks |
| B9 | Live output level published for the visuals | done (`audio/OutputLevel`, per deck and master) |
| B10 | Multiple clips per deck; loops placeable on a deck exactly like songs | done (`audio/Clip` on a circular timeline) |
| B11 | A single sample alone on a deck loops around the whole circle | done (tested) |
| B12 | Engine runs at the device's native rate; one high-quality SRC at load | **done** — `dsp/SincResampler` (Kaiser windowed-sinc polyphase, exact phase table) converts each track once in `PcmBuffer.resampledTo`, so the render loop runs at rate 1.0. Flat to 20 kHz within 0.08 dB, 108 dB alias rejection; measurements in AUDIO_QUALITY.md |
| B13 | Master limiter transparent at listening levels | done (-1 dBFS threshold, tested) |
| B14 | Hi-res (24-bit / float) source support | planned — gap 2 in AUDIO_QUALITY.md |

## C. The platter

| # | Requirement | Status |
| :-- | :--- | :--- |
| C1 | One circle, invisible except for the waveforms on it | done (`ui/platter/PlatterCanvas`) |
| C2 | Deck A protrudes outward, Deck B inward. Two rings, never concentric-per-track | done |
| C3 | Angle is time: one rotation = the deck cycle, derived from track duration | done (`PlatterGeometry`) |
| C4 | No circle drawn at the centre, and no bounding circles | done — nothing is drawn but rays, playhead and labels |
| C5 | Playhead: a glowing red slash centred on the ring, length = 2x the combined waveform height at that angle, so it bounces | done (tested) |
| C6 | Playhead collapses to a glowing dot when there is no waveform | done (tested) |
| C7 | Waveforms glow, brightening and growing with live output | done — driven by `Mixer.level`, not an oscillator |
| C8 | Deck B's waveform reaches inward toward the far side | done |
| C9 | Energy graph around the circle, repeating as needed, colour-coded | partial — colour path plumbed via `PlatterClip.energy`, but the analyser does not yet persist the curve, so it renders at neutral brightness |
| C10 | Cue markers visible on the ring | planned (phase 5) |
| C11 | The platter is the feature — not inside a card, not on a grey panel | done |
| C12 | Identical layout in portrait and landscape; song list along the bottom scrolling horizontally in portrait; navigation bar fixed | done — needs confirming on a device |
| C13 | Song list entries are draggable onto the platter | partial — tapping a row loads it; drag-to-place not implemented |
| C14 | No A/B buttons and no drag handle on song rows | done |
| C15 | Background is an out-of-focus rave light show, genuinely driven by the audio, not a strobe | planned (phase 6) |

## D. Gestures

Superseded: the original map (pinch = BPM, 1-finger vertical = pitch, 1-finger
horizontal = EQ, 2-finger rotate = overlap, 2-finger vertical = crossfade,
2-finger horizontal = seek, 3-finger rotate = spin, 3-finger pinch = volume).
Prompt 75 replaced it with D1–D6 below.

| # | Requirement | Status |
| :-- | :--- | :--- |
| D1 | All single-finger gestures manipulate the audio clips themselves | partial — recognised as `CLIP_DRAG`, but the clip operations it should drive are phase 5 |
| D2 | 2-finger horizontal = crossfader A to B | done |
| D3 | 2-finger vertical = smart scratch: seek, plus BPM and pitch falling on a non-linear curve through zero into reverse | done (`ScratchModel`, continuity tested) |
| D4 | 2-finger rotate = volume | done |
| D5 | 2-finger pinch/spread = bass boost | done — and it now reaches the music |
| D6 | 3-finger drag/pinch/rotate = place, resize, rotate the whole platter, to zoom in for precise work | done |
| D7 | Gestures are global — anywhere on screen, not only inside the circle, and they keep applying when the finger leaves it | done (tested at 2000 px from origin) |
| D8 | Two axes at once: two gestures may run simultaneously | done (tested) |
| D9 | A gesture ends when its defining conditions stop being met, not on finger-up | done (tested with fingers still down) |
| D10 | Single tap selects the waveform under the finger on that deck | done |
| D11 | Double tap selects both decks' waveforms at that spot | done |
| D12 | Long press removes the selected track(s) | done |
| D13 | Easter egg: scratching too far backward triggers a very low, growling "I am Satan, Lord of Darkness", slow at first then accelerating | partial — `ScratchModel` fires the trigger exactly once per gesture from one accumulator, tested; the growling voice itself is not yet synthesised |

## E. Gesture labels

| # | Requirement | Status |
| :-- | :--- | :--- |
| E1 | Label appears while the gesture runs, dissolves the instant it ends | done |
| E2 | Text only — no rounded box, no background | done |
| E3 | Clock-slot priority: 12:00, then 1:30, 10:30, 3:00, 9:00, 4:30, 7:30, and 6:00 last | done (tested) |
| E4 | A slot is reusable only once its previous label has finished dissolving | done (tested) |
| E5 | A gesture repeated mid-dissolve reclaims its own slot | done (tested) |
| E6 | Labels float upward from the moment they appear until gone or offscreen | done |

## F. Library, import, and mixing intelligence

| # | Requirement | Status |
| :-- | :--- | :--- |
| F1 | Local audio files supported | done — imported and measured |
| F2 | Any music service whose link resolves to a track or playlist | planned (phase 3) |
| F3 | A playlist link imports every track in it, not one | planned (phase 3) |
| F4 | Long imports run in the background with a progress notification, pausable and resumable | planned (phase 3) |
| F5 | Dropdown filter sorting the library by Camelot proximity to the Deck A track | done (`MixPlanner.byHarmonicProximity`, wired to the library sort chips) |
| F6 | Shuffle Crate: fills both decks by harmonic compatibility and BPM match | done (`MixPlanner.shuffleCrate`, weighted-random over usable pairs) |
| F7 | Automatchic Mix: builds a full pro-grade remix playlist using every tool in the app | partial — running order, transitions and per-step alignments are planned (`MixPlanner.automatchicMix`); executing the plan automatically is still to do |
| F8 | Auto beat sync, auto pitch, auto stretch, harmonize | **done** — `syncToDeckA` applies rate and phase through the engine and *renders* the pitch shift into the clip via `PcmBuffer.pitchShifted`, combining the harmonic interval with a keylock correction of `-12*log2(tempoRatio)` so a tempo match does not drag the key with it. Keylock is toggleable, because off is the turntable behaviour the scratch gestures depend on |
| F9 | No built-in audio clips; sample packs come from the Azphalt store at `azphalt.org` | partial — host corrected, packs import unanalysed; auto-download on first launch still to remove |
| F10 | Library stays as its own tab; the decks tab becomes the play/pause button | done (already true) |

## G. Sampler and looper

| # | Requirement | Status |
| :-- | :--- | :--- |
| G1 | Kaoss-pad / Kitara style expressive pad | done (`audio/MasterFilter` on the master bus, `ui/SamplerScreen`'s FilterPad). X is a bipolar DJ filter (centre bypass, left sweeps a lowpass down, right a highpass up, log-mapped); Y is resonance to Q 8 with `1/sqrt(Q)` compensation so a sweep does not slam the limiter. Placed before the limiter and after the crossfade, so it acts on the mix and its resonant peaks are still caught |
| G2 | Record to a pad and replay it | done (`audio/Sampler` + `ui/SamplerScreen`: arm record, hold a pad to capture, hold to replay) |
| G3 | Unused pads auto-filled with samples grabbed from loaded tracks | done (`Sampler.autoFill` from `StructureFinder` loop candidates, driven by the sampler's "Fill from track"; never overwrites an occupied pad) |
| G4 | Automatic loop maker sampling loops from the active playlist | partial — loops are found and can fill pads; running it across a whole playlist automatically is outstanding |
| G5 | The sampler/looper can occupy a deck slot, showing N loops the way songs are shown | planned — `Clip` already supports it; the UI to place a pad on a deck is outstanding |

## H. Reach

| # | Requirement | Status |
| :-- | :--- | :--- |
| H1 | Link multiple devices; each shows a different screen; all act as one instrument | planned (phase 6) |
| H2 | One-click auto-connect on the same Wi-Fi — requires a device to host, which does not exist yet | planned (phase 6) |
| H3 | Export the loaded session (two tracks, cue points, loop settings) as a shareable link with query parameters | planned (phase 6) |
| H4 | Expose the app's complete API | planned (phase 6) |
| H5 | Comprehensive documentation and a web page, describing the app that actually exists | done for the gesture map and platter behaviour — README and `web/` rewritten against §C/§D with an explicit status section; revisit as phases 5-6 land |

## Contradictions resolved

1. **Gesture map.** Prompt 23's map versus prompt 75's. Prompt 75 wins; §D is the
   only valid mapping. The README and web page carried prompt 23's until they were
   rewritten; both now document §D.
2. **Concentric rings.** Prompt 31 hints at stacking loops as rings; prompt 33
   rules it out explicitly — "there are only two decks". Two rings, and multiple
   clips subdivide a ring by angle.
3. **Long press.** Prompt 39 assigns removal to long press. The current code
   binds it to selection toggling instead. Removal wins (D12).
4. **Double tap.** Prompt 35 assigns both-deck selection to double tap. The
   current code binds it to play/pause. Selection wins (D11); play/pause has its
   own navigation-bar button per prompt 53.
5. **Overlap.** Overlap was a 2-finger rotate action in prompt 23; prompt 75
   reassigned rotate to volume, and prompt 75 makes all single-finger gestures
   clip manipulation — so overlap becomes a single-finger clip operation (D1).
6. **Bundled audio.** Early prompts assume built-in clips; prompt 83 item 6
   removes them all in favour of the store (F9).
