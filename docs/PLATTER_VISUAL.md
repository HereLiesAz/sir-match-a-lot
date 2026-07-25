# Platter visual specification

Written from three reference images supplied for the circular waveform, recorded
here in words because the images themselves do not live in the repository. Where
the references disagree with each other, the resolution is noted.

## What the references show

**Reference A — dense white starburst on black.** Several hundred very thin
radial rays springing from a ring. Rays are *separate straight segments*, not a
connected outline. Lengths vary enormously: the body sits short and even, and
perhaps a dozen rays shoot out four or five times further than their neighbours.
No smoothing — the rays are hard-edged. The centre is empty of waveform; the ring
is implied by where the rays begin.

**Reference B — chunky green bars.** Roughly forty bars, each with visible width
and a clear gap to the next, arranged around a ring. Squarish caps. Same varying
lengths, same implied ring. Coarser and more legible than A.

**Reference C — rainbow zigzag.** The faithfulness reference. A genuine waveform
wrapped around a circle: dense radial excursion tracing real amplitude, with a
handful of very long spikes, and colour varying by region around the ring. Large
dynamic range, and no drawn circle anywhere.

## Resolved specification

Reference A is the primary target — "the one that fits my vision most" — with B
and C contributing the bar treatment and the amplitude faithfulness respectively.

1. **Discrete radial rays, one per angular bucket.** From the ring outward for
   Deck A, inward for Deck B. Each ray is its own line segment.

   This is the single most important correction. The previous implementation
   built a `Path` connecting alternating peak and valley points, which produces a
   connected zigzag ribbon — the "just zigzags that have nothing to do with the
   audio's actual waveform" complaint. None of the three references is a
   connected path.

2. **Ray density follows circumference**, via `PlatterGeometry.rayCount`, so
   density holds constant while the platter is zoomed with the three-finger
   gesture.

3. **Exaggerated height mapping**, via `PlatterGeometry.exaggeratedHeight`. A
   linear mapping cannot give reference A's look: real music has a low average
   amplitude, so linear scaling gives the near-flat band that was drawn before,
   while simply multiplying by a bigger constant clips every peak to the same
   maximum and loses the spikes entirely.

   Reference A's drama comes from **contrast** — a short, fairly even body with a
   dozen rays shooting far out — not from raising the whole ring. So the gamma is
   only mildly below 1 (0.75), lifting quiet detail just enough to be visible,
   paired with a large 2.4x overshoot so genuine transients careen past the body.

4. **Dense hairline rays.** Reference A reads as several hundred very thin rays,
   so default spacing is 2 px with a 2048 cap, and the bright core stroke is
   hairline-width. The forty-bar treatment of reference B is the coarse end of the
   same mechanism, reachable by widening the spacing.

5. **Live level scaling.** Ray length is multiplied by the metered output level
   from `Mixer.level`, so the ring grows and shrinks with the music. Previously
   the "reaction" was `sin(visualizerPhase * 50f)`, a free-running oscillator
   unrelated to the audio, which is why it never matched the beat.

6. **Colour identifies the song.** In reference C each distinct colour is a
   different track, not a different energy band — the ring is readable as "which
   song am I looking at" at a glance. So each clip on a deck gets its own stable
   hue from `ClipPalette`, and the measured `EnergyCurve` modulates that hue's
   brightness along the ring rather than choosing it.

   This reconciles two requirements that look contradictory: the early request for
   waveforms "color coded for energy" and reference C's per-song colouring. Hue
   carries identity, luminance carries energy.

7. **Glow** as three stacked strokes of the same geometry — wide and dim, medium,
   then a bright narrow core — with alpha driven by the live level. It is a
   visualiser as much as a tool.

8. **No circles drawn.** Not at the centre, not as a base ring, not as bounding
   circles. The ring's existence is implied solely by where the rays start.
   References A and C both show this.

9. **Playhead**: a red glowing slash centred on the ring, total length twice the
   combined outward and inward waveform height at its current angle, so it
   bounces over the hills and valleys as it sweeps. Collapses to a glowing dot
   when no waveform is present.

## Testable parts

Rendering itself is Compose `Canvas` and is not unit-testable, so the arithmetic
it depends on is pulled out and tested instead:

- `fractionForTime` / `timeForFraction` — angle *is* time, one revolution per deck
  cycle. Previously the playhead advanced at a hardcoded `2*PI / 10f` rad/s while
  the computed cycle duration went unused, so its position corresponded to
  nothing.
- `exaggeratedHeight` — monotonic, zero at zero, quiet detail lifted, peaks
  overshooting.
- `rayCount` — scales with radius, bounded.
- `playheadHalfLength` / `playheadIsDot` — length tracks the waveform, dot when flat.
