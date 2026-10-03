# Cyclone V5 Alpha 78: voice mode that stays voice mode

Developer alpha for owner testing. It builds on Alpha 77 (Cyclone Live) and includes it.
- **Mobile:** `5.0.0-alpha.78.dev1` (version code 223).
- **Cyclone for Windows:** unchanged (runtime `5.0.0-alpha.68.dev1`).
- **Glass:** `1.0.0-alpha.40` (unchanged).

Your Alpha 77 test found four problems in live voice mode. This build fixes what caused each one, and logs every voice
run so the next test shows exactly where time goes.

## What you saw, and what caused it

**1. "Swipe down" opened the full working screen and the Ask bar, instead of staying in voice mode.**
- **Cause:** a spoken request entered Cyclone the same way as a typed one. That path starts the Ask panel (analysis,
  then working), which covers the screen.
- **Fix:** a spoken request now goes straight to the modes router and never touches the Ask panel.
  - A quick action runs under the voice panel, with its big Stop in reach.
  - A task started by voice shows as the small island, not the full working panel.

**2. The swipe took about 10 seconds.**
Several things added up:
- **The panel:** the Ask panel opened and drew itself first.
- **Two screen reads:** Instant read the whole screen once before routing and again before the swipe.
- **The gesture's landing spot:** the swipe starts in the middle of the screen, where the Ask panel was. The gesture
  could land on Cyclone's own window.

Fixes:
- **No panel:** voice doesn't open it (see 1).
- **One read:** the screen is read once per move. A gesture, an app to open, the camera or a call reads it right
  before the move. Only "tap …" commands read it before routing, because they need its labels.
- **No landing on Cyclone:** while Cyclone makes a gesture, the voice panel and the AI button let touches through, as
  the main overlay already did. A swipe lands on the app, never on Cyclone.

**3. Stopping took about 5 seconds.**
- **Cause:** voice's Stop didn't stop a running quick action. You were stopping it through the Ask panel's stop, which
  needs two taps or a two-second hold.
- **Fix:** voice's Stop now cancels the quick action at once. The panel with Stop stays up while it runs.

**4. "I didn't catch that. Tap me to try again." on every tap.**
- **Cause:** one hiccup (a silenced recording, or one transcription error) switched Drive to Android's speech
  recognizer for the rest of the session. When that recognizer couldn't start in that state, it failed at once on
  every tap, and there was no way back.
- **Fix, a clear rule for which "ear" listens:**
  - **Same-turn handover:** an ear that can't hear hands over to the other one in the same turn, once. Cyclone's
    recording hands to Android's recognizer; a broken recognizer hands back to the recording.
  - **No switch on network errors:** a network or model error in transcription never switches ears; the next tap
    just tries again.
  - **Tap to finish:** a tap now finishes listening in recognizer mode too (before, it did nothing).
  - **Microphone service:** a small race is fixed. A listen started right after the previous one could record
    before the microphone service held the microphone, which Android silences.

**"Open open Telegram."** Speech-to-text sometimes repeats a word. A word said twice in a row now counts once, so it
stays an instant command instead of going to the slow Mind.

## Voice runs in the logs

Every voice turn is now a run in Cyclone's run history (Glass → Runs, and the run diagnostics). Each stage has its
time since the tap, for example:
```
+0 ms       · Listening with Cyclone's recording (a tap)
+120 ms     · Microphone service holds the mic
+2,300 ms   · Speech ended: 900 ms of audio
+2,850 ms   · Heard: "swipe down"            (transcription 550 ms)
+2,860 ms   · Quick command to the modes router
+3,700 ms   · instant: done                  (route 2 ms · swipe down: 820 ms)
```
- **What's recorded:**
  - which ear listened, and every handover with Android's error code;
  - what was heard and how long transcription took;
  - where the request went;
  - what came back and what Cyclone said.
- **Instant runs:** every Instant run, typed or spoken, is also its own run with each stage timed (reading the screen,
  routing, the move, a hand-up).
- **Privacy:** like any run, a voice run keeps your words and Cyclone's lines, never audio. The run history redacts
  secrets as it does for every task.

## Decisions: JEV now, OpenAI Decisions later

- **One place for decisions:** all decisions go through one provider, `mind/decide`:
  - Speed → Auto's quick decision;
  - Instant's follow-up controls (the shutter, the call button);
  - the Pilot's "Decision model" route.
- **JEV is connected now, text only:** it never gets a screenshot, and none is taken for it.
- **OpenAI's Decisions API is frozen:** it isn't on OpenRouter yet. Its slot is ready (same questions, same answer
  reader, image support). Switching is one small update, with a checklist in plan 41 §15.
- **Settings → Fast mode → Decision model:** now shows the provider instead of a free text field.

## Tests

- **New:**
  - `VoiceEarsTest` (4): same-turn handover, back to the recording after a broken recognizer, one try per ear.
  - `VoiceRunLogTest` (3): timings, a failed run, a broken log never breaking a turn.
  - `DecisionsTest` (4): JEV live and text only, OpenAI frozen, no screenshot for JEV.
- **Extended:**
  - `ModesTest` (+3): a doubled word, only a tap reads the screen before routing, no screenshot for a box that can't
    see.
  - `VoiceLiveTest` (+1): the panel and Stop stay up during a quick action; Stop closes it.
- **Updated:** `DriveVoiceFallbackContractTest` now pins the new ear rule instead of the old one-way switch.
- **Guards:**
  - `test_decisions_guard.py` (new);
  - `test_voice_boundaries.py`: voice may also call `stopVoiceRequest`, which only cancels a quick action;
  - `test_modes_guard.py`.
- **Full phone suite:** 2258 tests, 0 failures. **CI guards:** all 244 pass.

## Physical acceptance

UNVERIFIED. On the Pixel, in Driver mode:
1. "Swipe down": the voice panel stays, Cyclone's Ask screen doesn't open, and the swipe happens in about a second.
2. "Open Telegram", then "open Chrome": each opens straight away.
3. During a quick action, tap Stop: it stops at once.
4. Tap the orb several times in a row: no "I didn't catch that" loop.
5. Open Glass → Runs (or the diagnostics) and send the "Voice: …" runs. Their timings show what is still slow.

## Not yet

- Streaming transcripts and early commit (Live still waits for the end of speech and the transcription).
- Undo on "no, …".
- The Lab suite for Live.
