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
- [x] Tempo accurate enough to hold a grid for a whole track: `TempoDetector`
      now fits a weighted line through where the beats actually fall
      (progressively, so a coarse period cannot lose the beat), instead of
      stopping at one frame plus a parabola. Synthetic clicks: tempo within 0.05
      BPM, grid within 40 ms after 300 s (was 0.3% off and 80 ms+). Real music
      is untested, and a track whose tempo genuinely drifts needs per-beat
      anchors, which this does not provide.
- [x] Time signature: `BeatGrid.inferBeatsPerBar` folds beat strengths into bars
      of three and of four and reads three only when it clearly beats four
      (contrast >= 0.2 and over twice four's, with 24+ beats); anything else is
      four. Stored as `Track.beatsPerBar` (Room v6, migration 5->6, analysis
      version 3 so tracks are re-read), carried through `TrackStructure`,
      `TimelineLayout` and `ClipMusic` so bars and snapping use the real length.
      Synthetic click tracks only; real music is untested, and no other meter
      (6/8, 5/4, 7/8) is read. Session files do not record it yet.
- [x] Octave errors: every tempo from 90 to 149 BPM on synthetic clicks is now
      read as itself (the lag is chosen on a smoothed envelope; 18 of 86 were
      read as their half before, all from 121 BPM up). 150 BPM and up is still
      ambiguous with half, which is a valid reading of the same pulse and is
      handled by the half/double-time ladder.
- [x] Wire `GridSnap` into the ViewModel: `ClipSnapper` snaps a dropped or
      dragged clip against the clips on both decks, in conformed time, so bars
      land on bars and drops on drops. Over empty timeline the old session-grid
      snap still applies. Cues are quantised to the nearest beat
      (`ClipMusic.nearestBeat`). Not done: pinch-stretch (`snapRatio` still
      rounds to whole beats of the session grid). Desktop has nothing to snap: it
      has two decks, a crossfader and a sampler, but no timeline or clip placement.
- [x] Plan screen (first cut): the Queue tab lists the plan, one row per track
      with the score and advice of the transition into it, the track playing
      highlighted, and the tracks the planner left out. Move / remove / shuffle
      while idle; tap to play from a row; PREV and NEXT (hard cuts). While a mix plays
      the playing and next tracks are locked and everything after them can be
      reordered, removed or shuffled (`MixDirector.replaceUpcoming`). QUEUE and
      RADIO on every library track. NEXT blends into the next track when it is
      already on its deck (`MixDirector.beginTransitionNow`), otherwise cuts.
      Entry and exit points can be pinned per track (IN / OUT chips on a row):
      picked from the places the planner itself would consider
      (`TrackStructure.pinOptions`), applied in `structureFor` so the director
      and the timeline both honour them. Session only: pins are not saved.
      A read-only strip above the list draws the plan (`TimelineLayout`): one
      lane per deck, the blends, and a bar ruler. Not done: dragging clips on it
      (the plan is still edited as a list), and nothing here has been looked at
      on a device.
- [ ] Player: queue, now playing, next/previous, shuffle, radio — driven by the
      timeline, scheduled on the audio thread.

## Review
