# Mix planning

Goal: play music the way Spotify / YouTube Music does — pick a track, album,
playlist or "radio from this track" and it just plays on, with every handover
planned, beat-matched and bar-aligned — and still let the user edit the plan
by hand with snapping that behaves like a DJ tool.

Source: two glee audits (UI/UX, and everything else) of c776b36. The app builds
an ordered track list and improvises each transition at playback. There is no
plan model, alignment ignores bars, and snapping is a single unconditional beat
tier.

## Slice 1 — domain core (pure JVM, `shared/jvmCommonMain`)

- [x] `GridSnap`: multi-tier snapping between a moving clip and reference
      points (bar lines, beats, markers). Strong: bar on bar, marker on marker.
      Medium: half-bar offset (beat 4 on beat 2). Weak: any other beat. Weaker
      tiers pull from a smaller radius. Reports which tier engaged.
- [x] `MixTimeline` model: clips on a master timeline (seconds), each with its
      grid, tempo ratio and markers, plus transitions between them.
- [x] `TimelineLayout`: turn a `MixPlan` (ordered steps) into a `MixTimeline`
      with each incoming clip placed so its entry bar lands on the outgoing
      clip's exit bar.
- [x] Tests for all of it, including 4/4 over 4/4, beat 4 on beat 2, 3/4 vs 4/4,
      drop on drop, and tempo-conformed clips.

## Later slices (not started)

- [x] `applyAlignment` phase sign: `Deck.alignTo` nudges the playhead by the
      negative of the beat offset, scaled by tempo ratio (PhaseAlignmentTest
      renders two click decks; the old sign fails 4 of its 5 tests).
- [ ] `BeatSync` phase frame and downbeats: `align()` still compares first beats
      at t=0 and ignores bar position, and the director's `Start` applies it to
      a deck already moved to a bar-snapped entry. Should be computed from the
      outgoing deck's beat phase at the moment of the start, on the bar.
- [ ] Persist beat anchors, time signature and markers in analysis.
- [ ] Wire `GridSnap` into the ViewModel in place of `BeatSnap`.
- [ ] Plan screen: lanes, bar ruler, transitions, reorder, pin entry/exit.
- [ ] Player: queue, now playing, next/previous, shuffle, radio — driven by the
      timeline, scheduled on the audio thread.

## Review
