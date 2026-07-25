# Audio quality

Written against the requirement that this app may be used for critical
listening. It records which choices in the signal path are quality-relevant,
which are settled, and which are still not good enough — so the gaps are visible
rather than discovered later.

## The signal path

```
PcmBuffer (decoded once)
  -> Resampler        variable signed rate: transport, scratch, pitch
  -> TimeStretcher    tempo independent of pitch (offline; bypassed while scratching)
  -> Biquad EQ        low shelf 200 Hz, high shelf 4 kHz
  -> deck gain
  -> equal-power crossfade
  -> master gain
  -> safety limiter   linear below -1 dBFS
  -> AudioTrack       ENCODING_PCM_FLOAT at the device's native rate
```

Everything from the resampler onward is float32. The only place precision is
deliberately reduced is source storage, discussed below.

## Settled

**Float output, no output dither.** The engine writes `ENCODING_PCM_FLOAT`, so
there is no truncation to 16-bit on the way out and no dither is needed. Mixing,
EQ, and gain are all float32; biquad state is float64, which keeps low-frequency
shelves well-conditioned.

**Engine runs at the device's native mixer rate.** `AudioTrackOutput.forDevice()`
queries `PROPERTY_OUTPUT_SAMPLE_RATE` and `PROPERTY_OUTPUT_FRAMES_PER_BUFFER`
and runs the graph at that rate. This matters more than it looks: running at a
fixed 44.1 kHz on a 48 kHz device makes AudioFlinger resample every block with
an algorithm we neither chose nor control. Matching the native rate reduces the
signal path to **one** sample-rate conversion — done once per track at load
time, where the algorithm is our choice and we can afford a good one.

**Limiter threshold at -1 dBFS.** Any always-on nonlinearity colours everything
through it. The curve is exactly linear (bit-transparent) below -1 dBFS and only
compresses above, so correctly gain-staged material never touches it. It exists
to stop a hot two-deck sum from hard-clipping, not to shape the sound. An earlier
revision put the threshold at -6 dBFS, which compressed ordinary listening
levels; that was wrong and is now guarded by a test.

**Equal-power crossfade.** Sum of squares is 1 at every fader position, verified
across the sweep. The previous implementation gave full gain to *both* decks at
centre, making mid-transition roughly 6 dB hot.

**Continuous reverse.** The playhead is a `Double` frame index advanced by a
signed rate and read with interpolation, so deceleration through zero into
reverse has no discontinuity. Tested by sweeping the rate from +1 to -1 and
asserting no sample-to-sample step exceeds the signal's own maximum slope. A
seek-based implementation cannot do this at all.

**Denormals.** Biquad state is `Double` and the shelves are well above the
denormal range at audio rates, so no flush-to-zero hack is required.

## Not good enough yet

These are real gaps. None of them is hidden behind a claim that it is fine.

### 1. Interpolation quality at large rate ratios — the biggest gap

`Resampler` uses 4-point Catmull-Rom cubic interpolation. At rate ≈ 1.0 —
transport, scratching, small pitch trims — this is inaudible. At ratios far from
1.0 it is not: cubic interpolation has a slow stopband rolloff, so downward rate
changes alias and upward ones image. A ±6% beat-match trim is fine. A 0.5x or 2x
pitch shift is audibly grainy.

**Fix:** a polyphase band-limited interpolator — windowed sinc, on the order of
32–64 taps with a Kaiser window, precomputed into a phase table — for the
transport and pitch paths, with an anti-imaging lowpass when the ratio exceeds 1.
Keep cheap cubic interpolation only for extreme scratch, where the artefact is
part of the effect and latency matters more than stopband depth.

This also governs the load-time sample-rate conversion described above, so it is
the single highest-value quality item outstanding.

### 2. Source storage is 16-bit

`PcmBuffer` stores planar `ShortArray`. For 16-bit sources — CD rips, most
lossy-decoded material — this is lossless. For 24-bit FLAC or any hi-res source
it discards real information before playback begins.

The reason is memory: a five-minute stereo track is 26 MB as `Short` and 53 MB as
`Float`, and two decks hold several clips each. But that is a budgeting problem,
not a justification for throwing away bits an audiophile source actually carries.

**Fix:** carry the decoder's native depth. Keep 16-bit storage when the source is
16-bit, and a float path when `MediaCodec` reports `ENCODING_PCM_FLOAT` or
24-bit. The `Deck` render loop branches once per clip per block, not per sample,
so the cost is negligible.

### 3. Time-stretch quality — and where NDK genuinely earns its cost

`TimeStretcher` is WSOLA. For beat-matching, where ratios stay within a few
percent, it is transparent enough, and during a scratch it is bypassed entirely
because pitch should follow rate like a real turntable. So its weak regime is
largely designed around.

"Largely" is doing work in that sentence. WSOLA smears transients and can warble
on sustained tonal material even at modest ratios, and a critical listener will
hear it on a piano or a held vocal where they would not hear it on a drum loop.

**Fix, in order of increasing cost:**
1. A phase vocoder with identity phase locking — better on tonal material,
   implementable in Kotlin, testable the same way WSOLA is.
2. Rubber Band or SoundTouch via NDK. These are the state of the art and they are
   C++. **This is the strongest case for native code anywhere in this project** —
   stronger than the latency argument, which is mostly masked by touch and
   display latency, and stronger than the GC argument, which the 17 ms output
   buffer absorbs. Note the licensing: SoundTouch is LGPL, Rubber Band is
   GPL-or-commercial, and that choice constrains how this app can ship.

A hybrid is worth considering on its merits rather than as a compromise: the
engine and all analysis stay Kotlin and stay unit-tested, and one C++ library
handles time-stretching, which is offline work on a background thread where JNI
overhead is irrelevant.

### 4. EQ is two fixed shelves

200 Hz and 4 kHz, chosen to serve the bass-boost gesture. Adequate, and correct
RBJ coefficients verified against their analytic magnitude response, but a
three-band DJ EQ with a sweepable mid is the conventional expectation.

## How these get checked

Quality claims here are asserted by tests in `app/src/test`, not by inspection:

- filter responses against their design targets (-3 dB at cutoff, shelf gain at
  DC, unity outside the band)
- limiter transparency below threshold, boundedness above, odd symmetry
- crossfade constant power across the sweep
- reverse-playback continuity through zero
- WSOLA pitch preservation, with a companion test showing a plain resample *does*
  shift pitch, so the first cannot pass for the wrong reason

When items 1–3 land, each needs a measurement to match: stopband attenuation for
the resampler, bit-exactness for hi-res storage, and a transient-smearing metric
for whichever stretcher replaces WSOLA.
