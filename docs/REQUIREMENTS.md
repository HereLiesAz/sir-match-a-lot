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
| A8 | Loop candidates found automatically from the playlist's songs | planned (phase 3, `dsp/LoopFinder`) |
| A9 | Points of interest tagged (drop, break, vocal entry) | planned (phase 3) |
| A10 | Replace the filename-hash and random-number "analysis" entirely | **done** — `ai/SongAnalyzer.kt` deleted, `analysis/TrackAnalyzer` measures from audio |

## B. Audio engine

| # | Requirement | Status |
| :-- | :--- | :--- |
| B1 | Own mixer; one output stream, not one player per clip | done (`audio/Mixer`, `audio/AudioEngine`) |
| B2 | Sample-accurate scratching | done (`dsp/Resampler` + `audio/ScratchModel`) |
| B3 | Deceleration through zero into reverse playback, on a non-linear curve | done (`audio/ScratchModel`, continuity tested) |
| B4 | Tempo change independent of pitch ("auto stretch") | done (`dsp/TimeStretcher`, WSOLA); quality gap 3 in AUDIO_QUALITY.md |
| B5 | Pitch shift independent of tempo ("auto pitch") | done (`dsp/PitchShifter`); quality gap 1 |
| B6 | Per-deck EQ and bass boost in the music's signal path | done (`audio/Deck`, routed and tested) |
| B7 | Crossfade with a correct gain law (no mid-fade level jump) | done (`audio/Crossfade`, equal power) |
| B8 | Beat sync that converges, driven from the audio thread not the UI | planned (phase 5) |
| B9 | Live output level published for the visuals | done (`audio/OutputLevel`, per deck and master) |
| B10 | Multiple clips per deck; loops placeable on a deck exactly like songs | done (`audio/Clip` on a circular timeline) |
| B11 | A single sample alone on a deck loops around the whole circle | done (tested) |
| B12 | Engine runs at the device's native rate; one high-quality SRC at load | partial — native rate done, SRC quality is gap 1 |
| B13 | Master limiter transparent at listening levels | done (-1 dBFS threshold, tested) |
| B14 | Hi-res (24-bit / float) source support | planned — gap 2 in AUDIO_QUALITY.md |

## C. The platter

| # | Requirement | Status |
| :-- | :--- | :--- |
| C1 | One circle, invisible except for the waveforms on it | planned (phase 4) |
| C2 | Deck A protrudes outward, Deck B inward. Two rings, never concentric-per-track | planned (phase 4) |
| C3 | Angle is time: one rotation = the deck cycle, derived from track duration | planned (phase 4) |
| C4 | No circle drawn at the centre, and no bounding circles | planned (phase 4) |
| C5 | Playhead: a glowing red slash centred on the ring, length = 2x the combined waveform height at that angle, so it bounces | planned (phase 4) |
| C6 | Playhead collapses to a glowing dot when there is no waveform | planned (phase 4) |
| C7 | Waveforms glow, brightening and growing with live output | planned (phase 4) |
| C8 | Deck B's waveform reaches inward toward the far side | planned (phase 4) |
| C9 | Energy graph around the circle, repeating as needed, colour-coded | planned (phase 4) |
| C10 | Cue markers visible on the ring | planned (phase 4) |
| C11 | The platter is the feature — not inside a card, not on a grey panel | planned (phase 4) |
| C12 | Identical layout in portrait and landscape; song list along the bottom scrolling horizontally in portrait; navigation bar fixed | planned (phase 4) |
| C13 | Song list entries are draggable onto the platter | planned (phase 4) |
| C14 | No A/B buttons and no drag handle on song rows | planned (phase 4) |
| C15 | Background is an out-of-focus rave light show, genuinely driven by the audio, not a strobe | planned (phase 6) |

## D. Gestures

Superseded: the original map (pinch = BPM, 1-finger vertical = pitch, 1-finger
horizontal = EQ, 2-finger rotate = overlap, 2-finger vertical = crossfade,
2-finger horizontal = seek, 3-finger rotate = spin, 3-finger pinch = volume).
Prompt 75 replaced it with D1–D6 below.

| # | Requirement | Status |
| :-- | :--- | :--- |
| D1 | All single-finger gestures manipulate the audio clips themselves | planned (phase 4) |
| D2 | 2-finger horizontal = crossfader A to B | planned (phase 4) |
| D3 | 2-finger vertical = smart scratch: seek, plus BPM and pitch falling on a non-linear curve through zero into reverse | planned (phase 4) |
| D4 | 2-finger rotate = volume | planned (phase 4) |
| D5 | 2-finger pinch/spread = bass boost | planned (phase 4) |
| D6 | 3-finger drag/pinch/rotate = place, resize, rotate the whole platter, to zoom in for precise work | planned (phase 4) |
| D7 | Gestures are global — anywhere on screen, not only inside the circle, and they keep applying when the finger leaves it | planned (phase 4) |
| D8 | Two axes at once: two gestures may run simultaneously | planned (phase 4) |
| D9 | A gesture ends when its defining conditions stop being met, not on finger-up | planned (phase 4) |
| D10 | Single tap selects the waveform under the finger on that deck | planned (phase 4) |
| D11 | Double tap selects both decks' waveforms at that spot | planned (phase 4) |
| D12 | Long press removes the selected track(s) | planned (phase 4) |
| D13 | Easter egg: scratching too far backward triggers a very low, growling "I am Satan, Lord of Darkness", slow at first then accelerating | planned (phase 4); one accumulator, not two |

## E. Gesture labels

| # | Requirement | Status |
| :-- | :--- | :--- |
| E1 | Label appears while the gesture runs, dissolves the instant it ends | planned (phase 4) |
| E2 | Text only — no rounded box, no background | planned (phase 4) |
| E3 | Clock-slot priority: 12:00, then 1:30, 10:30, 3:00, 9:00, 4:30, 7:30, and 6:00 last | planned (phase 4) |
| E4 | A slot is reusable only once its previous label has finished dissolving | planned (phase 4) |
| E5 | A gesture repeated mid-dissolve reclaims its own slot | planned (phase 4) |
| E6 | Labels float upward from the moment they appear until gone or offscreen | planned (phase 4) |

## F. Library, import, and mixing intelligence

| # | Requirement | Status |
| :-- | :--- | :--- |
| F1 | Local audio files supported | done — imported and measured |
| F2 | Any music service whose link resolves to a track or playlist | planned (phase 3) |
| F3 | A playlist link imports every track in it, not one | planned (phase 3) |
| F4 | Long imports run in the background with a progress notification, pausable and resumable | planned (phase 3) |
| F5 | Dropdown filter sorting the library by Camelot proximity to the Deck A track | planned (phase 5) |
| F6 | Shuffle Crate: fills both decks by harmonic compatibility and BPM match | planned (phase 5) |
| F7 | Automatchic Mix: builds a full pro-grade remix playlist using every tool in the app | planned (phase 5) |
| F8 | Auto beat sync, auto pitch, auto stretch, harmonize | planned (phase 5), on measured data |
| F9 | No built-in audio clips; sample packs come from the Azphalt store at `azphalt.org` | partial — host corrected, packs import unanalysed; auto-download on first launch still to remove |
| F10 | Library stays as its own tab; the decks tab becomes the play/pause button | done (already true) |

## G. Sampler and looper

| # | Requirement | Status |
| :-- | :--- | :--- |
| G1 | Kaoss-pad / Kitara style expressive pad | partial — a pad exists but drives a detached sine synth |
| G2 | Record to a pad and replay it | planned (phase 5) |
| G3 | Unused pads auto-filled with samples grabbed from loaded tracks | planned (phase 5) |
| G4 | Automatic loop maker sampling loops from the active playlist | planned (phase 5) |
| G5 | The sampler/looper can occupy a deck slot, showing N loops the way songs are shown | planned (phase 5) |

## H. Reach

| # | Requirement | Status |
| :-- | :--- | :--- |
| H1 | Link multiple devices; each shows a different screen; all act as one instrument | planned (phase 6) |
| H2 | One-click auto-connect on the same Wi-Fi — requires a device to host, which does not exist yet | planned (phase 6) |
| H3 | Export the loaded session (two tracks, cue points, loop settings) as a shareable link with query parameters | planned (phase 6) |
| H4 | Expose the app's complete API | planned (phase 6) |
| H5 | Comprehensive documentation and a web page, describing the app that actually exists | planned (phase 7) — README and `web/` currently document the superseded gesture map |

## Contradictions resolved

1. **Gesture map.** Prompt 23's map versus prompt 75's. Prompt 75 wins; §D is the
   only valid mapping. The README and web page still carry prompt 23's and are
   wrong until phase 7.
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
