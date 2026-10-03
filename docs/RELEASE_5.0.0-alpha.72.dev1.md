# Cyclone V5 Alpha 72: the app redesign, and Drive hears you

Developer alpha for owner testing. It builds on Alpha 71 (the other agent's "Drive, polished": the Drive intro film, the Drive
button held and dragged anywhere, "Listening" said once) and includes it. New here:
- the Home redesign (R4) and the full in-app redesign (R5);
- a fix for Drive not hearing the owner, with Android's speech recognizer as the fallback.

Versions:
- **Mobile:** `5.0.0-alpha.72.dev1` (version code 217).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.72.dev1.exe` (runtime `5.0.0-alpha.68.dev1`, unchanged).
- **Glass:** `1.0.0-alpha.40` (unchanged).

Every page in the app now sits on the AI page's material: the Cyclone rain behind smoked glass. Navigation is a
darker glass that clearly floats above the content. Older phones get a Lite version that looks the same. The contract
is `docs/design/redesign/rounds/R5-app.md`.

## What changed

**One glass world.**
- The rain is drawn once for the whole app, so changing tabs never restarts it.
- The official scene (or your own video) plays only on the AI page. Other pages show the drawn rain and decode
  nothing.

**Home (R4).**
- Home is in the AI page's style without the video:
  - the burger top left and the Cyclone mark top right;
  - the greeting;
  - four quick actions that fill the Ask bar;
  - recent activity and your routines in smoked lists;
  - the smoked Ask bar with its dots.

**Navigation above content.**
- The tab bar, header buttons, the model selector, sheets, both Ask bars and back buttons are the darker "chrome"
  glass. It blurs more, bends more at the edge, has a brighter rim and casts a deeper shadow.
- Cards and lists stay the lighter content glass.

**Every page.**
- Profiles, Routines, the Marketplace, Brain and Settings follow through the shared parts:
  - smoked cards;
  - a neutral white-and-graphite scheme instead of teal;
  - white icons and rings;
  - glass back and status chips.
- Settings groups have their own tile colours.

**Visual quality: Auto, Full or Lite** (Settings › Appearance).
- **Full** is the design.
- **Lite** keeps the same look with less work:
  - one shared blur for all cards instead of one each;
  - no lens or shadow on cards (navigation keeps both);
  - the rain at 20 instead of 30 frames a second.
- **Auto** picks Lite for clearly older phones (low-RAM flag, under 4.5 GB, fewer than 6 cores, or under 6 GB
  without a performance class). It also switches to Lite if frames keep running slow.
- Settings shows what Auto chose and why. Only your choice is stored.

**Always faster.**
- The rain holds still while you scroll a list.
- The moving shine is its own small layer, so it never re-runs a blur.

**Unchanged.** The floating overlay stays teal and as it was. Other activities (PC Gateway settings, the overlay's
AI settings) keep the teal theme.

## Drive: it heard nothing

**What was wrong.** Found by reading the code; physical confirmation is pending.
- **The recording started too early.** Drive runs over other apps (Maps), so Cyclone is in the background. It
  started its microphone service and began recording in the same moment. Starting the service takes a moment, and
  Android silences a background app's recording until the service holds the microphone. Drive recorded only zeros.
  Its speech detector then saw "no speech" and closed after four seconds with a soft sound: nothing was ever sent.
- **The fallback could not be found.** Drive's "on-device speech to text" option uses Android's speech recognizer.
  The manifest never declared it, and since Android 11 an app cannot see the recognizer without that. The option
  would only ever answer "I didn't catch that."
- **The detector's floor was too high for a phone in a car mount.** It ignored anything quieter than 350 RMS. The
  VOICE_RECOGNITION source has no gain control, and a mounted phone hears normal speech at about 150–400.

**What changed.**
- **Drive waits for the microphone first.** It waits up to 0.8 s for the microphone service to hold the
  microphone, then records.
- **A silenced microphone is caught in 0.6 s.** Only exact zeros count; a real room always has a little noise.
  Android's speech recognizer (the standard voice to text) then listens in the same turn. It records in its own
  process, so it can hear you, and it stays in use for the rest of that drive. A failed OpenRouter transcription
  moves the next tap to it too.
- **The recognizer is declared** (`android.speech.RecognitionService` in the manifest's queries). The on-device
  recognizer goes first. If it cannot serve the language or its model is missing, the phone's standard recognizer
  takes over in the same turn.
- **The detector's floor is 120 RMS.** The noise-floor ratio and the speech check still keep road noise and hiss
  out; all detector tests pass.
- **When both ways hear only silence,** Cyclone says "I can't hear the microphone right now. Open Cyclone once, then
  tap me again."
- **Nothing is stored.** The recognizer's words go straight into the turn.

## Tests

- `AskQualityTest` (new): Auto's rules, your choice winning, the explanations, the slow-frame window and the scroll
  hold.
- `AppR5ContractTest` (new):
  - one world reused by Home and the AI page;
  - navigation on the chrome tier;
  - Lite's shared blur, with lens and shadow dropped on content only;
  - the frame watch and the setting;
  - only the choice stored;
  - no teal in the new parts;
  - the overlay untouched.
- `HomeR4ContractTest`: Home's order, no teal parts, the smoked Ask bar.
- Updated: `AskScreenR3ContractTest`, `CycloneTealMatrixTest`, `CycloneAppleUiContractTest`.
- `MicSilenceTest` (new): exact zeros mean a silenced microphone; a quiet real room and any real sound never do.
- `VoiceActivityTest`: quiet speech from a mounted phone is now heard; road noise, hiss and blips still are not.
- `VoiceTurnTest`: a silenced microphone is said as such.
- `DriveVoiceFallbackContractTest` (new):
  - recording waits for the microphone service;
  - a silenced or busy recording hands over to the recognizer;
  - the recognizer is declared, and the standard one backs up the on-device one;
  - nothing of the voice is kept.

## Physical acceptance

UNVERIFIED. Not yet seen on a phone:
- how every page reads on the rain;
- chrome against content;
- Lite against Full side by side;
- whether Auto's step-down triggers on a slow phone and never on a fast one;
- Drive hearing you over Maps, with the phone's microphone and with a car kit;
- the recognizer taking over when the microphone is silenced.
