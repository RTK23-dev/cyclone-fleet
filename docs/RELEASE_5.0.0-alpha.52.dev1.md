# Cyclone V5 Alpha 52 — Drive: faster

Developer alpha for owner testing, built on Alpha 51 (Command Center C0) and Alpha 50 (Drive: conversations), which it includes.
- **Mobile:** `5.0.0-alpha.52.dev1` (version code 196).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.52.dev1.exe`. It has no Drive changes; it carries alpha.51's Command Center.
- **Glass:** `1.0.0-alpha.28` (unchanged since alpha.51).

This release makes Drive quicker and more accurate with the newest models (research of 27 September 2026), and adds
the measurements the first car test needs. Nothing about what Drive may do by voice has changed.

## What changed

**Newest speech recognition first.** Cyclone now prefers these, whenever OpenRouter lists them:
1. **Meta Muse Voice Transcribe:** made for push-to-talk; about a quarter of the errors of the old default; final text
   about 0.16 s after you stop.
2. **Microsoft MAI-Transcribe.**
3. **Google Gemini 3.5 Transcribe.**
4. **The older, cheaper Whisper line,** last.

The cost stays about $0.0002 per request. Your language setting is passed along as a hint.

**Instant commands.** Timers and alarms are understood on the phone itself, with no model, so the confirmation starts
the moment the words are in:
- "set a timer for ten minutes", "ten minute timer", "timer for half an hour";
- "wake me up at 6 am", "set an alarm for 7:30";
- "zet een timer van 5 minuten", "zet een wekker om 7 uur".
Anything more ("a timer for the pasta", "remind me …") still goes to the model, and nothing is guessed.

**No silence while it thinks.**
- **Ready-made lines:** "On it.", "Okay.", "Done.", "Sending." and "Still working on it." are made once in your voice
  when Drive starts, in memory, so they play at once.
- **A quick "On it.":** when a confirmation takes more than about half a second to start, you hear "On it." first,
  then the full line, never both at once.

**A quicker start.** As soon as Cyclone hears you actually talking, it opens its connection to OpenRouter, so the
transcription starts without the handshake. A tap you don't speak into still uses nothing.

**Voice quality: Fast or Best** (Settings → Voice):
- **Fast** keeps Gemini 3.8 Flash-Lite TTS;
- **Best** picks Gemini 3.8 Flash TTS, currently #2 in the Artificial Analysis Speech Arena.

**JEV is watching (not deciding).** JEV is TypeSafe's new decision model (15 September 2026). It is very fast and
nearly free, but still in beta.
- **Every request:** Cyclone asks JEV what kind of request it was, next to the usual understanding model.
- **Settings → Voice → Instant decisions** shows how often JEV agreed, how fast it was, and how often it was right
  when it was sure.
- **Its answer is never used.** If JEV earns it on your own drives, it can take over simple requests in a later
  release. You can switch the watching off there.

**Test voice** now also transcribes the same sample with up to two other listed transcription models and shows their
times and what they heard, so you can compare them on your phone.

## Safety

- **Unchanged:**
  - only a send can be approved by voice, after a word-for-word readback and an explicit "yes" recognised on the phone;
  - paying, deleting, permissions, sign-in, passwords and handovers wait for you on screen;
  - the microphone opens only when you tap;
  - nothing is recorded to disk.
- **JEV is watch-only:** a CI guard checks its answer can never become a voice action. Its tally keeps only request
  kinds and timings, never what you said.
- **Still no network on silence:** silent and blip-only taps still make no network call at all. The early connection
  waits for real speech, and a test covers it.

## Validation and limits

Tests that pass:
- **Voice core** (JVM): instant timers and alarms (English and Dutch), including what must still go to the model;
  the turn engine taking an instant command without a model call; the new model order and Best voice; JEV's request,
  its tolerant answer reader and the tally; speech time never counting a blip.
- **CI guards:** voice boundaries (now also "JEV only watches"), Task Kit, permissions, versions.
- **Mobile CI:** the Android build and all unit tests.

**Physical status: UNVERIFIED.** None of this has run on your Pixel 8 or in a car yet.

Honest limits:
- **Model availability:** the newest models are used only while OpenRouter lists them, and model names and prices
  change. Settings shows which model is really in use.
- **JEV's API is alpha:** if its answer shape changes, the JEV row says "No answer yet" and nothing else is affected.
- **Car audio:** the car's Bluetooth microphone, reading messages aloud and speaking over Cyclone are next, in D3.

Install the APK as an update; do not uninstall it. It is signed with the rotated Cyclone release key.

Suggested car test (about 15 minutes):
1. Settings → Voice → **Test voice**. Note the timings and which transcription model heard the sample best.
2. Drive with Driver mode on. Ask:
   - "set a timer for ten minutes";
   - a question about your calendar;
   - "navigate home";
   - "reply to" someone who messaged you.
3. Afterwards, look at **Instant decisions (JEV)**: how often it agreed, and its time.
4. Tell me what felt slow or wrong. That, and JEV's numbers, decide D3's first steps.
