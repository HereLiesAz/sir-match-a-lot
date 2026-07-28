# The `.sir` session format

A session is an evening's work: the running order, where every clip sits on its
deck, the pads and the takes recorded onto them. This is how it is written down.

The format is documented because a file the user can be handed is a file they are
entitled to understand. A session that will not open should be diagnosable by
unzipping it and reading the JSON, not by asking the author of the app.

## The container

A `.sir` file is a **zip archive**:

```
session.json          the manifest
takes/pad-0.wav       audio for sampler pad 0, if it has any
takes/pad-1.wav
...
```

Zip because the tools to look inside one are already on every machine. WAV
because takes are the only thing in a session that exists nowhere else — every
track can be found again from its title and length, and a recording somebody made
over the top of their own mix cannot. Re-encoding those to a lossy format on save
would mean a session degraded a little every time it was opened and saved again.

Takes are 16-bit PCM, interleaved, at whatever rate the pad held. A float pad is
rounded on the way out: that is a real loss, accepted because these are minutes of
stereo audio inside a document someone may email, and 16-bit WAV opens anywhere.

## The manifest

`session.json`, UTF-8. Every field has a default, so a manifest may omit anything
it has nothing to say about.

| Field | Meaning |
| --- | --- |
| `version` | Format version. See below. |
| `name` | What the user called it. |
| `savedAtMillis` | Epoch millis at save. |
| `writtenBy` | App version, for diagnosing a file that will not open. |
| `reference` | The tempo and key everything on the decks was conformed to. |
| `lineup` | The planned running order, if one was built. |
| `deckA`, `deckB` | What is on each deck, and where. |
| `pads` | Pad assignments, each naming its take file. |
| `crossfade` | Fader position, 0..1. |

**Clip positions are in seconds, never frames.** A frame index is meaningless at
a different engine sample rate, and the rate is a setting the user can change
between saving a session and opening it.

## How a track is written down

Not as a file path. A `content://` URI is a grant to one app on one device,
revocable, pointing at a file the user is free to move. A session that recorded
paths would open to empty decks the first time it was restored after a reinstall,
or opened on a second device, or sent to somebody — and it would do so without
explanation.

So a track is written down as **identity plus enough description to find it
again**:

```json
{
  "id": "…", "title": "Nightdrive", "artist": "…",
  "durationMs": 247000, "bpm": 128.0, "camelotKey": "8A",
  "firstBeatSeconds": 0.25, "downbeatOffset": 0, "energyLevel": 6,
  "cuePoints": [32.0, 96.5],
  "sourceHint": "content://…"
}
```

Opening resolves in four passes, most certain first. Each library track is
claimed at most once, so two saved tracks can never both resolve to the same
file:

1. **The same id** — the same library; nothing to think about.
2. **Title, artist and duration** — duration to within a second.
3. **Title and duration** — the same recording, credited differently. Common
   with remixes and compilations.
4. **The source hint**, and only if it is not contradicted. A path is the weakest
   evidence and the only kind that goes stale on its own: files get replaced in
   place, and a re-scan hands the same URI to whatever is there now. When both
   sides know their length and the lengths disagree, the hint is out of date and
   is refused.

Duration is the strongest evidence available. Titles get edited and artists get
spelled several ways, but a recording is the length it is — and a duration of
zero means "never decoded", not "zero seconds long", so it is never treated as
matching another unmeasured track.

There is deliberately **no fuzzy title matching**. Closest-string would find
something for every track including the ones that genuinely are not here, which
converts a reportable miss into a silent substitution.

That ordering follows one rule: **a wrong match is worse than a miss.** A track
reported missing is a job the user can finish. A session that quietly loads a
different recording is a set that goes out sounding wrong for reasons nobody can
see. So anything unresolved is *named* — on screen, not only in a message that
disappears.

## Versioning

`version` is present from the first release rather than added at the first
breaking change, because by then every file already written is unversioned.

A reader refuses a file whose version is higher than it understands. An older
reader guessing at a newer layout produces a session that is confidently wrong,
which is worse than one that will not open. Unknown *fields* are ignored, so a
later version may add things without breaking earlier readers.

## What is not in it

- **The audio of tracks.** A session is a description of a set, not a copy of a
  record collection. Tracks are resolved against the library on the machine
  opening it.
- **Analysis artefacts** — peaks, energy curves, landmarks. All of it is derived
  from audio the opening device has, and recomputing costs a decode that opening
  a track does anyway.
- **The learned transition model.** That belongs to a person, not to an evening,
  and shipping it inside a file meant to be shared would carry one user's habits
  onto another user's device. See `docs/PRIVACY.md`.

## Reading one by hand

```sh
unzip -o session.sir -d session/
cat session/session.json | python3 -m json.tool
```
