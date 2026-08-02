# Sir Match-a-Lot — the public API

Requirement H4 asks that the app's complete API be exposed. This is it.

The app has exactly three externally-visible surfaces, and they are all open
formats rather than an SDK:

| Surface | Transport | What it is for |
| --- | --- | --- |
| [Discovery](#1-discovery) | UDP broadcast, port 8888 | Finding the device that is hosting |
| [Room protocol](#2-the-room-protocol) | WebSocket, port 8890 | Playing one instrument from several devices |
| [Session links](#3-session-links) | HTTPS URL query parameters | Sending a loaded session to someone else |

Nothing here needs an account, a key, or a server. A room is a handful of
devices on the same Wi-Fi and nothing else, and a session link is a string.

Anything is free to speak these — a laptop, a hardware controller, a script, a
web page. That is what "expose the API" means here: the protocol is the product
surface, and it is specified rather than merely implemented.

Implementations live in `app/src/main/java/com/hereliesaz/sirmatchalot/sync/`:
`WebSocketProtocol.kt` (the wire format), `SyncServer.kt` (host), `SyncClient.kt`
(peer), `SyncRole.kt` (roles), `SessionLink.kt` (links).

---

## 1. Discovery

One device hosts; the others find it. There is no registry and no configuration.

**Request** — a peer broadcasts, to `255.255.255.255:8888`, the exact bytes:

```
SIR_MATCH_A_LOT_DISCOVER
```

Peers retry roughly once a second, up to ten times, then give up.

**Response** — the host replies, to the sender's address and port, with JSON:

```json
{
  "serverIp": "192.168.1.42",
  "wsUrl": "ws://192.168.1.42:8890",
  "roomCode": "K7QW"
}
```

`serverIp` and `wsUrl` are required; `roomCode` is informational, and a peer
still has to send it in [`join`](#join). IPv6 addresses are not advertised — a
host with no IPv4 address on the local network does not answer.

Discovery is best-effort by nature. Wi-Fi networks with client isolation, or
"AP isolation", drop broadcast between clients; on those, the host's `wsUrl` has
to be typed in by hand. That is a property of the network, not a bug to fix.

---

## 2. The room protocol

WebSocket, RFC 6455, default port **8890**. Text frames carrying JSON objects,
one message per frame. No subprotocol is negotiated and no extensions are
supported; a client asking for one is answered without it.

Frames are not fragmented and payloads are capped at **1 MiB** — room messages
are small JSON, so anything larger is a mistake or an attack, and a 64-bit
length field is attacker-controlled by definition.

The handshake is ordinary: `GET` with `Upgrade: websocket`, `Connection:
Upgrade`, and a `Sec-WebSocket-Key`. The host answers `101` with
`Sec-WebSocket-Accept` = base64(SHA-1(key + `258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)).
Anything that is not a valid upgrade gets `400`. As RFC 6455 §5.1 requires,
host-to-peer frames are never masked and peer-to-host frames always are.

Ping and pong are handled: a ping is answered with a pong carrying the same
payload. Close ends the connection.

### Pairing, and what is in the clear

Exactly three message types travel unencrypted, and only during pairing:
`hello`, `hello_ack` and `join_refused`. Everything else — every message
documented below — is carried inside a `sealed` envelope.

```
peer → host   { "type": "hello", "key": "<base64 P-256 public key>", "name": "Pixel 8" }
host → peer   { "type": "hello_ack", "key": "<base64 P-256 public key>" }
```

Both sides then derive, by ECDH over P-256 and HKDF-SHA256:

- a session key, used for AES-256-GCM;
- **six decimal digits**, from the same derivation, which both devices display.

The digits are the authentication. A bare key exchange is agreed just as happily
with a device in the middle, which is why one is not enough on its own: the
middle device holds two separate exchanges and cannot make the two screens
match. Both users compare and approve. The joining device sends `join` only
after its own user approves; the host honours it only after its own user does.

Every message after `hello_ack` is:

```json
{ "type": "sealed", "data": "<base64 nonce ‖ AES-256-GCM ciphertext>" }
```

The plaintext inside is the JSON documented below, unchanged. A frame that does
not authenticate is dropped without a reply. The room code is mixed into the
key derivation, so two devices holding different codes never reach the same
session.

### The room state

The host holds one authoritative state object. A peer's update is **shallow
merged** into it and the result broadcast to *everyone, including the sender*,
so every device converges on the same values rather than each trusting its own.

Shallow, specifically: sending `{"crossfader": 40}` must not erase the decks,
and sending a whole `deckA` replaces that deck wholesale rather than having its
fields merged one at a time with stale ones.

```json
{
  "isPlaying": true,
  "crossfader": -35,
  "deckA": {
    "isPlaying": true,
    "bpm": 128,
    "pitch": 0.0,
    "currentTime": 84.5,
    "cues": [12.0, 48.0, null, null]
  },
  "deckB": { "…": "same shape" }
}
```

| Field | Type | Range | Applied by a receiving app |
| --- | --- | --- | --- |
| `isPlaying` | boolean | — | yes, toggles transport |
| `crossfader` | integer | −100 (full A) … +100 (full B) | yes |
| `deckX.cues` | array of 4 numbers or null | seconds | yes |
| `deckX.isPlaying` | boolean | — | carried, not applied |
| `deckX.bpm` | number | — | carried, not applied |
| `deckX.pitch` | number | semitones | carried, not applied |
| `deckX.currentTime` | number | seconds | carried, **deliberately** not applied |

`currentTime` is shared but never applied to a local deck. Applying it would
fight the receiving device's own render loop, and because every applied change is
echoed back as state, it would do so in a feedback loop. Position is
synchronised by [`seek_deck`](#events) instead, which is an event — it happens
once, at a moment, rather than continuously asserting a value.

Anything not listed is preserved and relayed untouched, so a newer version can
add fields without older devices dropping them.

### Peer → host messages

Every message carries `type`. Unknown types are ignored rather than refused, so
a newer peer talking to an older host degrades instead of failing.

#### `join`

```json
{ "type": "join", "roomCode": "K7QW", "role": "pads", "name": "Alex's phone" }
```

Sent inside a `sealed` envelope, once this device's own user has approved the
pairing code — not on connect, because a join sent before the digits are
compared is a join to a host nobody has confirmed. Required: `update_state` and
`trigger_event` from a connection that has not joined are dropped, as is a join
from a device the host's user has not approved. If the host has a room code
set and this one does not match, case-insensitively, the host replies with
`{"type": "join_refused", "reason": "room code does not match"}` and closes.
Refused rather than ignored — a peer that typed the code wrong should learn
that, not sit in silence. (The message has always been sent; until recently no
client read it, so the peer sat in silence anyway.)

`role` is one of the [roles](#roles). `name` is a human label for the roster.

#### `update_state`

```json
{ "type": "update_state", "roomCode": "K7QW", "state": { "crossfader": 40 } }
```

`state` is merged into the room state and broadcast back as `state_synced`.

#### `trigger_event`

```json
{ "type": "trigger_event", "roomCode": "K7QW", "event": "play_sampler_pad", "payload": { "padId": 3 } }
```

Acted on by the host **and** relayed to every peer, so a device driving the pads
from across the room plays the host's instrument rather than talking to a relay
that ignores it.

### Host → peer messages

#### `init_state`

```json
{ "type": "init_state", "roomState": { "…": "the whole room state" } }
```

Sent once, in reply to an accepted [`join`](#join). A joiner needs the room as it
stands, not only what changes after it arrives.

It used to be sent on connect, before any join could have been made — which
meant a peer given `join_refused` had already been handed the entire room state,
and a peer that never sent `join` at all was never checked against the room code
and could still drive the host. A connection that has not joined now receives
nothing and commands nothing.

#### `state_synced`

```json
{ "type": "state_synced", "state": { "…": "the whole room state" } }
```

The full merged state after any update — not a delta.

#### `event_triggered`

```json
{ "type": "event_triggered", "event": "kaoss_move", "payload": { "x": 0.4, "y": 0.8, "padId": 0 } }
```

#### `join_refused`

```json
{ "type": "join_refused", "reason": "room code does not match" }
```

The connection closes immediately afterwards.

### Events

| `event` | `payload` | Effect |
| --- | --- | --- |
| `play_sampler_pad` | `{"padId": int}` | Triggers that sampler pad |
| `kaoss_move` | `{"x": -1..1, "y": 0..1, "padId": int}` | Moves the master filter. `x` is the sweep — 0 bypasses, negative brings a lowpass down, positive takes a highpass up. `y` is resonance. Both are clamped |
| `sync_click` | `{}` | Beat- and key-matches Deck B to Deck A |
| `load_track_direct` | `{"deck": "A"\|"B", "trackId": string}` | Loads a track by library id |
| `nudge_deck_direct` | `{"deck": "A"\|"B", "direction": "forward"\|"back"}` | Nudges that deck by 50 ms |
| `seek_deck` | `{"deck": "A"\|"B", "time": seconds}` | Seeks that deck |

`trackId` is the sending device's library id, so `load_track_direct` only means
anything between devices sharing a library. Across libraries, use a
[session link](#3-session-links), which identifies tracks by title and artist
for exactly this reason.

### Roles

A device declares in `join` what it is for, and shows only that screen. This is
what makes several devices one instrument rather than three copies of the same
app: one person browses, one plays the decks, one plays the pads.

| `role` | Screen | Also accepted |
| --- | --- | --- |
| `all` | everything (default) | `full`, empty, absent |
| `library` | library and loading | `browser`, `browse` |
| `decks` | platter, crossfader, filters | `deck`, `platter`, `mixer` |
| `pads` | sampler pads | `pad`, `sampler`, `performance` |

Matching is case-insensitive and tolerates surrounding space. An **unrecognised
role resolves to `all`**, so a device joining a room run by a newer version, and
named a role it has never heard of, gets the whole instrument rather than a
blank screen.

A role is a local choice announced to the room, not a permission the host
grants. Which screen a phone shows is that phone's business; the room is told so
the roster means something.

### What the protocol does not do

Stated plainly, because a documented limit is worth more than a surprise:

- **No identity, and no memory of one.** Keys are ephemeral per connection, so
  nothing is remembered between sessions: pairing happens again every time, and
  the app cannot tell you that this is the same phone as yesterday.
- **No protection from a user who approves without looking.** The six digits are
  only as good as the comparison, which is the standing limit of every scheme
  shaped like this one.
- **Transport is still `ws://`, not `wss://`.** Confidentiality is at the
  application layer instead — see docs/SECURITY.md for why, and for what a
  listener on the network can still see (two public keys, and the size and
  timing of everything after).
- **No audio.** Only control messages travel. Every device renders its own
  audio from its own library, which is why `load_track_direct` needs a shared
  library and a session link does not.
- **No conflict resolution.** Last write wins, per top-level key.

These are the right trade-offs for a room of phones on one Wi-Fi network and the
wrong ones for anything reachable from the internet. Do not port-forward 8890.

The section above used to read "No authentication. No encryption." Both are now
wrong, which is the better direction for a limits section to be wrong in.

---

## 3. Session links

An ordinary HTTPS URL with query parameters, which is the whole point: a link
whose contents are legible can be read, edited, diffed, and handled by anything.
An opaque encoded blob would make the format private to whichever version wrote
it.

```
https://hereliesaz.github.io/sir-match-a-lot/?a=Röyksopp%20-%20Eple&a=Boards%20of%20Canada%20-%20Roygbiv&b=Aphex%20Twin%20-%20Xtal&ca=12,48&cb=8&x=-35&bpm=128&key=8A&room=K7QW
```

| Parameter | Repeats | Meaning |
| --- | --- | --- |
| `a` | yes | A Deck A track, in platter order |
| `b` | yes | A Deck B track, in platter order |
| `ca` | no | Deck A cue points, comma-separated seconds |
| `cb` | no | Deck B cue points, comma-separated seconds |
| `x` | no | Crossfader, −100 … +100 |
| `bpm` | no | The session's reference tempo |
| `key` | no | The session's reference key, Camelot notation |
| `room` | no | Room code, so a link can also be an invitation |

Tracks are `Artist - Title`, split on the **first** ` - ` only, so
"Röyksopp - Eple - Remix" is Röyksopp playing "Eple - Remix". A value with no
separator is treated as a title with no artist.

Tracks are named, not identified by id. A database id is meaningless on the
receiving device, which has its own library and has never seen the sender's row.
Title and artist are what a person can match by hand when the automatic match
fails — and the receiving app names the tracks it could not find rather than
skipping them silently, because knowing which two songs are missing is what makes
recreating the session possible.

### Parsing rules

Written down because interoperating with them is the point:

- **Unknown parameters are ignored**, so a link written by a newer version still
  opens on an older one with whatever it does understand.
- **A malformed number is skipped, not fatal.** Losing one cue point is better
  than losing the session.
- Negative cue points are dropped; `bpm` must be positive; `x` is clamped to
  −100 … +100; `room` is upper-cased.
- **Commas are left unescaped** on purpose: `ca=4,8` says what it means where
  `ca=4%2C8` does not. A comma is a legal sub-delimiter in a query string.
- `+` is written as `%20`, so a literal plus in a title survives.
- Everything else is standard percent-encoding, UTF-8.

`web/index.html` reads these parameters and displays the session, so the default
link destination is a page that understands its own links rather than one that
ignores them.

---

## Versioning

There is no version field, and adding one would not help: a version number only
tells a peer that it cannot cope, whereas these formats are built so that it can.
Unknown message types, unknown state keys, unknown roles and unknown query
parameters all degrade to something usable rather than to an error.

Two things are therefore commitments, and will not change meaning without a new
name: the wire spellings above, and the ranges in the tables. A field may be
added at any time.
