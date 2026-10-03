# Cyclone V5 Alpha 77: Cyclone Live

Developer alpha for owner testing. It builds on Alpha 76 (Fast mode, the parallel Pilot) and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.77.dev1` (version code 222).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.77.dev1.exe` (runtime `5.0.0-alpha.68.dev1`, unchanged).
- **Glass:** `1.0.0-alpha.40` (unchanged).

Plan 42's first build. Every request now goes to the lowest mode that can do it: **Instant** (one obvious action, no
model), **Flash** (a few routine steps) or the **Mind**. In Drive, a quick command happens straight away, confirms
with a sound and keeps listening. The as-built notes are in `Cyclone V5 plan/42-modes-instant-flash-mind.md` §14.

## What changed

**Settings → Model & intelligence → Speed:**
- **Instant for commands** is the default. Clear commands happen instantly, with no model; everything else goes to the
  Mind as before.
- **Auto** adds one quick decision when the command isn't clear. It picks Instant, Flash or the Mind, and unsure
  always goes up. It uses Fast mode's model and route.
- **Always Mind** is the one-tap way back to how it was before.

**Instant mode.** In English and Dutch, typed in Ask or said in Drive:
- "swipe up", "scroll down", "go back", "home";
- "open Instagram", "click Pokémon GO" (by name on the screen, only when the name is unique);
- "open my camera"; "take a picture" (the camera, then the shutter); "take a picture of me" (the front camera, then
  the shutter);
- "call my mom":
  - Cyclone finds the contact (Mom, Mam, Mama…);
  - it shows "Calling Mam in 2 s — tap Stop to cancel";
  - it opens the dialer and presses Call;
  - with no match, or with two matches, it goes to the Mind with the names it found;
- timers, alarms, the flashlight, volume and play/pause.

**How Instant stays safe:**
- Every move goes through Cyclone's own action engine, with its approvals and access profile.
- It never types, sends, pays, deletes or posts. Such a button goes to the Mind, which asks you.
- It never acts on a screen with a password, code or card field, or in banking, payment and authenticator apps.
- Stop, or saying "no", ends it at once. A stop never turns into a mission.

**Moving up with the baton.** When Instant can't finish, the run goes to Flash or the Mind together with what was
already done and why it stopped. The next mode continues from the screen instead of starting over. Flash is a quick
Mind mission: with Fast mode on, it writes the whole plan and hands it to the Pilot straight away.

**Live voice (Drive):**
- **Quick commands skip the wait.** "Swipe up" or "open my camera" goes straight to the router, with no reading by
  the understanding model and no spoken "On it.".
- **Silent success.** A quick action that worked plays the done sound instead of words (switchable).
- **The mic stays open for 8 s** after a quick action (switchable):
  - "… and take a selfie" is the next command;
  - "no, open Spotify" replaces what you meant;
  - silence closes it quietly.
- A command that moves up to the Mind is confirmed like any task, and everything else works as before: questions,
  readbacks and approvals.

**Answers from the phone.** "What time is it", "what's the date" and "how much battery" are answered at once, without
a model.

## Not yet

- Streaming partial transcripts, early commit (~250 ms) and prewarming the target app while you speak. Live still
  waits for the end of speech (0.7 s) and the transcription.
- The haptic on silent success.
- Undoing the previous action on "no, …".
- A dedicated fast planner for Flash (Flash is a quick Mind mission for now).
- The mode chip and a single timeline on the run card, and search keywords for Speed.
- Command Center and Glass requests still start Mind missions directly.
- The Lab suite and measured timings.
- Recent apps as an Instant command. It goes up a mode, because Cyclone's action engine has no recent-apps action.

## Tests

- `ModesTest` (new, 21): the grammar with your examples, the router and its rules, the decision box wire formats,
  and Instant on a fake phone:
  - selfie with the shutter;
  - a call with one and with two matches, and a cancelled call;
  - taps that can't be undone;
  - sensitive screens;
  - direct actions.
- `VoiceLiveTest` (new, 11): the quick path with no model call and no words, silent success with the 8 s window, "…
  and take a selfie", silence closing quietly, promotion, failure, and Always Mind.
- Guards:
  - `test_modes_guard.py` (new): only the router starts missions for requests; Instant only moves through the agent
    environment; code decides risk; voice still only submits;
  - `test_voice_boundaries.py` pins the one place the mic reopens by itself.

## Physical acceptance

UNVERIFIED. Not yet seen on a phone:
- each Instant command;
- the selfie shutter in the stock camera app;
- the 2 s call window and the Call button;
- Live voice's sound and 8 s window in the car;
- the timings.
