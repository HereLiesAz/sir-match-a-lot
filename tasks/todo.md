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

- [ ] `GridSnap`: multi-tier snapping between a moving clip and reference
      points (bar lines, beats, markers). Strong: bar on bar, marker on marker.
      Medium: half-bar offset (beat 4 on beat 2). Weak: any other beat. Weaker
      tiers pull from a smaller radius. Reports which tier engaged.
- [ ] `MixTimeline` model: clips on a master timeline (seconds), each with its
      grid, tempo ratio and markers, plus transitions between them.
- [ ] `TimelineLayout`: turn a `MixPlan` (ordered steps) into a `MixTimeline`
      with each incoming clip placed so its entry bar lands on the outgoing
      clip's exit bar.
- [ ] Tests for all of it, including 4/4 over 4/4, beat 4 on beat 2, 3/4 vs 4/4,
      drop on drop, and tempo-conformed clips.

## Later slices (not started)

- [ ] Fix `BeatSync` / `applyAlignment` phase (sign, frame, downbeats).
- [ ] Persist beat anchors, time signature and markers in analysis.
- [ ] Wire `GridSnap` into the ViewModel in place of `BeatSnap`.
- [ ] Plan screen: lanes, bar ruler, transitions, reorder, pin entry/exit.
- [ ] Player: queue, now playing, next/previous, shuffle, radio — driven by the
      timeline, scheduled on the audio thread.

## Review
