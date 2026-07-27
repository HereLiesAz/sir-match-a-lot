# Terms of Use

**Sir Match-a-Lot**
Last updated: 27 July 2026

Sir Match-a-Lot is free software. These terms cover using the app; the source code
is separately governed by its licence, which is the GNU General Public License
version 3 — see [LICENSE](../LICENSE) in the repository root.

## The software

Provided **as is, without warranty of any kind**, express or implied, including
merchantability and fitness for a particular purpose. This is the standard GPL-3.0
position and it is meant literally: nobody is on call, there is no support
contract, and the app may fail at the worst possible moment.

Which is worth saying plainly for this app in particular. **Do not use it for a
performance you cannot afford to have interrupted** unless you have tested it, on
your device, with your music, in the conditions you will play in. It decodes
whole tracks into memory, drives a real-time audio thread, and can be starved by
another app or the operating system. A phone is not a rig.

## Your music is yours

The app does not supply music. You bring your own files, and you are responsible
for having the right to use them — owning them, licensing them, or having them
under terms that permit what you are doing, including any public performance.

Nothing you load is uploaded, shared, or transmitted by the app. See
[PRIVACY.md](PRIVACY.md).

## What the app will not do

**It does not download audio from YouTube, Spotify, or any similar service, and
it will not be made to.** Their terms prohibit it and Google Play's developer
policy specifically bans apps that facilitate it.

A YouTube playlist link is read through the public Atom feed YouTube publishes for
it, which yields the **names** of the songs. Those arrive as library entries with
no audio, for you to point at files you already hold. The app names what you asked
for; it does not take it.

Anything that serves an actual audio file — a podcast enclosure, a direct link, a
purchased download, your own server — imports and plays normally.

## The Azphalt store

Sample packs are fetched from `azphalt.org`, which is a separate service with its
own terms. Packs are downloaded only when you ask for one. What you may do with a
pack's contents is set by whoever published it, not by these terms.

## Multi-device sessions

Linking devices runs peer to peer on your local network with **no encryption and
no authentication**. Anyone on the same network who knows the port can connect to
a hosted session and control it.

That is a deliberate trade for one-press setup with no accounts and no
infrastructure, and it means: host on a network you control. Do not host a session
on public or untrusted Wi-Fi. See [SECURITY.md](SECURITY.md).

## Session links

A link you generate contains the titles and artists of the loaded tracks, cue
points, and the room code — readable by anyone you send it to. Only share what you
are content to share.

## Liability

To the fullest extent permitted by law, the authors and contributors are not
liable for any damages arising from use of the app. This includes lost or damaged
audio files, an interrupted or failed performance, hearing damage from output
levels, and damage to audio equipment.

**On levels specifically:** the app includes a limiter on the master bus, but it
is a safety limiter, not a guarantee. You are responsible for the gain reaching
your ears and your speakers. Check the volume before putting on headphones.

## Changes

These terms may change. The full history is public in the repository, so any
change can be seen and dated exactly.

## Contact

<https://github.com/HereLiesAz/sir-match-a-lot/issues>
