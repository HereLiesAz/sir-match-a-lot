# Sir Match-a-Lot 🎶

An Android DJ app built around a single circular platter and global multi-touch
gestures, instead of two skeuomorphic decks and a wall of tiny controls.

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

Kotlin and Jetpack Compose throughout.

| Package | Responsibility |
| :--- | :--- |
| `dsp/` | FFT, STFT, signed-rate resampling, WSOLA time-stretch, biquad filters, tempo/key/energy/peak detection. No Android dependencies, so it is covered by JVM unit tests. |
| `audio/` | The real-time graph: decoded PCM, clips on a circular timeline, one signed-rate playhead per deck, EQ, equal-power crossfade, safety limiter, metering, and the `AudioTrack` output stage. |
| `analysis/` | Runs the DSP pipeline over a decoded track. One decode feeds playback, analysis, and drawing. |
| `domain/` | The Camelot wheel and mix compatibility scoring. |
| `gesture/` | Concurrent multi-axis gesture recognition and label placement. |
| `ui/platter/` | Platter geometry, palette, and rendering. |
| `sync/` | Multi-device rooms: LAN discovery, an RFC 6455 WebSocket server and client, per-device roles, and the shareable session link. |

Audio is mixed by the app itself rather than by a platform player, which is what
makes reverse playback, sample-accurate scratching, crossfading and EQ possible at
all. The output stage sits behind an interface so it can be replaced without
touching the DSP or the UI.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full design,
[`docs/AUDIO_QUALITY.md`](docs/AUDIO_QUALITY.md) for the signal path and its known
limitations, [`docs/PLATTER_VISUAL.md`](docs/PLATTER_VISUAL.md) for the
rendering specification, and [`docs/API.md`](docs/API.md) for the protocols the
app speaks.

## 📡 Multi-device

One phone hosts a room; the others find it on the same Wi-Fi and join. There is
no server to run and no account — a room is a handful of devices and nothing
else.

Each device picks what it is for — the library, the decks, or the pads — and
shows only that screen, so several people play one instrument rather than three
copies of the same app. A loaded session can also be copied out as an ordinary
link with readable query parameters and opened anywhere.

Both are open formats rather than an SDK, specified in
[`docs/API.md`](docs/API.md), so anything can speak them.

## 🔨 Building

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
./gradlew bundleRelease          # release bundle (unsigned without a keystore)
```

Release signing is supplied by CI. Without a keystore the release variant builds
unsigned rather than failing.

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
