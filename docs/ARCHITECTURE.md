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

Three Gradle modules as of the desktop-linking work: **`:shared`**, a Kotlin
Multiplatform module targeting `androidTarget()` and `jvm("desktop")`, holds
everything with no real dependency on Android or a particular playback
backend — the mixing brain, the DSP, and the LAN pairing protocol. That is
what lets an Android phone and a desktop build join the same room and share
the same domain logic without duplicating it. **`:app`** is the Android
application: the Compose UI, the Room database, and the two files that
actually touch `AudioTrack`/`MediaCodec`. **`:desktopApp`** is the
touch-laptop side: a plain Compose Multiplatform desktop app (`kotlin("jvm")`
+ `org.jetbrains.compose`) that depends on `:shared` the same way `:app`
does.

`:desktopApp` is two independent halves. `RoomSession`
(`desktopApp/src/main/kotlin/.../desktop/RoomSession.kt`) wraps
`SyncServer`/`SyncClient` the way `SirMatchALotViewModel` does on Android —
host or join a room, approve pairings, see the roster and live status.
`PlaybackSession` plays two decks through a crossfader with an eight-pad
sampler — `DeckControl` and `SamplerPadControl` each wrap a live `Deck`/
`SamplerPad` from `AudioEngine` in `StateFlow`s a screen or a test can drive
directly. `AudioOutput` itself (the interface, `OfflineAudioOutput`, and
`AudioEngine`, with its `deckA`/`deckB`/`mixer`/`sampler`) turned out to have
no Android dependency at all and moved to `:shared`, so only the two
concrete outputs differ — `AudioTrackOutput` (`AudioTrack`, in `:app`) and
`DesktopAudioOutput` (`javax.sound.sampled.SourceDataLine`, here) — and only
`AudioDecoder` (`MediaCodec`, in `:app`) needed a desktop counterpart,
`DesktopAudioDecoder`, scoped for now to what `javax.sound.sampled` reads
natively (WAV/AIFF/AU — broader format support via an SPI provider is a
follow-up, not a blocker). The two halves aren't wired together yet — a
loaded deck reacting to room state is UI work on two already-working
pieces, not new plumbing.

This is the real tree, not an aspirational one — regenerated from
`find shared/src app/src/main/java -name '*.kt'` rather than hand-maintained,
since a previous version of this section had drifted from the code on nearly
every line (files renamed, moved between packages, or never built at all)
and nobody reading it could tell which.

```
shared/src/jvmCommonMain/kotlin/com/hereliesaz/sirmatchalot/
  # "jvmCommonMain" rather than "commonMain": both real targets (Android,
  # desktop) compile to the JVM, so this is simply "everything shared", not
  # a strict common/JVM split — see shared/build.gradle.kts.

  dsp/          pure math, zero platform deps, JVM-unit-tested
    Fft.kt                radix-2 Cooley-Tukey, in-place, preallocated
    Window.kt             Hann/Hamming
    Biquad.kt             RBJ cookbook: low/high shelf, peaking, lowpass
    TimeStretch.kt        WSOLA — tempo independent of pitch, and the pitch
                          shifter built from it
    Resampler.kt          Catmull-Rom fractional read, signed rate (reverse)
    SincResampler.kt      windowed-sinc sample-rate conversion
    OnsetDetector.kt      spectral flux envelope
    TempoDetector.kt      comb-filter/autocorr over the envelope -> BPM
    BeatGrid.kt           downbeat inference, beat/bar times
    KeyDetector.kt        chromagram + Krumhansl-Schmuckler -> key
    EnergyCurve.kt        per-window loudness+spectral centroid -> energy 0..1
    BandEnergy.kt         per-band energy for the light show
    StructureFinder.kt    bar-aligned self-similarity -> loop candidates
    StructureSegmenter.kt track structure (intro/build/drop/breakdown/outro)
    VocalDetector.kt      vocal-presence estimate
    GrowlVoice.kt         synthesised voice for the reverse-scratch easter egg
    Stft.kt               short-time Fourier transform, shared by the above
    PeakEnvelope.kt       min/max peak reduction for drawing

  audio/        the real-time graph, minus decode and the platform sink
    PcmBuffer.kt          immutable decoded mono/stereo PCM, 16-bit or float
    Deck.kt               a circular timeline of clips, signed-rate playhead
    Mixer.kt              crossfade law, master gain, limiter, level meter
    MasterFilter.kt       the XY performance filter on the master bus
    ScratchModel.kt       gesture delta -> non-linear rate curve through zero
    Sampler.kt            pads: record from master bus, replay
    OneShotVoice.kt       plays a short buffer once, for the growl voice
    SpectrumMeter.kt      lock-free band levels for the light show
    DecodedCache.kt       heap-budgeted cache of decoded tracks
    AudioOutput.kt        the AudioOutput interface, OfflineAudioOutput (used
                          by tests), and AudioEngine — none of it touches a
                          platform sink; only the concrete outputs
                          (AudioTrackOutput in :app, DesktopAudioOutput in
                          :desktopApp) and the decoders (AudioDecoder in
                          :app, DesktopAudioDecoder in :desktopApp) differ

  analysis/     the portable half of orchestrating dsp over a library
    TrackAnalyzer.kt      decode once -> BPM, key, beatgrid, energy, peaks
    AnalysisProgressBus.kt shared progress state between the service and UI
                          (AnalysisService.kt, the foreground-service shim
                          around TrackAnalyzer, stays in :app)

  data/
    Track.kt              Room @Entity — only the annotations need Room's
                          artifacts, which are multiplatform; @Dao/@Database
                          need Android's SQLite driver and stay in :app
    KeyValueStore.kt      the settings-store interface DeviceIdentity/
                          KnownDevices persist through

  domain/
    HarmonicEngine.kt     Camelot wheel compatibility scoring
    BeatSync.kt           tempo/phase/pitch alignment between two tracks
    MixPlanner.kt         Shuffle Crate + Automatchic Mix ordering
    MixDirector.kt        runs a planned mix: transitions, flourishes, timing
    TransitionChoreographer.kt / TransitionScript.kt / TransitionTaste.kt
                          chooses and scripts a transition, learns from taste
    TrackStructure.kt / TrackGrid.kt   structure and beat-grid lookups
    SetArc.kt             energy arc across a planned set
    LoopHarvest.kt        loop candidates for a track
    BeatSnap.kt           snapping a time to the beat grid
    DeckCapacity.kt       how many beats of audio a deck timeline can hold

  session/
    SessionDocument.kt    the `.sir` file format (decks, pads, running order)
    SessionArchive.kt     zip read/write of a session plus its pad takes
    SessionResolver.kt    matches a saved session's tracks against the library
    WavCodec.kt           WAV encode/decode for pad takes

  sync/
    SyncServer.kt / SyncClient.kt   the room: hosting and joining — plain
                          java.net sockets and OkHttp, no Android dependency
    RoomCrypto.kt         ECDH key agreement, SAS, AES-256-GCM framing
    DeviceIdentity.kt     the long-term identity keypair and its fingerprint,
                          and KnownDevices, the paired-device store
    WebSocketProtocol.kt  the WebSocket framing used over the room socket
    SyncRole.kt           which screen a device shows once linked
    SessionLink.kt        share/restore a session as a URL's query params
    DebugLog.kt           expect/actual: android.util.Log vs println

  gesture/
    GestureEngine.kt      concurrent multi-axis gesture recognition
    GestureLabels.kt      clock-position label placement, float-up + dissolve

shared/src/androidMain/kotlin/.../sync/DebugLog.android.kt   Log actual
shared/src/desktopMain/kotlin/.../sync/DebugLog.desktop.kt   println actual

app/src/main/java/com/hereliesaz/sirmatchalot/
  audio/
    AudioDecoder.kt       MediaCodec -> PcmBuffer
    AudioOutput.kt        AudioTrackOutput — the AudioTrack render thread,
                          implementing :shared's AudioOutput interface

  analysis/
    AnalysisQueue.kt      bounded parallel, cancellable, resumable
    AnalysisService.kt    foreground service: notification, pause/resume

  data/
    TrackDao.kt, AppDatabase.kt   Room DAO, migrations, over :shared's Track
    AnalysisQueue.kt      what needs (re-)analysing and why
    AudioFileCache.kt     local copies of imported audio
    AudioFileFilter.kt    which files in a folder import counts as audio
    AzphaltStoreRepository.kt  the sample-pack store
    EngineSettings.kt     sample rate, memory budget, visual refresh settings
                          (SettingsStore reads/writes through :shared's
                          KeyValueStore)
    LinkParser.kt         playlist-link and pasted-tracklist parsing
    PlaylistParser.kt     M3U/PLS/XSPF parsing

  crash/
    CrashReportingHandler.kt / CrashReportStore.kt / CrashReportPrompt.kt /
    CrashReport.kt / CrashReportIssue.kt
                          uncaught-exception capture and the GitHub-issue draft

  theme/          Compose colour, typography and theme
  ui/
    platter/            the feature: rings, waveforms, playhead, gestures
    main/MainScreen.kt   tabs, the crossfader, sync and settings dialogs
    LibraryScreen.kt, SamplerScreen.kt, SettingsScreen.kt
    SirMatchALotViewModel.kt   the app's one ViewModel
    BackgroundWork.kt    tracked long-running work, for the app-bar indicator

desktopApp/src/main/kotlin/com/hereliesaz/sirmatchalot/desktop/
  Main.kt                 the entry point: the room screen plus a two-deck
                          instrument — DeckPanel x2 (each with a native
                          Browse… picker), a crossfader Slider, an 8-pad
                          sampler grid, and a Library panel
  RoomSession.kt          the desktop analogue of SirMatchALotViewModel's
                          sync half — wraps SyncServer/SyncClient in
                          StateFlows a screen or a test can drive without
                          Compose or a display
  PlaybackSession.kt      the desktop analogue of its playback half — deckA
                          and deckB (each a DeckControl), a crossfader onto
                          AudioEngine.mixer, and an eight-pad sampler (each a
                          SamplerPadControl), all played through
                          DesktopAudioOutput
  DesktopAudioOutput.kt   AudioOutput via javax.sound.sampled.SourceDataLine,
                          same blocking-write/idle-standdown shape as
                          AudioTrackOutput
  DesktopAudioDecoder.kt  a local file -> PcmBuffer, via javax.sound.sampled
                          (WAV/AIFF/AU; broader formats are a follow-up)
  DesktopFilePicker.kt    java.awt.FileDialog wrapper — the native OS file
                          chooser, not a Compose-drawn one, so it behaves the
                          way Finder/Explorer already do
  DesktopLibrary.kt       a remembered list of local audio files, persisted
                          as JSON, each measured by the same TrackAnalyzer
                          (BPM, Camelot key, energy) the Android library
                          uses — analysis runs on a background thread per
                          file so a click handler never blocks on FFTs; not
                          the Android app's Room-backed `Track` entity — see
                          "What desktop Phase 5 didn't do" below
  DesktopKeyValueStore.kt KeyValueStore backed by a properties file, since
                          there is no SharedPreferences on a desktop JVM
```

### What desktop Phase 5 didn't do

`DesktopLibrary` now measures BPM, Camelot key, and energy the same way the
Android library does — `TrackAnalyzer` and the whole `dsp` pipeline behind
it were already fully portable, so wiring them in needed no new audio code,
only a background thread so analysis (real FFT work over the whole track)
never blocks the UI thread's click handler. What it still isn't is the
Android app's `Track` entity: no cached-copy bookkeeping, no cue points, and
nothing backed by Room. Room's multiplatform story needs a SQLite driver
plus KSP codegen wired into `:shared`, which stays real, separate,
unstarted work — a JSON file list has no query planner or indices, so it
will not scale to a library the way a real database would, but that is a
cost this size of library can absorb for now, not something blocking a
laptop from measuring and remembering the tracks a working DJ has loaded.

### Packaging

`desktopApp/build.gradle.kts`'s `nativeDistributions` block produces a Msi
(Windows), a Dmg (macOS), and a Deb (Linux) — `jpackage` only builds the
installer for the OS it runs on, so CI would need a matrix of runners to
produce all three; this repo doesn't have that wired up yet, and a Msi/Dmg
built this way from Linux is not itself possible (`jpackage` doesn't cross-
package). The three icon files under `desktopApp/src/main/resources/`
(`icon.ico`, `icon.icns`, `icon.png`) were generated once from the Android
app's `xxxhdpi` launcher icon and are checked in rather than regenerated on
every build; `packageVersion` is read from the same `version.properties`
`:app` uses (major.minor.patch only — installer formats reject a fourth
build-number component), so the desktop package version tracks the app's
real version instead of a hand-maintained `"1.0.0"`.

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
7. `StructureFinder`/`StructureSegmenter` → bar-aligned loop candidates and
   track structure (intro/build/drop/breakdown/outro).

Persisted to Room next to the track. Runs in `AnalysisService`, a foreground
service with a progress notification and working pause/resume, bounded-parallel
and resumable so a 500-track playlist survives app death.

Playlists expand to *all* their tracks. Local files and any service whose link
resolves to audio the platform can decode; a link that cannot be resolved to
audio is reported as such rather than silently becoming a fake track.

## Spending memory and power

Two costs are proportional to the *whole* of a track rather than to anything the
user did, and both of them showed up in the field: an `OutOfMemoryError` on a
256 MB heap, and a battery draw larger than everything else on the device
combined. Neither has a fix that is right for every phone, so the levers are in
`data/EngineSettings` and the defaults are the safe end of each.

**Sample rate is the only lever that changes the arithmetic.** A decoded track
costs `rate × channels × depth` bytes a second and nothing else, and every stage
of the render graph is per sample. Halving the rate halves both, exactly.
`SampleRateOption` therefore offers the device's native rate — the default, and
the only one where AudioFlinger does no resampling of its own — plus four lower
ones, labelled with what a minute of stereo costs at each. Changing it rebuilds
`AudioEngine` and clears the decks, because every decoded buffer, every clip
start frame and the whole beat grid are counted in frames of the old rate.

**Nothing decodes on hope.** `AudioDecoder.probe` reads a container's duration,
rate and channel count without decoding, so `DecodedCache.canAdmit` can refuse a
track that will not fit *before* the decoder is holding a whole-track
accumulation buffer. The answer is a sentence naming the setting that would let
it fit, rather than a stack trace. `OutOfMemoryError` is caught at the load and
analysis boundaries — the only two places where it is both expected and
recoverable — and drops the caches rather than the process.

**The audio thread stands down.** The render graph used to mix, filter, limit
and meter silence continuously from the moment the app opened, with `AudioTrack`
holding the output path open the whole time. `AudioEngine.isIdle` is structural
— are any decks sounding, any pads playing, any take recording — never a level
measurement, because a track playing a quiet passage must keep the output open
or the next beat arrives a stand-down late. After about a second of idle blocks
the track pauses and the thread parks; it wakes on a 20 ms poll, or immediately
when something that is about to make a sound says so.

**Nothing redraws for nobody.** The platter state publishes only while a screen
is in front of someone, at the chosen refresh rate rather than at 60 Hz; the
platter's frame clock parks entirely when nothing is playing, no finger is down
and no gesture label is still fading; and the light show — six full-screen
additive blooms, the most expensive thing drawn — is not composed at all when it
is off or when the room is dark.

## Saying what it is doing

Nearly everything expensive in this app is asynchronous, and each piece of it
used to report itself to exactly one screen — analysis to the Library tab, loop
harvesting to a button on the Sampler tab, and loading a track, which decodes a
whole file, converts its rate and then stretches and shifts it to match the
session, to nowhere at all. Whichever screen you were on, the work you had just
started from it was invisible.

`ui/BackgroundWork` is a registry of what is in flight, rendered in the app bar
every tab shares. Two decisions carry it:

- **Work is wrapped, not bracketed.** `BackgroundWork.track` puts the clear-down
  in a `finally`, because the failure mode of a progress indicator is an orphan:
  a spinner left running by an early return, a decode that gave up, a caught
  `OutOfMemoryError` or a cancelled harvest teaches you to ignore the one place
  the app tells you anything. It is `inline` so the long functions it wraps keep
  their early returns instead of being reshaped around the wrapper.
- **Unknown progress stays unknown.** A fraction is shown where one is genuinely
  known — analysis is track *n* of *m* — and an indeterminate spinner where it is
  not. A bar that creeps to 90% and waits is a lie about a measurement.

The background analysis service is folded in from `AnalysisProgressBus` rather
than registered, since it outlives the ViewModel and a run started before the app
was reopened still has to appear. Outcomes — what a track conformed to, that a
clip was dropped to make room, that a file would not decode — come from
`feedbackMsg`, which is now drawn over every screen instead of only the Library,
and dismisses itself. It is an overlay rather than a row in the layout: taking
space would resize the platter each time a message appeared or expired, including
under a finger mid-gesture.

## Delivery phases

- **1 — DSP foundation.** `dsp/` in full, with unit tests: FFT against a known
  DFT, WSOLA length/pitch invariants, tempo detection on synthetic click tracks
  at known BPMs, key detection on synthesised chord progressions, biquad
  magnitude response. Plus `HarmonicEngine` tests.
- **2 — Audio engine.** `PcmBuffer`, `AudioDecoder`, `Deck`, `Mixer`,
  `AudioOutput`, `ScratchModel`, `SpectrumMeter`. Tests drive the graph offline
  through `OfflineAudioOutput` and assert on rendered samples: reverse
  continuity, crossfade gain law, EQ effect, loop wrap.
- **3 — Data + analysis.** Room with migrations, `TrackAnalyzer`,
  `AnalysisQueue`, `AnalysisService`, playlist expansion, store client at
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
