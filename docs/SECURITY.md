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

### The room protocol is unencrypted and unauthenticated

A hosted session accepts commands from anyone on the same local network who
knows the port. There is no pairing, no key, and no transport security. A room
code is a *label*, not a credential — it identifies the session; it does not
protect it.

**Why:** the requirement is one-press connection between phones in a booth, with
no accounts and no server. Every mechanism that would fix this properly adds a
step or a service.

**What it means in practice:** host on a network you control. What an attacker on
your network can do is move the crossfader, trigger pads, load tracks that are
already in your library, and seek the decks. They cannot read your files, reach
your library database, or make the app fetch arbitrary URLs.

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
