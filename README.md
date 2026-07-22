# Sir Match-a-Lot 🎶

Welcome to **Sir Match-a-Lot**, a revolutionary Android DJing app that breaks free from traditional skeuomorphic dual-deck layouts. Instead of tiny faders and overwhelming buttons, Sir Match-a-Lot features a stunning, singular **Concentric Platter Interface** and powerful multi-finger **Global Gestures**, giving you a highly tactile and deeply connected mixing experience.

## ✨ The Concentric Platter
The heart of Sir Match-a-Lot is the unified circular platter that puts the music front and center.
- **Deck A (Outer Ring):** Audio waveforms dynamically protrude outward from the base circle.
- **Deck B (Inner Ring):** Audio waveforms dynamically protrude inward from the base circle.
- **Stopwatch Playhead:** A singular red playhead acts like a stopwatch hand, circling the platter and reading both decks simultaneously. 

Because the entire interface is built around this central circle, the duration of one complete rotation is determined dynamically by the longest audio track currently loaded. You no longer mix two separate timelines; you mix one unified groove.

## 🎛️ Global 8-Directional Gestures
With Sir Match-a-Lot, your entire screen is your canvas. You don't need to hunt for tiny knobs. Performing global multi-finger gestures anywhere on the screen controls the music intuitively:

| Gesture | Action | Description |
| :--- | :--- | :--- |
| 🤏 **Pinch** | **BPM / Speed** | Pinch in or out to dynamically slow down or speed up the track's BPM. |
| 👆 **1-Finger Drag (Vertical)** | **Pitch** | Adjust the pitch of the music. |
| 👆 **1-Finger Drag (Horizontal)** | **EQ (Bass/Treble)** | Sweep left and right to adjust the Bass and Treble. |
| ✌️ **2-Finger Rotate** | **Overlap** | Rotate two fingers to seamlessly shift the incoming track's overlap position relative to the currently playing track. |
| ✌️ **2-Finger Drag (Vertical)** | **Crossfader** | Slide two fingers up or down to crossfade between Deck A and Deck B. |
| ✌️ **2-Finger Drag (Horizontal)** | **Seek / Scratch** | Swipe two fingers left or right to quickly rewind or fast-forward the playhead. |
| 🖐️ **3-Finger Rotate** | **Platter Spin** | Spin three fingers to literally "spin the record" and manually scrub the playhead. |
| 🖐️ **3-Finger Pinch** | **Master Volume** | Pinch in or out with three fingers to control the master output volume. |

> [!TIP]
> **Track Selection:** Gestures apply to the currently selected track(s). To select a track, simply tap on its waveform on the platter. If no track is selected, spatial fallbacks apply: gestures starting near the outer edge target Deck A, and gestures near the center target Deck B.

## 🎵 Harmonic Mixing & Auto Sync
- **Auto Beat Sync:** Instantly syncs the BPM and phase of your tracks so your beats are perfectly aligned.
- **Harmonize:** Utilizes our advanced Harmonic Engine to automatically detect the key of your tracks and pitch-shift them to complementary keys (using the Camelot Wheel system) without altering the tempo.

## 🚀 Getting Started
1. Load tracks from your Library into the horizontal track list at the bottom of the screen.
2. Tap a track to load it onto the first available deck.
3. Tap the **Play** button (or the center spindle) to start the platter.
4. Perform global gestures to mix, match, and morph your tracks!

## 🛠️ Architecture
Built natively for Android using **Jetpack Compose** and **Kotlin Coroutines**. The audio engine relies on high-performance custom renderers to draw the dynamic audio spectrum as continuous oscillating paths.
