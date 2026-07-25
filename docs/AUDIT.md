# Audit of the pre-existing codebase

Audited at commit `a5508e5`. 24 Kotlin files, 4,654 LOC.

Method: read every source file; installed the Android SDK (platform 37) and built.

## Build status

| Check | Result |
| :--- | :--- |
| `:app:compileDebugKotlin` | **passes** (10 deprecation/redundancy warnings) |
| `:app:compileDebugAndroidTestKotlin` | **fails** — `MainScreenTest.kt:17` unresolved reference `MainScreen` |
| `:app:testDebugUnitTest` | passes, but the only test is `assertEquals(4, 2 + 2)` |

So the code compiles. The problem is not syntax — it is that most of the
advertised behaviour does not exist, and the parts that do exist are built on
foundations that cannot support the feature list.

The in-file comment at `app/build.gradle.kts:59` asserting that "compileSdk and
targetSdk 37 do not exist as stable releases. 34 is the latest stable" is
false — `platforms;android-37.0` and `37.1` are both published. The comment was
wrong, not the version.

## Blocking architectural defects

### 1. Track analysis is fabricated, not measured

This is the root failure. Every harmonic and tempo feature in the app depends on
BPM and musical key, and nothing ever measures either one.

- `SongAnalyzer.kt` `HeuristicAnalyzer` derives BPM from a **character-code sum
  of the filename**: `estimatedBpm = 110 + (charSum % 40)`, and picks a key by
  `keys[charSum % keys.size]`. Renaming a file changes its "detected" key.
- `AzphaltStoreRepository.kt:104-107` is blunter still: `bpm = (90..150).random()`
  and `camelotKey = "8A"` hardcoded. BPM is re-rolled per import.
- `GeminiAnalyzer` asks a language model to *recall* the BPM of a track from its
  title, then falls back to the hash heuristic. It is also permanently disabled:
  `SirMatchALotViewModel.kt:30` constructs it with the literal
  `apiKey = "MY_GEMINI_API_KEY"`, and line 151 short-circuits to the fallback on
  exactly that string.

Consequence: "Auto Beat Sync", "Harmonize", Camelot sorting, and compatibility
scores are all arithmetic over invented numbers. They will never align a beat.
Fixing this requires real DSP on decoded audio — not a different prompt.

### 2. The audio path cannot express the features that were promised

`DeckController.kt` is a thin `ExoPlayer` wrapper, and the ViewModel creates
**one ExoPlayer per clip** (`_controllersA` / `_controllersB` are lists). That
means there is no mixer — just N independent players racing each other.

Specific requirements this architecture cannot satisfy:

- **Reverse playback.** Prompt: "the speed decreases until it gets to zero, and
  starts to play backwards." `ExoPlayer` cannot play at a negative rate. There
  is no code that attempts it.
- **Scratching.** Achieved via `seekTo()` at 200 ms polling
  (`startProgressTracking`, line 150). Seeks are keyframe-quantised and
  allocate; this yields stuttering jumps, not a scratch.
- **Independent pitch / tempo.** `setPitchOnly` uses
  `PlaybackParameters(1.0f, pitchRatio)`, which is Sonic-based and coarse; there
  is no time-stretcher, so "auto stretch" does not exist.
- **Beat-phase sync.** Attempted in the *UI layer*
  (`ControlsScreen.kt:663-689`) by polling every 250 ms and calling `seekTo`
  when drift exceeds 40 ms. Correcting sub-40 ms phase with 250 ms seeks is not
  convergent; it will audibly hiccup forever.
- **EQ / bass boost.** `adjustEqBassTreble` (ViewModel:428) sets
  `synthEngine.filterCutoff` — a field on the standalone sine-wave synth, which
  is not in the music's signal path at all. The gesture is wired to nothing.
  It also writes values in the 100–12000 range into a field the synth
  multiplies by directly as a 0–1 gain (`SynthEngine.kt:151`).
- **Silence trimming** is computed but the clip bounds are applied via a
  deprecated `ClippingMediaSource` constructor and `trimEndMs` is set from the
  last non-silent peak, so a track whose analysis failed gets `trimEndMs = 0`
  and is treated as untrimmed — inconsistently with `durationMs`.

### 3. The waveform is not the waveform

The user's complaint — "The 'waveforms' are just zigzags that have nothing to do
with the audio's actual waveform" — is accurate, but with a specific cause.

`AudioWaveformExtractor` genuinely decodes PCM and produces real peaks (this
part is sound). But the renderer never uses them for most tracks, because
`peaksPath` is only ever populated by `AzphaltStoreRepository`. Tracks added
through the file picker or `analyzeTrack` get `peaksPath = null`, so
`loadPeaksForTrack` returns immediately and the draw loop falls through to its
literal placeholder:

```kotlin
val rawPeak = if (peaks != null && pSize > 0) { peaks[idx] } else { 0.05f }
```

Every locally imported track therefore renders as a constant 0.05 — a flat ring
of identical spikes. That is the zigzag.

Two further problems in the same draw code:
- The peaks are a **static** amplitude envelope. Nothing samples the live output,
  so "brightness and size intensifying with the current audio output" is faked
  with `sin(visualizerPhase * 50f)` — a free-running oscillator unrelated to the
  music. This is also why the background "lightshow" does not follow the beat:
  `ControlsScreen.kt:183` derives it from `sin(phase * 12f)` on a 10-second
  linear tween, not from audio.
- The platter rotation period is hardcoded to 10 seconds
  (`playheadAngle += (2 * Math.PI / 10f)`, line 171) while `platterDurationSeconds`
  is computed from track duration and then never used (line 641). `scrubPlayhead`
  likewise converts angle to time using a hardcoded `* 10f` (ViewModel:503). So
  the playhead's position does not correspond to playback position in any track.

### 4. Multi-device linking has no server

`SyncClient.kt` is a complete OkHttp WebSocket client plus UDP discovery
broadcast to port 8888. Nothing in the repository listens on port 8888 or serves
a WebSocket. There is no server, and no device can *become* one, so "one-click
auto connect while the devices are on the same wifi" cannot succeed. The
`MainScreen` dialog defaults to typing `192.168.1.100` by hand.

### 5. Gesture recognition contradicts its own spec

The gesture layer in `ControlsScreen.kt` is dispatched by `pointers.size` in a
`when` block, which means:

- **Simultaneous two-axis gestures are impossible** across finger counts, and
  within the 2-finger branch all four gestures (span, rotate, dx, dy) fire on
  every frame that passes their independent thresholds — so a single sloppy drag
  triggers `BASS BOOST`, `VOLUME KNOB`, `CROSSFADER` and `SMART SCRATCH` at
  once. The request was deliberate two-axis combination, not accidental
  four-way crosstalk.
- **The mapping is the superseded one.** Prompt 75 re-assigned the gestures
  (3-finger = platter transform, 2-finger horizontal = crossfader, 2-finger
  vertical = smart scratch, 2-finger rotate = volume, 2-finger pinch = bass).
  The code follows this, but `README.md` and the entire `web/` page still
  document the *old* map (pinch = BPM, 1-finger vertical = pitch, 2-finger
  rotate = overlap). The shipped documentation describes an app that no longer
  exists.
- **Gestures are not global.** Line 253-254 excludes the bottom `160.dp`, and
  the platter's own `pointerInput` (line 720) sits on top competing for taps.
  The user asked three times for gestures to apply anywhere.
- The Satan easter egg is implemented twice, with two different trigger
  accumulators (`backwardScrubAccumulator` at line 348, `consecutiveBackwardScratch`
  at line 431), and uses `TextToSpeech` at pitch 0.2 rather than the described
  "exceptionally low, growling voice". The speech rate ramp only advances in one
  of the two paths.

### 6. Missing features, tracked against the prompts

| Requested | State |
| :--- | :--- |
| Camelot-proximity dropdown filter in library | `sortOption` exists in the ViewModel; **no UI**, and `LibraryScreen` renders unsorted `tracks`, so it is dead code |
| Auto pitch / auto stretch | absent (no time-stretcher) |
| Sampler pad record + replay | absent — pads play a synthesised sine/noise burst |
| Auto-fill unused pads with samples from tracks | absent |
| Automatic loop maker from playlist songs | absent |
| Quick cue markers, visible | `setCue`/`triggerCue` exist; **not drawn**, not reachable from UI |
| Tag other elements of interest | absent |
| Expose complete app API | absent |
| Shuffle Crate button | absent |
| Automatchic Mix | absent |
| Energy graph around circle, gesture-reshapable | absent |
| Share session as link with query params | absent |
| Playlist import (imports all songs) | absent — a playlist URL is parsed then treated as one track |
| Background import w/ notification, pause/resume | absent — no service, no `FOREGROUND_SERVICE`/`POST_NOTIFICATIONS` in the manifest |
| Store integration at `azphalt.org` | points at `azphalt.store`, and auto-downloads the first package on first launch |
| Double-tap selects both decks' waveforms | double-tap is bound to play/pause instead (line 722) |
| Long-press removes selected tracks | long-press **toggles selection** (line 758-776); `removeTrackFromDecks` is never called from any gesture |
| Loops placeable on a deck like a song | absent |
| Comprehensive documentation | README + web page describe the superseded gesture map |

### 7. Correctness bugs worth naming

- `removeTrackFromDecks` finds the index in `_loadedTracksA`, mutates the list,
  **then** uses that same index against `_controllersA`. Because the two lists
  are maintained separately, any prior partial failure desynchronises them and
  this throws or releases the wrong player.
- `updateAllVolumes` pairs tracks to controllers by positional index across two
  independent `StateFlow`s — the same latent desync.
- Crossfader: `crossA = if (cf < 0) 1f else (100f - cf)/100f` gives full gain to
  *both* decks at the centre, so the mix is +6 dB louder mid-fade.
- `Track.id` in `AzphaltStoreRepository` is `file.absolutePath.hashCode().toString()`
  — a 32-bit hash used as a Room primary key.
- `AppDatabase` uses `fallbackToDestructiveMigration()`, so the user's library is
  wiped on every schema change.
- `DeckController` hardcodes a YouTube fetch to `http://10.0.2.2:8080/...`, the
  emulator loopback address. It cannot work on a device.
- `AudioWaveformExtractor` assumes 16-bit PCM output without checking
  `KEY_PCM_ENCODING`, and treats interleaved stereo samples as consecutive mono
  samples, so peak windows are half the intended duration on stereo input.
- The `LaunchedEffect(Unit)` gesture-fade loop at `ControlsScreen.kt:93` runs a
  16 ms `while(true)` for the lifetime of the screen regardless of whether any
  gesture label is visible.
- `app/build.gradle.kts` carries a large amount of configuration from an
  unrelated project: AdMob unit IDs, a GitHub OAuth client ID, a "build tools"
  repo, `FONTS_API_KEY`, and a `copyInAppDocs` task referencing
  `docs/PRIVACY_POLICY.md`, `docs/PERMISSIONS.md`, `docs/conduct.md` — none of
  which exist. The KSP pin of `2.3.10` against Kotlin `2.4.10` looks like a
  mismatch but is in fact the only option: KSP has published nothing newer than
  2.3.10, and KSP2 accepts it. It belongs in the version catalog rather than
  hardcoded in the module, but the version itself is right.

## What is worth keeping

Three things are good enough that rewriting them would be worse:

1. **`domain/HarmonicEngine.kt`** — the Camelot wheel model is correct.
   Chromatic-to-Camelot tables, transposition, shortest semitone shift, and the
   compatibility scoring (relative major/minor, perfect fifth, diagonal) are all
   right, and half/double-time tempo matching is handled properly. Pure Kotlin,
   no Android dependencies, directly unit-testable. **Keep, add tests.**

2. **`audio/AudioWaveformExtractor.kt`** — the `MediaExtractor` + `MediaCodec`
   decode loop and the silence-trim pass are the right approach and correctly
   structured. It needs real fixes (honour `KEY_PCM_ENCODING` and channel count,
   downmix to mono, support cancellation, and return the PCM itself so analysis
   and playback can share one decode) but the skeleton survives. **Keep and
   extend.**

3. **`data/LinkParser.kt`** — small, correct URL/filename parsing. **Keep**,
   extended for playlist forms.

Partially reusable: `SyncClient`'s message protocol is a reasonable schema to
keep once a server exists to speak it. The Compose theme files and launcher
icons are fine.

## Verdict

The UI shell, the Camelot engine, and the PCM decoder are real work. Everything
between them — analysis, mixing, synchronisation, gesture arbitration,
visualisation — is either fabricated data or a stub wired to nothing, and the
single-`ExoPlayer`-per-clip design forecloses reverse playback, scratching,
time-stretching, and EQ regardless of how much is added on top.

Rebuild, keeping the three components above.
