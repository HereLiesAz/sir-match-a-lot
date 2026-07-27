# Permissions

Every permission the app declares, what uses it, and what happens without it.

The list is short on purpose. A permission that cannot be traced to a line of
code should not be in the manifest, and one of these was: `RECORD_AUDIO` was
declared and requested on first launch while nothing in the app used it. It is
gone. See [the note at the bottom](#removed-record_audio).

## Declared

### `READ_MEDIA_AUDIO` (Android 13+) / `READ_EXTERNAL_STORAGE` (≤ Android 12)

**Used by** `AudioDecoder`, via `MediaExtractor` opening the URIs your imports
produce.

**For** reading the music you choose to import — a single file, or a folder walked
recursively.

**Without it** the app runs and every screen works, but no file can be decoded, so
there is nothing to analyse or play. Folder import through the system picker still
grants access to what you pick, since that is a separate consent.

`READ_EXTERNAL_STORAGE` carries `android:maxSdkVersion="32"`, so it is not
requested at all on Android 13 and above, where it has been replaced.

### `INTERNET`

**Used by** `AzphaltStoreRepository` (the store), `fetchPlaylistDocument` (link
import), and the `sync` package (local-network sessions).

**For** exactly three things, all of which you start: downloading a sample pack,
fetching a playlist you pasted a link to, and connecting devices on your own
network.

**Without it** — with the device offline — everything else works. Local files,
analysis, mixing, the sampler, and the platter never touch the network.

There is no telemetry, no analytics, and no first-run call home. See
[PRIVACY.md](PRIVACY.md).

### `POST_NOTIFICATIONS` (Android 13+)

**Used by** `AnalysisService`.

**For** the progress notification of a library analysis run, which is also where
its Pause and Stop controls live.

**Without it** analysis still runs and still finishes; you simply cannot see or
control it from outside the app.

This is a runtime permission on Android 13+, and until July 2026 the app declared
it without ever asking for it — so on every recent device the foreground service's
notification was suppressed and a minutes-long scan reported nothing at all
outside the Library tab. It is requested now.

### `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`

**Used by** `AnalysisService`, declared with `android:foregroundServiceType="dataSync"`.

**For** keeping a library analysis alive while the app is backgrounded — which is
precisely when someone leaves a folder of several hundred tracks to get on with
it. Analysis is an FFT pass over every whole track; a hundred songs is minutes of
work.

**Without it** analysis would be killed the moment the app left the foreground,
and would have to start again.

The `dataSync` type is the honest one: the work is decoding and measuring files,
not playing media. It is not `mediaPlayback`, because the service plays nothing.

## Not declared, and deliberately

- **`RECORD_AUDIO`** — no microphone access. See below.
- **Location**, in any form. Nothing in the app is location-aware. LAN discovery
  uses a UDP broadcast, which needs no location permission for its own sake.
- **Contacts, calendar, camera, SMS, phone, account access.** Nothing touches
  them.
- **`WRITE_EXTERNAL_STORAGE`.** Everything the app writes — peaks, energy curves,
  the database, downloaded packs — goes to its own private storage, which needs
  no permission and is deleted when the app is.
- **`WAKE_LOCK`.** The foreground service is what keeps analysis alive; the audio
  thread now stands down entirely when nothing is sounding. Holding a wake lock
  on top of that would be a battery cost with nothing to show for it.

## Removed: `RECORD_AUDIO`

It was in the manifest, and it was the *first* thing requested on first launch —
so a microphone prompt was the first thing anyone saw on opening a DJ app.

Nothing used it. There is no `AudioRecord`, no `MediaRecorder`, and no microphone
path anywhere in the source. The sampler's record button captures
`Sampler.captureFromMaster`, which reads the float buffer the mixer has just
filled on its way to `AudioTrack` — the app's own output, already in memory,
needing no permission of any kind.

Removed in July 2026 rather than justified. A permission you cannot point at a
caller for is one you have to defend in Play review and declare on the data
safety form, for a capability that does not exist.

If a future feature genuinely needs the microphone — sampling a room, a talkover
mic — it should be added back *with* that feature, requested in context when the
feature is first used rather than at launch, and written up here and in
[PRIVACY.md](PRIVACY.md) at the same time.
