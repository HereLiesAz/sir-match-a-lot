# Sir Match-a-Lot — architecture and build plan

Derived from the requirements in `docs/prompts.md`. Where later prompts revised
earlier ones, the later wins; `docs/REQUIREMENTS.md` records the resolved set.

## The one decision everything else follows from

The app needs, simultaneously: sample-accurate scratching, playback through zero
into reverse, tempo change independent of pitch, per-deck EQ, N clips mixed per
deck, and a live output level driving the visuals.

No off-the-shelf Android player does this. `ExoPlayer` cannot play at a negative
rate, and its seek granularity makes scratching impossible. So the core of this
app is **our own mixer**: audio is decoded once to PCM, held in memory, and
rendered by a pull-based graph on a dedicated thread feeding one `AudioTrack`.

Everything else — the platter, the gestures, the sync, the sampler — is a
controller for that graph.

### Kotlin, not NDK

The render loop is pure Kotlin against `AudioTrack` in `MODE_STREAM` with
`PERFORMANCE_MODE_LOW_LATENCY`. This costs roughly 10–25 ms of latency versus an
Oboe/AAudio C++ path, and requires care with allocation (no allocation inside the
callback; preallocated float buffers, primitive arrays only).

The tradeoff is deliberate: the DSP becomes ordinary Kotlin that runs on the JVM,
so tempo detection, key detection, and time-stretching are covered by fast unit
tests instead of requiring an instrumented device. For a gesture-driven app where
scratch feedback is visual as well as audible, that is the right trade. The graph
is written behind an interface so an Oboe backend can replace the output stage
later without touching the DSP or the UI.

## Module layout

```
app/src/main/java/com/hereliesaz/sirmatchalot/
  dsp/          pure math, zero Android deps, JVM-unit-tested
    Fft.kt              radix-2 Cooley-Tukey, in-place, preallocated
    Window.kt           Hann/Hamming
    Biquad.kt           RBJ cookbook: low/high shelf, peaking, lowpass
    TimeStretch.kt      WSOLA — tempo independent of pitch
    Resampler.kt        Catmull-Rom fractional read, signed rate (reverse)
    OnsetDetector.kt    spectral flux envelope
    TempoDetector.kt    comb-filter/autocorr over the envelope -> BPM + phase
    BeatGrid.kt         downbeat inference, beat times
    KeyDetector.kt      chromagram + Krumhansl-Schmuckler -> key
    EnergyCurve.kt      per-window loudness+spectral centroid -> energy 0..1
    LoopFinder.kt       bar-aligned self-similarity -> loop candidates
    PeakEnvelope.kt     min/max peak reduction for drawing

  audio/        the real-time graph
    PcmBuffer.kt        immutable decoded mono/stereo float PCM + sample rate
    AudioDecoder.kt     MediaCodec -> PcmBuffer (+ silence trim)  [evolved from
                        the existing AudioWaveformExtractor]
    Clip.kt             a PcmBuffer placed on a deck: offset, loop, gain, cues
    Deck.kt             playhead (double, signed rate), clip list, EQ, gain
    Mixer.kt            sums decks, master gain, limiter, level meter
    AudioEngine.kt      AudioTrack render thread; the only real-time context
    ScratchModel.kt     gesture delta -> non-linear rate curve through zero
    Sampler.kt          pads: record from master bus, replay, auto-fill
    Metering.kt         lock-free level + spectrum snapshot for the UI

  analysis/     orchestration of dsp over a library
    TrackAnalyzer.kt    decode once -> BPM, key, beatgrid, energy, peaks, cues
    AnalysisQueue.kt    bounded parallel, cancellable, resumable
    ImportService.kt    foreground service: notification, pause/resume, progress

  data/
    Track.kt, TrackDao.kt, AppDatabase.kt   (proper migrations, stable ids)
    Library.kt          repository
    LinkParser.kt       [kept]
    playlist/           M3U/PLS/CSV + link-based playlist expansion
    store/AzphaltStore.kt

  domain/
    HarmonicEngine.kt   [kept] Camelot wheel
    MixPlanner.kt       Shuffle Crate + Automatchic Mix
    SessionLink.kt      share/restore a session as query params

  gesture/
    GestureEngine.kt    concurrent multi-axis recognition, global
    GestureLabels.kt    clock-position label placement, float-up + dissolve

  ui/
    platter/            the feature: rings, waveforms, playhead, energy ring
    visualizer/         audio-reactive background
    library/, sampler/, ...
```

## The audio graph

```
Deck A: clips -> resample(rate, signed) -> WSOLA(tempoRatio) -> EQ -> gain ┐
                                                                          ├-> crossfade -> master gain -> limiter -> AudioTrack
Deck B: clips -> resample(rate, signed) -> WSOLA(tempoRatio) -> EQ -> gain ┘                                    └-> level/spectrum -> UI
```

Rules for the render thread:
- One thread, `THREAD_PRIORITY_URGENT_AUDIO`. Never blocks, never allocates.
- Control changes arrive through `AtomicReference` snapshots or a lock-free
  command ring buffer, applied at buffer boundaries; gains are smoothed
  per-sample toward the target to avoid zipper noise.
- Metering publishes into a preallocated double-buffered array the UI reads.

### Playhead and reverse

The playhead is a `double` sample position advanced by a signed rate:

```
position += rate * (deckSampleRate / outputSampleRate)
```

`rate < 0` reads backward; the fractional read is Catmull-Rom interpolated, so it
is continuous through zero with no click. `ScratchModel` maps the 2-finger
vertical gesture to `rate` on a non-linear curve: pushing forward accelerates,
pulling back decelerates through 0 into negative, and the *pitch* follows the
rate (as a real turntable) while the WSOLA stage is bypassed during scratch —
because during a scratch you want the pitch artefact, that's the sound. Away from
scratch, tempo and pitch are decoupled.

Prompt 77's easter egg lives here: `ScratchModel` accumulates reverse distance and
raises a single event once past the threshold. One implementation, one accumulator.

## The platter

One circle, invisible except for what is drawn on it. Radius `R`.

- **Deck A** waveforms protrude **outward** from `R`; **Deck B** protrude
  **inward**. Two rings only — never concentric-per-track.
- Each deck's clips divide the circumference proportionally to duration. One clip
  alone occupies the full 360° and loops (prompt 37).
- **Angle is time.** One full rotation = the deck cycle length (the longest
  loaded timeline). Angle→time and time→angle are a single pair of functions used
  by the playhead, the waveform lookup, scrubbing, and cue markers — the
  hardcoded `10f` that made the old playhead meaningless is gone.
- **Waveform** is drawn from a real min/max peak envelope at the screen's angular
  resolution, its amplitude scaled by the deck's *current* metered level, so it
  careens with the music. Glow is three stacked strokes (wide/dim, mid, bright
  core) with the alpha driven by level.
- **Playhead**: a red glowing slash centred on `R`, total length =
  2 × (current outward height + current inward height) at that angle, so it
  bounces over the hills. Zero waveform → a glowing dot. Nothing else is drawn at
  the centre.
- **Energy ring**: the energy curve drawn around the circumference, repeating as
  needed, colour-coded, reshapable by gesture.
- Cue markers and detected points of interest (drop, break, vocal-in) as ticks.

## Gestures

A single global recogniser, above everything except direct audio-clip
manipulation. Not a `when (pointerCount)`.

Each frame, from the active pointer set, the engine computes candidate *axes* —
centroid dx, centroid dy, span change, twist, per-finger count — and lets
**multiple gestures be active at once** (prompt 27, 67). A gesture ends the moment
its defining conditions stop being met, not on pointer-up (prompt 65): each
recogniser has enter/exit hysteresis on its own axis, so releasing into a
different gesture ends the first cleanly.

Resolved mapping (prompt 75 supersedes the earlier map):

| Gesture | Action |
| :--- | :--- |
| 1 finger | manipulate the audio clips themselves (select, move, trim, overlap) |
| 2 finger horizontal | crossfader A↔B |
| 2 finger vertical | smart scratch — seek + rate + pitch through zero into reverse |
| 2 finger rotate | volume (knob feel) |
| 2 finger pinch/spread | bass boost |
| 3 finger drag / pinch / rotate | move, scale, rotate the whole platter |
| single tap | select the waveform under the finger on that deck |
| double tap | select both decks' waveforms at that angle |
| long press | remove the selected track(s) |

**Labels** (prompts 41–45, 63, 69): text only, no box. Priority clock slots in
order 12:00, 1:30, 10:30, 3:00, 9:00, 4:30, 7:30, 6:00 (6:00 last). A label floats
upward from the moment it appears and dissolves the instant its gesture ends; its
slot frees only when the dissolve completes; if the same gesture recurs mid-fade
it reclaims its slot. Driven by the frame clock, not a `while(true)` loop.

## Analysis pipeline

Decode once, measure everything, store it:

1. `AudioDecoder` → mono float PCM + sample rate, silence trimmed at both ends.
2. `OnsetDetector` → spectral-flux onset envelope.
3. `TempoDetector` → BPM + beat phase; `BeatGrid` → beat/bar times.
4. `KeyDetector` → chromagram → key → Camelot via `HarmonicEngine`.
5. `EnergyCurve` → energy over time.
6. `PeakEnvelope` → multi-resolution min/max peaks for drawing.
7. `LoopFinder` → bar-aligned loop candidates; points of interest tagged.

Persisted to Room next to the track. Runs in `ImportService`, a foreground
service with a progress notification and working pause/resume (prompt 85),
bounded-parallel and resumable so a 500-track playlist survives app death.

Playlists expand to *all* their tracks. Local files and any service whose link
resolves to audio the platform can decode; a link that cannot be resolved to
audio is reported as such rather than silently becoming a fake track.

## Delivery phases

- **1 — DSP foundation.** `dsp/` in full, with unit tests: FFT against a known
  DFT, WSOLA length/pitch invariants, tempo detection on synthetic click tracks
  at known BPMs, key detection on synthesised chord progressions, biquad
  magnitude response. Plus `HarmonicEngine` tests.
- **2 — Audio engine.** `PcmBuffer`, `AudioDecoder`, `Clip`, `Deck`, `Mixer`,
  `AudioEngine`, `ScratchModel`, metering. Tests drive the graph offline through
  a fake output and assert on rendered samples: reverse continuity, crossfade
  gain law, EQ effect, loop wrap.
- **3 — Data + analysis.** Room with migrations, `TrackAnalyzer`,
  `AnalysisQueue`, `ImportService`, playlist expansion, store client at
  `azphalt.org`.
- **4 — Platter + gestures.** Angle/time mapping, waveform and playhead
  rendering, energy ring, `GestureEngine` with concurrent axes, labels.
- **5 — Musical features.** Sync/auto-pitch/auto-stretch on measured data,
  Camelot filter, Shuffle Crate, Automatchic Mix, cues, loop maker, sampler with
  recording and auto-fill.
- **6 — Reach.** Multi-device (device-hosted server + discovery, so one-click
  actually connects), session share links, the public API, background visualizer.
- **7 — Docs.** Regenerate README and `web/` from the resolved requirements, so
  the documentation describes the app that exists.

Each phase lands green: it compiles, its tests pass, and no phase depends on a
later one to be honest about what it does.
