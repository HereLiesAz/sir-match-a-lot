# Security

## Reporting a vulnerability

Open a private report through GitHub Security Advisories:
<https://github.com/HereLiesAz/sir-match-a-lot/security/advisories/new>.

If that is unavailable, open an issue saying that you have found a security
problem and asking for a private channel — **without the details**. Please do not
put a working exploit in a public issue.

This is a hobby project with no security team and no guaranteed response time. A
realistic expectation is a reply within a couple of weeks.

## What is in scope

The app runs locally and has no backend, so the interesting surface is narrower
than usual:

- **The room protocol** (`sync/`) — the UDP discovery responder on port 8888 and
  the WebSocket server on port 8890, and anything reachable by sending them
  malformed input.
- **Parsers fed by untrusted input** — `PlaylistParser` (M3U, XSPF, Atom, RSS),
  `LinkParser`, `SessionLink`, and the store's JSON and ZIP handling in
  `AzphaltStoreRepository`. A malicious playlist, session link, or pack is a real
  threat model: they come from outside.
- **`AudioDecoder`** where a malformed media file could do something worse than
  fail.
- Anything that writes outside the app's private storage, or reads outside what
  the user granted.

## Known and accepted

These are design decisions, documented rather than hidden. Reporting them is
welcome as a discussion; they will not be treated as vulnerabilities in
themselves.

### The room is paired, and encrypted after that

Joining a room takes three things, and no two of them are enough:

1. **A key exchange.** The joining device and the host each generate an
   ephemeral P-256 key pair, exchange public keys, and derive a session key by
   ECDH. Nothing else in the conversation travels in the clear.
2. **Six digits, compared by two people.** Both devices derive the same six
   digits from the exchange and show them. A key exchange on its own stops a
   passive listener and *nothing else* — anyone in the middle can run one
   exchange with each side and read everything — so the exchange has to be
   authenticated by a channel the attacker is not on. The two people looking at
   each other's screens are that channel. A device in the middle holds two
   different exchanges and cannot make both screens agree.
3. **Both users pressing approve.** The host's approval says "this is the device
   I meant to admit". The joining device's approval says "this is the host I
   meant to join". Either alone leaves the middle open.

Everything after that is AES-256-GCM, with a fresh nonce per frame: a listener
on the network sees two public keys and then frames that do not open. Tampering
fails to authenticate rather than decrypting to something plausible.

The room code is still not a credential — it is broadcast in the clear by
discovery — but it is no longer only a label either: it is mixed into the key
derivation, so two devices holding different codes cannot reach the same
session even if their exchange otherwise succeeds.

The transport underneath is cleartext `ws://`, and the manifest permits that
explicitly (`usesCleartextTraffic`). Without it the platform blocks `ws://` at
targetSdk 28 and above and joining cannot work at all. That is why the
confidentiality is at the application layer instead: a network security config
cannot express "cleartext to a phone whose address I will not know until I find
it", and TLS to a self-signed certificate on an address discovered by broadcast
authenticates nothing that the pairing code does not authenticate better.

**What this does not do.** It does not stop somebody who is standing next to you
and approves the pairing on your own device. It does not survive a user pressing
approve without looking at the digits, which is the failure mode every scheme of
this shape has. And an ephemeral key per connection means no device is
remembered: pairing happens again next time.

**Why a pairing step at all**, given the requirement was one-press connection
between phones in a booth: because one press was buying an open port. The
compromise is that discovery is still automatic — a device finds the room by
itself — and the only thing added is one screen on each side with the same six
digits on it. No accounts, no server, and no typing.

**What it means in practice:** an attacker on your network can do nothing until
somebody approves them on the host's screen. Once approved — which is to say,
once you have let them in — they can move the crossfader, trigger pads, load
tracks that are already in your library, and seek the decks.

They cannot read your files or write to them, and they cannot make the app fetch
a URL of their choosing. Two narrower statements, because the sentence here used
to be broader than the truth: `load_track_direct` carries a track id that
reaches `trackDao.getTrackById`, which *is* a query against the library database
driven by an attacker-chosen string, and a track imported from a playlist keeps
its remote `sourceUri`, so loading one can cause an outbound fetch to an address
the attacker chose only in the sense that they chose which of your own rows to
play.

**If this is not acceptable for your use**, do not start hosting — it is off
unless you turn it on, and the rest of the app is unaffected.

### Hosting opens listening ports

Ports 8888 (UDP) and 8890 (TCP) are open while hosting, and only while hosting.

### Session links are readable

They carry track titles, artists, cue times and the room code in a query string,
by design, so a link can recreate a session. Anyone holding the link can read
them, as can any host the link is opened through.

### Playlist import fetches a URL you supply

By design — that is the feature. The fetch is plain `HttpURLConnection` with a
15-second timeout, and the response is parsed as a playlist document, never
executed. XML parsing has `disallow-doctype-decl` set, which closes off external
entity expansion.

### Android backup is enabled

`allowBackup="true"`, so library metadata and settings can be included in the
user's own Google backup. Disclosed in [PRIVACY.md](PRIVACY.md#androids-own-backup).
The single manifest flag is where to change it if that is unwanted.

## Out of scope

- The absence of root or tamper detection. The app is GPL-3.0; anyone may build
  and modify it.
- Anything requiring physical access to an unlocked device.
- `azphalt.org` itself, which is a separate service. Report issues with the store
  to whoever runs it.
- Automated scanner output with no demonstrated impact.

## A standing note on CI

The `github-advanced-security` check fails on every pull request in this
repository, and has done since before this file existed. The cause is Copilot
autofix returning `model_not_supported` from its own API before it reads any
code — it is an availability problem in that service, not a finding about this
codebase, and no change here can make it pass. Disabling Copilot autofix under
**Settings → Code security** stops it. CodeQL is unaffected and reports
separately.

Do not read that red check as an unaddressed security result.
