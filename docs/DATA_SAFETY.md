# Play Data safety — the answers, and why

Google Play's Data safety form is filled in by hand and is separately binding
from the privacy policy: the two must agree, and the form is what users see on
the listing. This is the source of truth for what to enter, so the answers are
traceable to code rather than reconstructed from memory each release.

Keep this in step with [PRIVACY.md](PRIVACY.md) and
[PERMISSIONS.md](PERMISSIONS.md). If a release changes any answer, change it here
in the same pull request.

## Summary answers

| Question | Answer |
| :--- | :--- |
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Not applicable — no user data is collected |
| Do you provide a way for users to request that their data be deleted? | Not applicable — uninstalling removes everything; nothing is held elsewhere |

"Collect" in Play's definition means transmitting data off the device. This app
transmits none. Everything it derives from your music — tempo, key, energy,
waveform peaks — is written to app-private storage and never leaves.

## Every data type, and the answer

**None of the following are collected or shared.** The ones worth explaining are
the ones a reviewer might expect to be ticked.

| Category | Collected | Shared | Note |
| :--- | :--- | :--- | :--- |
| Location | No | No | Nothing is location-aware |
| Personal info | No | No | No accounts, names, emails, or IDs |
| Financial info | No | No | No purchases in the app |
| Health and fitness | No | No | — |
| Messages | No | No | — |
| Photos and videos | No | No | — |
| **Audio files** | **No** | **No** | Read locally to decode and measure. Never uploaded, copied, or modified. See below |
| Files and docs | No | No | Same: read in place, never transmitted |
| Calendar | No | No | — |
| Contacts | No | No | — |
| App activity | No | No | No analytics of any kind |
| Web browsing | No | No | — |
| App info and performance | No | No | **No crash reporting.** Nothing is sent on a crash |
| Device or other IDs | No | No | No advertising ID, no install ID, no device ID |

### On "Audio files"

This is the answer most likely to be questioned, so the reasoning should be on
record.

The app reads audio files you choose, decodes them in memory, measures them, and
holds the decoded audio only while it is loaded on a deck. What persists is
measurements — a tempo, a key, a peak envelope — in the app's own private
storage.

Play's definition of *collection* is transmission off the device. No audio, no
file, and no derived data is transmitted anywhere. So: **not collected.**

If that ever changes — a cloud library, a backup of measurements, a crash
reporter that could include a filename — this answer changes with it, and the
change belongs in the same pull request as the code.

## Network activity a reviewer will see

Declaring no collection does not mean declaring no network. The app makes
requests, all user-initiated, and none of them carry user data:

1. **Playlist import** — fetches a URL the user pasted. For a YouTube playlist
   link this is rewritten to YouTube's public Atom feed. Outbound content: the
   URL itself and a `User-Agent` of `SirMatchALot`.
2. **Azphalt store** — `GET https://azphalt.org/packages?types=audio`, then a
   pack download if the user installs one. Outbound content: none beyond the
   request.
3. **Local-network sync** — UDP discovery on 8888 and a WebSocket on 8890,
   between the user's own devices. Nothing leaves the local network; no server of
   ours is involved.

None of these send data *about* the user. Requests carry an IP address the way
every network request does, which Play does not treat as collection by the app.

## Other listing declarations

- **Ads:** none. No ad SDK is linked. The app contains no advertising.
- **In-app purchases:** none in the app itself.
- **Target audience:** general; not directed at children. No social, chat, or
  user-generated-content features.
- **Foreground service:** declared `dataSync`, used by `AnalysisService` to keep a
  library analysis alive while backgrounded. The in-app justification is that
  analysis is an FFT pass over every whole track and a large folder takes minutes,
  which is exactly when a user backgrounds the app.
- **Permissions needing justification:** none. The sensitive-permission list is
  empty since `RECORD_AUDIO` was removed — see
  [PERMISSIONS.md](PERMISSIONS.md#removed-record_audio).
- **Privacy policy URL:** Play requires a publicly reachable URL. The policy lives
  at [PRIVACY.md](PRIVACY.md); link to it on a public host — the GitHub blob URL
  for the file, or the project site — and make sure the link resolves without a
  login before submitting.

## Data deletion

Play asks how users delete their data. All of it is app-private storage on the
user's own device: the library database, the peak and energy files, and the
settings. Uninstalling the app removes every one of them, and there is no copy
anywhere else to request the deletion of.

Within the app, deleting a track from the library removes its row and its
measurement files.

Note that Android's automatic backup is currently enabled
(`allowBackup="true"`), so library metadata and settings may be included in the
device backup Google makes to the *user's own* account. That is Google's copy
under the user's control, not ours, and it is disclosed in
[PRIVACY.md](PRIVACY.md#androids-own-backup). If that is not wanted, the manifest
flag is the single place to change it.
