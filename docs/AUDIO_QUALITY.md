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

### 1. Interpolation quality — sample-rate conversion **fixed**, extreme pitch ratios outstanding

This was recorded as the biggest gap, and the worst part of it turned out to be
worse than described. The note above said Catmull-Rom is "inaudible at rate ≈
1.0", which is true — but the engine was never *at* rate 1.0. `Deck.rateScale`
divides the clip's rate by the output rate, so a 44.1 kHz file on a 48 kHz device
played at rate 0.919 **permanently**, through the 4-point spline, for every track
and every sample. This is the common case on Android, not an edge case.

Measured, at 0.5 amplitude, comparing the spline against the windowed-sinc
converter now in `dsp/SincResampler` (THD+N after least-squares removal of the
test tone; the measurement floor is -154 dB):

| Signal | Catmull-Rom | Windowed sinc |
| :--- | ---: | ---: |
| 1 kHz, 44.1→48 kHz | -89.5 dB | -130.1 dB |
| 10 kHz, 44.1→48 kHz | **-26.4 dB** | -128.8 dB |
| 16 kHz, 44.1→48 kHz | **-11.7 dB** | -121.5 dB |
| 23 kHz alias, 48→44.1 kHz | **-5.3 dB** | -115.1 dB |

At 16 kHz the spline's error was 11.7 dB below the signal. That is not a subtle
loss of air at the top end; it is gross distortion, and it was in the path of
every track.

**Fixed by** converting once at load time — `PcmBuffer.resampledTo`, called from
`loadOntoDeck` — with a Kaiser-windowed-sinc polyphase filter. Because
`from/to` reduces to a small rational (44.1↔48 kHz needs 160 phases), the phase
table holds *every* phase the conversion can ask for, computed exactly, with no
inter-phase interpolation. The cutoff is pulled back by half the transition width
so the stopband, not the middle of the skirt, begins where folding begins.
Passband is flat to 20 kHz within 0.08 dB.

Kernel length was chosen by measurement, not by the "32–64 taps" guess above,
which was wrong: 64 taps gives only ~20 dB of alias rejection at this cutoff.
128 taps is the knee — 108 dB rejection, 0.08 dB at 20 kHz, 279 ms per 60 s of
mono audio.

The payoff compounds: with the clip already at the output rate, the render loop
runs at rate exactly 1.0, where the interpolating read lands on integer positions
and returns stored samples untouched. A test asserts this, so a regression that
quietly reintroduces per-sample conversion fails the build.

**Still outstanding:** large *deliberate* pitch ratios. A 0.5x or 2x shift still
goes through the spline in the render loop, where a fixed filter bank cannot be
used because the rate varies continuously — that is the same property that makes
scratching work. Cheap interpolation remains correct for extreme scratch, where
the artefact is part of the effect. The gap is now confined to intentional large
pitch shifts rather than affecting all playback.

### 2. Source storage is 16-bit — **fixed**

`PcmBuffer` now carries the decoder's native depth. A 16-bit source is stored as
planar `ShortArray`, which for a CD rip or anything lossy-decoded is *lossless* —
the source holds no more than that, so float storage would cost twice the memory
for information that is not there. A source `MediaCodec` reports as
`ENCODING_PCM_FLOAT` is stored as planar `FloatArray` and stays float through
every operation: slicing, sample-rate conversion, time-stretching and pitch
shifting all preserve it.

The two storages are exclusive — a buffer holds one or the other, never both —
and asking a float buffer for its 16-bit arrays throws rather than quietly
quantising a whole track at a call site that looks free.

The render loops branch **once per clip per block**, as predicted: `Deck` and
`Sampler` resolve the depth outside the sample loop and read from the matching
array, so there is no per-sample conversion and no widened copy of a 16-bit
track.

Measured: a tone at a quarter of a 16-bit LSB survives a float buffer, and
survives a 96 kHz → 48 kHz conversion. Through 16-bit storage the same tone is
exactly zero.

**Consequence, recorded:** `DecodedCache` counts **bytes**, not frames. Counting
frames would have made the cache believe a float track was half its real size,
which would have turned the budget that exists to prevent `OutOfMemoryError` into
one that permits twice the audio it means to. A pair of five-minute hi-res stereo
tracks is about 230 MB and will not fit alongside much else on a 256 MB heap;
`overBudget` reports that before the allocator does.

### 3. Time-stretch quality — and where NDK genuinely earns its cost

`TimeStretcher` is WSOLA. For beat-matching, where ratios stay within a few
percent, it is transparent enough, and during a scratch it is bypassed entirely
because pitch should follow rate like a real turntable. So its weak regime is
largely designed around.

"Largely" is doing work in that sentence. WSOLA smears transients and can warble
on sustained tonal material even at modest ratios, and a critical listener will
hear it on a piano or a held vocal where they would not hear it on a drum loop.

**Decided: Rubber Band via NDK, under the GPL.**

Rubber Band is the state of the art and it is C++. This is the strongest case for
native code anywhere in this project — stronger than the latency argument, which
is largely masked by touch and display latency, and stronger than the GC
argument, which the 17 ms output buffer absorbs.

The shape is a hybrid, and it stands on its own merits rather than as a
compromise: the engine and all analysis stay Kotlin and stay unit-tested, and one
C++ library handles time-stretching — offline work on a background thread, where
JNI overhead is irrelevant. `TimeStretcher` becomes an interface with the existing
WSOLA implementation retained as the fallback and, usefully, as the correctness
oracle its tests already are.

**Licensing consequence, recorded deliberately.** Rubber Band is GPL-or-commercial
and the GPL route was chosen, so Sir Match-a-Lot itself is GPL-3.0 (see
`LICENSE`). Every recipient of a build may fork, rebrand, and redistribute it,
including a monetised build, and that cannot be withdrawn from any version
already shipped. GPLv3 was picked over GPLv2 because Rubber Band is
"GPLv2 or later" and so permits it; if Google Play distribution ever hits friction
over GPLv3's installation-information terms, GPLv2 is the more conservative
fallback and remains compatible.

Rejected alternatives, for the record:
- A Kotlin phase vocoder with identity phase locking. Cheaper and license-free,
  and better than WSOLA on tonal material, but short of Rubber Band.
- SoundTouch (LGPL), which would have left the app's own license untouched, at
  lower quality than Rubber Band.

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
