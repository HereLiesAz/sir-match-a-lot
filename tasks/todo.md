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
- [x] Director `Start` lines bars up: the incoming deck is started `lead`
      seconds before its (bar-snapped) entry, where `lead` is the time to the
      outgoing deck's next bar line (`ClipMusic.secondsToNextBar`, read from the
      outgoing playhead). The planner's first-beat phase is no longer applied to
      a conformed clip. Limits: skipped when the lead would start before the
      file begins; accurate to the few ms between reading one playhead and
      writing the other, not sample-accurate; `BeatSync.align` itself still
      compares first beats at t=0 (used only for unconformed clips and the
      SYNC button).
- [x] Persist markers in analysis: `Track.markersCsv` (Room v5, migration 4->5,
      schema 5.json), measured by `TrackAnalyzer` (drops, builds, breakdowns,
      vocal entry), read by the ViewModel instead of recomputing on every load.
      `CURRENT_ANALYSIS_VERSION` is now 2, so existing tracks are re-analysed.
- [ ] Beat anchors (a drift-free per-beat grid) and time signature: need a beat
      tracker and a meter detector; the grid is still one phase and one tempo,
      and four four is assumed.
- [x] Wire `GridSnap` into the ViewModel: `ClipSnapper` snaps a dropped or
      dragged clip against the clips on both decks, in conformed time, so bars
      land on bars and drops on drops. Over empty timeline the old session-grid
      snap still applies. Not yet done: pinch-stretch (`snapRatio`), cue
      placement, desktop.
- [ ] Plan screen: lanes, bar ruler, transitions, reorder, pin entry/exit.
- [ ] Player: queue, now playing, next/previous, shuffle, radio — driven by the
      timeline, scheduled on the audio thread.

## Review
