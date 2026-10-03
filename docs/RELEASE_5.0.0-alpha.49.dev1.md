# Cyclone V5 Alpha 49 — Drive: talk

Developer alpha for owner testing, built on Alpha 48 (one-click Windows setup), which it includes.
- **Mobile:** `5.0.0-alpha.49.dev1` (version code 193).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.49.dev1.exe`. It has no changes of its own since alpha.48.
- **Glass:** `1.0.0-alpha.27` (unchanged).

This is the first Drive release (plan 32, D1): talk to Cyclone in the car.

## What changed

**Driver mode.** Turn it on in Settings → Drive → Driver mode, or with the **Cyclone Drive** Quick Settings tile.
- The small Ask bubble becomes a large voice orb on Tilt Glass (84 dp; Smaller and Larger are in Settings).
- **Tap the orb and talk.** You hear a short sound, Cyclone listens until you stop, and it confirms in one short line,
  for example "Setting a 10 minute timer."
- **Then it works quietly.** The orb shrinks back to the button with a turning ring, and the screen is yours again.
- **When it ends,** it tells you: "Done: timer set for 10 minutes." or what went wrong. If a task takes longer than a
  minute, it says "Still working on it." once.
- **Move the orb:** hold it for one second, then drag it to either edge. It stays there, separately for portrait and
  landscape.

**AI mode.** While Cyclone listens, thinks or speaks, a Tilt Glass panel rises from the bottom:
- **What you said** appears in grey, and what Cyclone says in white, in large type (two lines at most).
- **The orb reacts:** it breathes with your voice, swirls while thinking, and pulses while speaking.
- **One big Stop** ends the request and the task.
- **Dimming:** the screen dims only while a request is live.
- **When Cyclone needs you** (a question, an approval), the orb glows warm, a sound plays and AI mode rises. In this
  release Cyclone reads questions aloud and you can answer them. Everything else says "That needs you on screen when
  you're stopped."

**It stays out of the way:**
- Accidental taps, silence, "uh" and "never mind" close without using the internet.
- Timers, alarms, calendar events and contacts run directly, with no screen.
- A spoken task runs in the background when this phone can (Background work ready), so Maps stays on screen.
  Otherwise it borrows the screen and gives it back to the app you were in.
- If Cyclone hears something unclear, it asks one short question. After a second unclear answer it says "Okay, tap me
  when you're ready."

**Settings → Drive → Voice:**
- **Models:** speech to text (or Android's own recognizer, on the phone), the voice model and voice with a Preview,
  and the fast understanding model. All are picked from OpenRouter's live list; Cyclone never uses a model that list
  doesn't show.
- **Language** and **End of speech** (how long Cyclone waits after you stop).
- **Test voice:** Cyclone speaks a sample request, transcribes it back and understands it. It then shows each
  timing on this phone next to its target.

## Safety and privacy

- **Voice never acts.** Only two things leave voice:
  - a new request, exactly as if you typed it;
  - Task Kit commands: Stop, and your answer to Cyclone's question.
  A CI guard (`test_voice_boundaries.py`) checks that voice touches no phone tool or engine.
- **By voice nothing is approved in this release.** Paying, deleting, permissions, passwords and handing over the phone
  always wait for you on screen. (Approving a *send* after a word-for-word readback comes with Drive D2.)
- **Microphone:** it opens only when you tap. There is no wake word. Its foreground service ("Cyclone is listening")
  runs only while Cyclone listens.
- **Audio:** recordings stay in memory, go to OpenRouter as one clip for transcription, and are never saved. The
  guard checks that `voice/` writes no files.
- **Spoken lines** pass the same secret redaction as everything Cyclone stores: codes, keys and card numbers are never
  said.
- **Settings note:** use Drive hands-free, with the phone mounted, and keep your eyes on the road. It is not a
  replacement for Android Auto.

## Validation and limits

Tests that pass:
- **Voice core** (JVM):
  - the voice detector on synthetic signals: silence, hiss, road noise, a blip, speech, pauses, the 15 s cap;
  - the turn engine: every path, including zero network calls on silent, blip, filler and cancel opens;
  - the rules, the strict understanding parser (anything malformed is unclear), the spoken copy and its length limits,
    redaction, model choice from the live list, timings, earcons and WAV;
  - the AI mode face.
- **CI guards:** voice boundaries, Task Kit, permission architecture (the microphone service permission is an
  infrastructure exemption), release versions and product invariants.
- **Mobile CI:** the Android build and all unit tests.

**Physical status: UNVERIFIED.** Drive has not run on your Pixel 8 or in a car yet. Unverified so far:
- the 2.0 s p50 / 3.0 s p90 confirmation target;
- the microphone service behind other apps;
- audio ducking under Maps;
- the local voice fallback.

Honest limits:
- **Microphone access:** Android must let the microphone service start from the overlay. If listening does not
  start, say so and we'll adjust.
- **Answers by voice:** only Cyclone's questions. Details cards, approvals and sends wait on screen until D2.
- **Car audio:** car Bluetooth microphone routing, reading messages aloud and interrupting by speaking are D3. You
  can already interrupt by tapping the orb while Cyclone speaks.
- **Costs:** Test voice and every spoken request use your OpenRouter key.

Install the APK as an update; do not uninstall it. It is signed with the rotated Cyclone release key.

Suggested checks:
1. Settings → Drive → Voice → **Test voice**. Note the four timings.
2. Turn on **Driver mode**. Allow the microphone when Android asks.
3. Tap the orb and say "Set a timer for ten minutes". You should hear a short confirmation, then "Done".
4. Tap the orb and stay silent: it should close by itself after about 4 seconds, with nothing spent.
5. Hold the orb for a second and drag it to the other edge.
