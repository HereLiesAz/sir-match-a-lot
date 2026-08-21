# Sir Match-a-Lot 🎶

A DJ app built around a single circular platter and global multi-touch
gestures, instead of two skeuomorphic decks and a wall of tiny controls. The
platter, gestures, and Camelot-wheel automation described below are the
Android app; a touch-laptop counterpart shares the same mixing engine and
sync protocol from a common Kotlin module — see [Desktop](#-desktop) further
down.

## ✨ The platter

One circle. It is the feature of the app, not a widget inside it.

- **Deck A** waveforms protrude **outward** from the ring; **Deck B** waveforms
  protrude **inward**. Two rings, never one per track.
- **Angle is time.** One full revolution is one deck cycle, derived from the
  material loaded on it. The playhead's position on the circle *is* the playback
  position — a single pair of functions converts between them everywhere.
- **No circle is drawn.** Not at the centre, not as a base ring, not as a
  bounding outline. The ring exists only where the waveform implies it.
- **The playhead** is a glowing red slash centred on the ring, its length twice
  the combined waveform height at its own angle — so it bounces over the hills
  and valleys as it sweeps, and collapses to a glowing dot when nothing is loaded.
- Waveforms are dense radial rays whose length and glow follow the **live metered
  output**, so the ring careens with the music. Colour identifies the song;
  measured energy modulates its brightness.

## 🎛️ Global gestures

Gestures apply **anywhere on the screen** — not only inside the circle — and two
can run at once. A gesture ends the moment its own defining conditions stop being
met, rather than when a finger lifts, so you can run one straight into another.

| Gesture | Action |
| :--- | :--- |
| 👆 **1 finger** | Manipulate the audio clips themselves |
| ✌️ **2-finger horizontal** | Crossfade Deck A ↔ Deck B |
| ✌️ **2-finger vertical** | Smart scratch — slows through zero into reverse, pitch following like a real turntable |
| 🎛️ **2-finger rotate** | Master volume, with the feel of a knob |
| 🤏 **2-finger pinch/spread** | Bass boost |
| 🖐️ **3 fingers** | Move, zoom and rotate the whole platter, to work precisely on part of a track |
| **Tap** | Select the waveform under your finger on that deck |
| **Double tap** | Select both decks' waveforms at that spot |
| **Long press** | Remove the selected track(s) |

While a gesture runs, its name appears at a clock position around the platter and
floats upward, dissolving the instant the gesture ends.

## 🎵 Measured, not guessed

Every musical value the app shows or acts on is measured from the audio signal:

- **Tempo and beat phase** from a spectral-flux onset envelope, with harmonic
  reinforcement and a log-normal octave prior.
- **Key** from a chromagram correlated against the Krumhansl-Kessler profiles,
  mapped onto the Camelot wheel.
- **Energy** from loudness and spectral centroid over time.
- **Waveform peaks** as a min/max envelope, with silence trimmed from both ends.

A track whose tempo or key could not be determined shows a dash and is skipped by
beat-sync, rather than being given a plausible-looking substitute. Confidence
figures are shown alongside, because they are relative measures rather than
calibrated probabilities.

## 🚀 Getting started

1. Import audio from the Library tab; it is decoded and analysed on import.
2. Tap a track in the strip along the bottom to load it onto a deck. Deck A fills
   first, then Deck B.
3. Mix with the gestures above — anywhere on the screen.

## 🛠️ Architecture

Kotlin throughout — Jetpack Compose on Android, Compose Multiplatform on
desktop — split across three Gradle modules:

| Module | What lives there |
| :--- | :--- |
| `:shared` | The portable brain, built once and used by both apps: `dsp/` (FFT, STFT, signed-rate resampling, WSOLA time-stretch, biquad filters, tempo/key/energy/peak detection), `audio/` (the real-time mixing graph — decks, mixer, sampler — everything except the final platform sink), `analysis/` (runs the DSP pipeline over a decoded track), `domain/` (the Camelot wheel and mix compatibility scoring), `gesture/` (concurrent multi-axis gesture recognition and label placement), and `sync/`/`session/` (LAN rooms and shareable session links — see Multi-device below). No Android dependency anywhere in it, so it is covered by plain JVM unit tests. |
| `:app` | The Android app: Compose UI, the Room database, and the two files that actually touch platform APIs — `AudioTrackOutput` and `AudioDecoder` (`MediaCodec`). |
| `:desktopApp` | The touch-laptop app: Compose Multiplatform UI, `DesktopAudioOutput` (`javax.sound.sampled`) and `DesktopAudioDecoder` in place of their Android equivalents, a native file picker, and a JSON-backed local library. |

Audio is mixed by the app itself rather than by a platform player, which is what
makes reverse playback, sample-accurate scratching, crossfading and EQ possible at
all. The output stage sits behind an interface (`AudioOutput`, in `:shared`) so
each platform supplies only its own sink and decoder — the mixing graph itself
is identical code on both.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full design,
[`docs/AUDIO_QUALITY.md`](docs/AUDIO_QUALITY.md) for the signal path, its known
limitations, and where the two platforms' output stages currently differ,
[`docs/PLATTER_VISUAL.md`](docs/PLATTER_VISUAL.md) for the rendering
specification, and [`docs/API.md`](docs/API.md) for the protocols the app
speaks.

## 🖥️ Desktop

A touch laptop runs the same mixing engine as the phone, from the identical
`:shared` code — not a scaled-down controller. `./gradlew :desktopApp:run`
opens a window with:

- **Two decks and a crossfader.** Load a local WAV/AIFF/AU file onto Deck A or
  B (via a native file-choose dialog, not a typed path), play/stop each
  independently, and crossfade between them with the same equal-power law the
  Android app uses.
- **An eight-pad sampler**, loaded the same way as a deck.
- **A local library** that remembers files you've pointed it at across
  launches, and measures each one's BPM, Camelot key, and energy with the same
  `TrackAnalyzer` the Android library uses — real analysis, not a placeholder.
- **Room pairing**, described below — a laptop can host or join alongside, or
  instead of, any Android device.

Native installers (Msi/Dmg/Deb) are versioned from the same
`version.properties` as the Android app and carry a proper icon; see
"Building" below. What it doesn't have yet: a real database backing the
library (it's a JSON file, not Room — see
[`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md) §I) or a platter — the desktop
UI is decks-and-pads, not the circular gesture surface above.

## 📡 Multi-device

One device hosts a room; the others find it on the same Wi-Fi and join —
**a phone or a laptop can do either**, since both speak the identical sync
protocol from `:shared`. There is no server to run and no account — a room is
a handful of devices and nothing else.

Each device picks what it is for — the library, the decks, or the pads — and
shows only that screen, so several people play one instrument rather than three
copies of the same app. A loaded session can also be copied out as an ordinary
link with readable query parameters and opened anywhere.

Both are open formats rather than an SDK, specified in
[`docs/API.md`](docs/API.md), so anything can speak them.

## 🔨 Building

```bash
# Android
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
./gradlew bundleRelease          # release bundle (unsigned without a keystore)

# Desktop
./gradlew :desktopApp:run                       # run it directly
./gradlew :desktopApp:hotRun                     # run with Compose Hot Reload — edit
                                                  # a @Composable, save, see it update
                                                  # in the already-running window,
                                                  # no restart. Downloads a JetBrains
                                                  # Runtime (JBR) the first time.
./gradlew :desktopApp:test                       # unit tests
./gradlew :desktopApp:packageDeb                 # or packageMsi / packageDmg
                                                  # (each only builds on its native OS)

# Shared module (both apps depend on it)
./gradlew :shared:desktopTest                    # portable dsp/audio/domain/sync tests, run on the JVM
```

Release signing is supplied by CI. Without a keystore the Android release variant
builds unsigned rather than failing.

### Compose Hot Reload's MCP server

Hot Reload also ships a Model Context Protocol server, exposed as the Gradle
task `:desktopApp:hotMcpServer`. `.mcp.json` at the repo root registers it as
`compose-hot-reload`, so an MCP-aware coding agent (this one included) can
`reload`, `take_screenshot`, `get_semantic_tree`, `get_logs`, and simulate
`click`/`type_text`/`scroll` against the actual running desktop window —
reasoning about the real, live layout instead of only the Compose source. The
task manages the running application itself; there is no separate app process
to start first. This surface is marked experimental upstream (introduced in
Compose Hot Reload 1.2.0), so treat its exact behavior as still settling.

## 📋 Status

This is a rebuild in progress. [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md)
tracks every requirement individually with its current state. In summary:

**Working.** Measured tempo, key, energy and peak analysis. The mixing engine,
with reverse playback, scratching, crossfade, EQ and metering. The platter
renderer and the gesture engine. Multi-device rooms — hosting, LAN discovery,
per-device roles — the shareable session link, and the documented API.

**Also working, and listed here as outstanding until recently.** The sampler
records: `Sampler.beginRecording` captures the app's own mixer output to a pad.
The automatic loop maker (`LoopHarvest`) runs across a whole playlist, and a pad
bank can be placed onto a deck slot. `docs/REQUIREMENTS.md` has marked G2–G5
done for some time; this section had not caught up, and a status section is
exactly where a reader trusts the answer.

**Desktop.** A touch laptop is a full device in the room, not a stub: the
shared engine, room pairing, two-deck-plus-crossfader-plus-sampler playback,
a native file picker, and a locally analysed library are all working — see
[Desktop](#-desktop) above and `docs/REQUIREMENTS.md` §I. Outstanding: the
desktop library is a JSON file rather than a real database (§I8), and there
is no platter UI on desktop by design.

## 🔒 Privacy, permissions, terms

No accounts, no analytics, no advertising, and no server of its own. Your music
and everything measured from it stay on your device; the app makes a network
request only when you ask it to import a link or fetch a store pack. A crash is
logged to private storage and offered to you as a prefilled GitHub issue on the
next launch — it goes nowhere unless you send it.

- [`docs/PRIVACY.md`](docs/PRIVACY.md) — what is stored, what is sent, and when
- [`docs/PERMISSIONS.md`](docs/PERMISSIONS.md) — every permission, why it exists,
  what breaks without it. **The app does not use your microphone**
- [`docs/TERMS.md`](docs/TERMS.md) — terms of use, and why no audio is taken from
  YouTube or Spotify
- [`docs/SECURITY.md`](docs/SECURITY.md) — reporting a vulnerability, and the
  known limits of the local-network sync
- [`docs/DATA_SAFETY.md`](docs/DATA_SAFETY.md) — the Play data safety answers,
  traceable to code

## 📄 License

Sir Match-a-Lot is licensed under the **GNU General Public License v3.0** — see
[`LICENSE`](LICENSE).

This is a deliberate choice, made to allow linking the
[Rubber Band Library](https://breakfastquay.com/rubberband/) for high-quality
time-stretching under its GPL terms. The consequence is that anyone who receives
a build of this app may study, modify, and redistribute it, and any derivative
work must also be GPL.

Note that this covers the *application*. Sample packs, sound effects, and other
content distributed through the [Azphalt Store](https://azphalt.org) are separate
works, not derivatives of this code, and are not affected by this license.
