# Privacy Policy

**Sir Match-a-Lot**
Last updated: 27 July 2026

## The short version

Sir Match-a-Lot has no accounts, no analytics, no advertising, and no server of
its own. Your music, your library, and everything measured from it stay on your
device.

It does keep a crash log. When the app crashes it writes the stack trace to its
own private storage and offers, on the next launch, to file it as a GitHub
issue — which opens your browser with the text already filled in. Nothing is
sent unless you send it, and declining leaves the report on the device.

The app makes a network request only when you ask it to: importing a playlist
from a link you paste, or downloading a sample pack from the Azphalt store.
Nothing is sent anywhere in the background, and nothing about how you use the app
is collected at all.

This is verifiable rather than promised — the source is public at
<https://github.com/HereLiesAz/sir-match-a-lot>, and the whole of the app's
network code is three places: `AzphaltStoreRepository`, `fetchPlaylistDocument`
in `SirMatchALotViewModel`, and the `sync` package.

## What the app stores, and where

All of this is in the app's own private storage on your device. None of it is
transmitted, and uninstalling the app deletes it.

| What | Where | Why |
| :--- | :--- | :--- |
| Track titles, artists, file locations | Local database | The library list |
| Measured tempo, key, energy, beat grid | Local database | Matching and beat sync |
| Waveform peaks and energy curves | App-private files | Drawing the platter |
| Your settings | App-private preferences | Sample rate, memory budget, visuals |

**Your audio files are never copied, uploaded, or modified.** The app reads them
to decode and measure them, holds the decoded audio in memory while it is loaded
on a deck, and releases it. What it keeps afterwards is the *measurements* —
numbers describing the music, not the music.

## Permissions

Each one, why it exists, and what happens if you refuse it. The full detail is in
[PERMISSIONS.md](PERMISSIONS.md).

- **Read audio files** (`READ_MEDIA_AUDIO`, or `READ_EXTERNAL_STORAGE` below
  Android 13) — to open the music you choose. Refuse it and the app runs, but has
  nothing to play.
- **Internet** — only for the two things named above. Refuse it (by taking the
  device offline) and everything except playlist-link import and the store still
  works.
- **Notifications** — the progress notification for a library analysis run, which
  is also where its Pause and Stop buttons are. Refuse it and analysis still
  runs; you just cannot see or control it from outside the app.
- **Foreground service** — keeps that analysis running while the app is in the
  background, which is exactly when you would leave it to get on with a large
  folder.

**The app does not use your microphone.** It does not have microphone permission.
The sampler's record button captures the app's own mixer output — the audio it is
already producing — which needs no permission and never involves the mic. (An
unused `RECORD_AUDIO` declaration was removed in July 2026; if you installed an
earlier build, it was requested but never used by anything.)

**The app does not use location, contacts, camera, or your identity**, and does
not request them.

## When the app talks to the network

Only these, only on your action:

**1. Importing from a link.** When you paste a URL, the app fetches that URL to
read the playlist. It goes to whatever host you pasted. A YouTube playlist link is
rewritten to YouTube's public Atom feed for that playlist
(`youtube.com/feeds/videos.xml?playlist_id=…`) so the song list can be read
without an API key. That request is to YouTube, and — like any web request —
YouTube sees your IP address and what you asked for. The app sends no identifier
of its own beyond a `User-Agent` of `SirMatchALot`.

The app never downloads audio from YouTube or Spotify. See [TERMS.md](TERMS.md)
for why.

**2. The Azphalt store.** When you press *Store*, the app requests
`https://azphalt.org/packages?types=audio` and downloads a pack if you install
one. Azphalt sees your IP address and which pack you took, as any download server
would. Nothing identifying you is attached.

That is the complete list. There is no telemetry, no "anonymous usage data", no
first-run ping, and no remote configuration.

## Multi-device sync

The linked-session feature runs entirely on your local network, peer to peer.
One device hosts; the others find it with a UDP broadcast on port 8888 and connect
over a WebSocket on port 8890. **No traffic leaves your network and none of it
passes through any server operated by this project.**

What travels between the devices while linked: the room code, a device role and
the label "Android Device", transport state, crossfader position, cue points,
track ids, pad triggers, and filter positions. No audio and no files are sent.

Two things worth knowing before you host:

- The connection is **unencrypted and unauthenticated**. Anyone on the same
  network who knows the port can connect and send the same commands. It is
  designed for a room you control — a booth, a rehearsal space, a house — not a
  public Wi-Fi network.
- Hosting opens listening ports on your device for as long as it is on.

Sync is off unless you start it.

## Session links

A shared session link carries, in its query string: the titles and artists of the
loaded tracks, cue point times, the crossfader position, the reference tempo and
key, and the room code. **Anyone you send the link to can read all of that**, and
so can any server the link is opened through — the default target is the
project's GitHub Pages site, whose host sees the request like any other web
request.

No file paths, no audio, and nothing about you personally are in a link. The app
only produces one when you ask it to.

## Android's own backup

The app currently allows Android's automatic backup (`allowBackup="true"`), so
your library metadata and settings may be included in the device backup Google
makes to your own Google account, under Google's terms rather than ours. Your
audio files are not in the app's storage and so are not part of this. You can turn
Android backup off for the app in your device's system settings.

## Children

The app is not directed at children, collects nothing from anyone, and has no
social, chat, or user-content features.

## Changes

Material changes will be recorded in this file, whose full history is public in
the repository — you can see exactly what changed and when, which is a stronger
guarantee than a "last updated" line.

## Contact

Questions, or anything here that does not match what the app does:
<https://github.com/HereLiesAz/sir-match-a-lot/issues>.

If you believe you have found a security problem, please read
[SECURITY.md](SECURITY.md) first.
