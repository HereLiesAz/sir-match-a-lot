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
| A9 | Points of interest tagged (drop, break, vocal entry) | **done** — drops, breakdowns, builds and peaks from `StructureFinder`, snapped to bar lines; vocal entry from `VocalDetector`, which requires harmonic content, pitch *instability* and formant-band presence together. Pitch instability is what separates a voice from a lead synth playing the same notes in the same band. Limit recorded and tested: deep vibrato from any source reads as singing |
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
| B7 | Crossfade with a correct gain law (no mid-fade level jump) | done (`audio/Mixer`, equal power); the UI crossfader now drives it, instead of setting two linear per-player gains that dipped ~3 dB at centre |
| B8 | Beat sync that converges, driven from the audio thread not the UI | done — `BeatSync` computes the correction and `AudioEngine.applyAlignment` applies it once to the deck rate and playhead, rather than chasing drift with repeated seeks |
| B9 | Live output level published for the visuals | done (`audio/Deck.outputLevel` and `audio/Mixer`'s level meter, per deck and master) |
| B10 | Multiple clips per deck; loops placeable on a deck exactly like songs | done (`audio/Clip` on a circular timeline) |
| B11 | A single sample alone on a deck loops around the whole circle | done (tested) |
| B12 | Engine runs at the device's native rate; one high-quality SRC at load | **done** — `dsp/SincResampler` (Kaiser windowed-sinc polyphase, exact phase table) converts each track once in `PcmBuffer.resampledTo`, so the render loop runs at rate 1.0. Flat to 20 kHz within 0.08 dB, 107.5 dB alias rejection; measurements in AUDIO_QUALITY.md. Native rate is now the **default** rather than the only option: `data/SampleRateOption` lets a lower rate be chosen, since rate is the one lever that changes how many bytes a minute of audio costs. Choosing one accepts AudioFlinger resampling on the way out, and the setting says so |
| B13 | Master limiter transparent at listening levels | done (-1 dBFS threshold, tested) |
| B14 | Hi-res (24-bit / float) source support | **done** — `PcmBuffer` carries the decoder's native depth; a float source stays float through slicing, resampling, stretching and shifting, and the render loops branch once per clip per block rather than converting per sample. `DecodedCache` counts bytes rather than frames, since a float sample costs twice what a 16-bit one does |

## C. The platter

| # | Requirement | Status |
| :-- | :--- | :--- |
| C1 | One circle, invisible except for the waveforms on it | done (`ui/platter/PlatterCanvas`) |
| C2 | Deck A protrudes outward, Deck B inward. Two rings, never concentric-per-track | done |
| C3 | Angle is time: one rotation = the deck cycle, derived from track duration | done (`PlatterGeometry`) |
| C4 | No circle drawn at the centre, and no bounding circles | done — nothing is drawn but rays, playhead and labels. The one thing at the centre is the transport button, which is a control rather than decoration: 26 dp, grey until a track is on the circle, and drawn *under* the waveform so the rays pass over it |
| C5 | Playhead: a glowing red slash centred on the ring, length = 2x the combined waveform height at that angle, so it bounces | done (tested) |
| C6 | Playhead collapses to a glowing dot when there is no waveform | done (tested) |
| C7 | Waveforms glow, brightening and growing with live output | done — driven by `Mixer.level`, not an oscillator |
| C8 | Deck B's waveform reaches inward toward the far side | done |
| C9 | Energy graph around the circle, repeating as needed, colour-coded | done — the analyser now persists the curve to `Track.energyPath` (`EnergyCurve.toByteArray`, window length included so a curve read back can still answer `at`), and `republishPlatter` feeds it to `PlatterClip.energy`. A track analysed before the path was written recomputes the curve at load rather than falling back to neutral forever |
| C10 | Cue markers visible on the ring | **done** — `PlatterMarker`, drawn straddling the ring: outside for Deck A, inside for Deck B, matching where each deck's rays already point. Not scaled by the metered level, so a cue stays legible during the quiet passage you are looking for it in. Structural landmarks ride the same mechanism |
| C11 | The platter is the feature — not inside a card, not on a grey panel | done |
| C12 | Identical layout in portrait and landscape; song list along the bottom scrolling horizontally in portrait; navigation bar fixed | done — needs confirming on a device |
| C13 | Song list entries are draggable onto the platter | **done** — long-press a card in the strip to lift it, drag onto the circle, and release. The ring under the finger picks the deck (outside A, inside B) and the angle picks the position: angle is time, so the fraction dropped at is the frame the clip starts on. A target marker shows both before release. Long-press rather than immediate drag, or the strip would stop scrolling |
| C14 | No A/B buttons and no drag handle on song rows | done |
| C15 | Background is an out-of-focus rave light show, genuinely driven by the audio, not a strobe | **done** — `RaveBackground` over `SpectrumMeter`. Three bands off the master bus rather than one level, so a kick and a hi-hat move different lights instead of everything pulsing together, which is what a strobe is. Brightness from the audio, motion from a free-running clock. Silence is darkness |

## D. Gestures

Superseded: the original map (pinch = BPM, 1-finger vertical = pitch, 1-finger
horizontal = EQ, 2-finger rotate = overlap, 2-finger vertical = crossfade,
2-finger horizontal = seek, 3-finger rotate = spin, 3-finger pinch = volume).
Prompt 75 replaced it with D1–D6 below.

| # | Requirement | Status |
| :-- | :--- | :--- |
| D1 | All single-finger gestures manipulate the audio clips themselves | **done** — a single finger starting on a clip drags that clip's placement around the circle, across decks, or off the platter to remove it |
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
| D13 | Easter egg: scratching too far backward triggers a very low, growling "I am Satan, Lord of Darkness", slow at first then accelerating | **done** — `ScratchModel` fires once per gesture; `GrowlVoice` synthesises the line with a source-filter formant synthesiser (glottal pulse train at 62 Hz, three resonators per phoneme, noise for the fricatives, sub-harmonic and period jitter for the growl), accelerating across the utterance. Synthesised rather than bundled, because F9 says no built-in audio clips |

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
| F1 | Local audio files supported | done — a single file, or a whole folder walked recursively (`importFolder`, `data/AudioFileFilter`). A music library is a folder of folders, so the walk descends; it is iterative with a visited set, so a deep or cyclic tree terminates rather than overflowing the stack |
| F2 | Any music service whose link resolves to a track or playlist | **done, within a deliberate boundary** — `data/PlaylistParser` reads M3U/M3U8, XSPF, Atom and RSS, plus pasted link lists and tracklists, and `importFromLink` fetches a link and expands it. Anything serving a real audio file (podcast enclosures, direct links, self-hosted, purchased downloads) imports playable. **YouTube and Spotify give song lists, not audio**, and this app deliberately does not extract audio from them — see the note below the table. That is the finished behaviour, not outstanding work: the remaining gap is one the app declines to close |
| F3 | A playlist link imports every track in it, not one | **done** — a playlist becomes one library entry per song. Entries with no playable location are kept, named, with a null `sourceUri`, rather than dropped or given invented audio |
| F4 | Long imports run in the background with a progress notification, pausable and resumable | **done** — `analysis/AnalysisService` is a foreground service with a progress notification carrying Pause/Resume/Stop actions. It owns its own database handle and analyser rather than reaching into the ViewModel, because it outlives it by design; progress travels back through `AnalysisProgressBus` so the notification and the library screen show the same figures. Pause takes effect *between* tracks, so resuming does not repeat work already paid for |
| F5 | Dropdown filter sorting the library by Camelot proximity to the Deck A track | **done, and taken further** — the sort exists (`MixPlanner.byMixScore`, on the library chips *and* on the platter), but a sort order alone was not enough to make the matching usable: the strip under the platter — the list tracks are actually dragged from — was fed the raw table in insertion order, so every measurement the app makes reached the performer only if they switched tabs. `MixPlanner.rank` now attaches each track's own match to it, the strip is ordered by it and defaults to it, and each card carries its score and what it would take to bring it in. Ordering is by the same number the card shows, so the list reads top to bottom |
| F6 | Shuffle Crate: fills both decks by harmonic compatibility and BPM match | done (`MixPlanner.shuffleCrate`, weighted-random over usable pairs) |
| F7 | Automatchic Mix: builds a full pro-grade remix playlist using every tool in the app | **done** — `MixPlanner.automatchicMix` decides the running order and per-step corrections; `domain/MixDirector` performs it, deciding when each transition starts (one crossfade before the outgoing track ends), how long it lasts (16 bars of the outgoing tempo, capped at a third of the track), and where the crossfader sits at every instant. It emits commands rather than calling the engine, so the timing is a pure function of elapsed time and is fully unit-tested |
| F8 | Auto beat sync, auto pitch, auto stretch, harmonize | **done** — `syncToDeckA` applies rate and phase through the engine and *renders* the pitch shift into the clip via `PcmBuffer.pitchShifted`, combining the harmonic interval with a keylock correction of `-12*log2(tempoRatio)` so a tempo match does not drag the key with it. Keylock is toggleable, because off is the turntable behaviour the scratch gestures depend on |
| F9 | No built-in audio clips; sample packs come from the Azphalt store at `azphalt.org` | **done** — auto-download on first launch removed; a store pack is imported when asked for, and arrives unanalysed for the analysis queue to measure. The same code path hid a bug: the track list was published only when non-empty, so clearing the library left the last non-empty list on screen for ever |
| F10 | Library stays as its own tab; play/pause is not a tab | done — the tab bar's play item is gone. Play/pause is the button at the centre of the platter, pressed through the waveform covering it, so the transport is on the instrument instead of sitting among the things that navigate |

**On YouTube and Spotify.** Their terms prohibit downloading audio, and Google
Play's developer policy specifically bans apps that facilitate it — a listing
that does so is removable, which matters because the store is the monetisation
channel. So a YouTube playlist link is read through the Atom feed YouTube
publishes for it (`youtube.com/feeds/videos.xml?playlist_id=…`, keyless and
documented), which yields the playlist's **songs**. Those arrive as named
library entries with no audio, to be pointed at files the user holds or at
Azphalt store packs. The app names what you asked for; it does not take it.

## G. Sampler and looper

| # | Requirement | Status |
| :-- | :--- | :--- |
| G1 | Kaoss-pad / Kitara style expressive pad | done (`audio/MasterFilter` on the master bus, `ui/SamplerScreen`'s FilterPad). X is a bipolar DJ filter (centre bypass, left sweeps a lowpass down, right a highpass up, log-mapped); Y is resonance to Q 8 with `1/sqrt(Q)` compensation so a sweep does not slam the limiter. Placed before the limiter and after the crossfade, so it acts on the mix and its resonant peaks are still caught |
| G2 | Record to a pad and replay it | done (`audio/Sampler` + `ui/SamplerScreen`: arm record, hold a pad to capture, hold to replay) |
| G3 | Unused pads auto-filled with samples grabbed from loaded tracks | done (`Sampler.autoFill` from `StructureFinder` loop candidates, driven by the sampler's "Fill from track"; never overwrites an occupied pad) |
| G4 | Automatic loop maker sampling loops from the active playlist | **done** — `LoopHarvest` allocates round-robin by track, best-first within a track, so every song contributes its best loop before any song contributes its second; playlist order breaks ties. Two passes, so a playlist never has to fit in memory at once |
| G5 | The sampler/looper can occupy a deck slot, showing N loops the way songs are shown | **done** — `placePadsOnDeck` puts each loaded pad on the circle as its own clip, so each gets its own arc, colour and waveform. The pads keep their audio: placing a loop should not cost you the pad |

## H. Reach

| # | Requirement | Status |
| :-- | :--- | :--- |
| H1 | Link multiple devices; each shows a different screen; all act as one instrument | done — `SyncRole` picks the screen a device shows and the tab bar follows it; the host applies remote events to its own engine as well as relaying them, so a remote pad plays the host's instrument rather than a relay that ignores it |
| H1a | A device joins only if the host approves it, and the host only if the device approves back | **done** — `RoomCrypto` agrees an ephemeral P-256 key per connection and derives six digits from the exchange; both devices show them and both users approve before anything is admitted. The host's approval alone would leave the joining device trusting whatever answered; the joining device's alone would leave the host admitting whatever asked |
| H1c | A device paired with once is not asked about again | **done** — `DeviceIdentity` keeps a long-term signing key per device and `KnownDevices` the fingerprints of the devices approved before; a remembered device rejoins silently once its signature over the current exchange verifies against the key its fingerprint names. Claiming a fingerprint proves nothing, and a replayed signature from an earlier exchange does not verify. Settings lists them and can forget them all |
| H1b | Nothing useful on the wire for anyone listening | **done** — everything after the two-message handshake is AES-256-GCM with a per-frame nonce, keyed by the exchange above and the room code. A listener sees two public keys and frames that do not open; a tampered frame fails to authenticate rather than decrypting to something plausible. `RoomCryptoTest` asserts that a device in the middle cannot make both screens show the same digits, which is the property the whole scheme rests on |
| H2 | One-click auto-connect on the same Wi-Fi — requires a device to host, which does not exist yet | done — `SyncServer` is that device: a UDP responder on 8888 answers the discovery broadcast `SyncClient` was always sending, and an RFC 6455 server on 8890 (`WebSocketProtocol`) carries the room |
| H3 | Export the loaded session (two tracks, cue points, loop settings) as a shareable link with query parameters | done — `SessionLink`; `web/index.html` reads the parameters, so the default destination understands its own links |
| H4 | Expose the app's complete API | done — `docs/API.md` specifies all three surfaces: discovery, the room protocol, and the session-link format |
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
