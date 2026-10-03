# 32 — Cyclone Drive: build handoff (all Drive alphas)

**For:** the agent that builds Drive mode end to end.
**Written:** 2026-09-27, after alpha.47 (web-only PC).
**Product plan:** [24 — Cyclone Drive](24-cyclone-drive-voice.md). It still holds the *why* and the UX: read §1–§5 and
§7–§9 there. This file is the *how, now*: it corrects what changed since plan 24 was written, maps each piece to
today's code, and splits the work into releases you can ship one by one.

---

## 0. Before you write any code

1. **Read, in order:**
   - `AGENTS.md`: invariants, ownership, the fast release lane (Android + Glass + Windows package from one push);
   - plan 24 (the product);
   - `docs/design/CYCLONE_TILT_GLASS.md`: every Drive surface is Tilt Glass;
   - plans 25, 26 and 28 (planes and background);
   - plan 29 §3 (direct first);
   - plan 30 (setup cards).
2. **Check the numbering.** Read `release/version.toml`. Drive's releases take the next free alphas. The owner decides
   whether parallel sessions (plan 26 §6) ships first; Drive does not need it. Below, releases are called **D1–D4**.
   Give each the next alpha number when you ship it.
3. **Re-check OpenRouter live.** Model names and prices in plan 24 §3 are from 2026-09-26.
   - Query `GET https://openrouter.ai/api/v1/models?output_modalities=speech` and `…=transcription`.
   - Read the current `/audio/speech` and `/audio/transcriptions` docs.
   - Never hard-code a model you have not seen in that list. Defaults live in one place, and Settings reads the live list.
4. **Do not push while a publish is running** (Mobile CI cancels in-progress runs per branch).

## 1. What changed since plan 24 (read this, it changes the design)

| Plan 24 said | Now true in the code | What Drive does instead |
|---|---|---|
| Voice is dictation only (`ai/OverlayChromeController.kt` `SpeechRecognizer`) | Still the only speech input. The overlay is the **Tilt Glass** stack (plan 27): card, island, notification-only, idle oval. **`VoiceOrbButton`** and **`VoicePress`** exist in `ui/overlay/glass/GlassKit.kt`: tap to talk, hold to push-to-talk, a 400 ms double-press guard and an 800 ms minimum listen. | The **AI button is the voice orb**, grown to the driver size. Reuse `VoicePress`, the orb drawing and the `GlassLight` tilt light. Do not draw a second orb or a Siri-style aurora in a new style: AI mode is the Tilt Glass panel plus the orb (see §3). |
| Drive may borrow the owner's screen | **Planes built** (plans 25, 26, 28): `runtime/plane/MissionPlanes`, the background workspace via Shizuku, app leases, Automatic start. The **Background Check** (`runtime/plane/BackgroundCheck.kt`) says whether background works on this phone. | In driver mode a mission starts in the **background** when `MissionPlanes.capability` is ready and the check passed, so Maps stays on screen. Borrowing the screen (and returning to the previous app) is the fallback. |
| The Louella reply opens the chat | **Tier 0 reply** (plan 26): `phone.reply_notification` answers from the notification with no screen; every reply is a *send* and needs approval. | Announced messages are answered **from the notification** first. Opening the chat is the fallback, when there is no notification or the reply needs context. |
| Timers and alarms go through the clock app | **Direct first** (plan 29): `phone.direct_timer`, `direct_alarm`, `direct_calendar_*`, `direct_contacts_find`, and the Mind tools `set_timer`, `set_alarm`, `calendar_add`, `calendar_find`, `contact_find`. | These are ideal while driving: no screen, instant. The understand prompt names them so ordinary asks never touch a screen. |
| The Voice permission is new | `RECORD_AUDIO` is declared, has a setup row, and has a **setup card** (`setup/SetupCards.kt`, `VOICE`). | Add a **Driver mode** setup card (plan 30 pattern), shown only when the owner turns Driver mode on. |
| The foreground service type is microphone | Not declared yet. `test_mobile_permission_architecture.py` requires every permission to be an infrastructure exemption or a setup row. | Declare `FOREGROUND_SERVICE_MICROPHONE` with an infrastructure exemption (the mic only while AI mode listens), and update the guard. |
| Task commands | `task/TaskKit.kt` `TaskCommand`: `Stop`, `Approve`, `Decline`, `Reply(text)`, `Fill(values, remember)`, `MoveToBackground`, and so on. `TaskCommands.send(context, taskId, command)` is the only door. | Voice sends only these, through `TaskCommands.send`. Never call `MindMissions`, `MindTaskController` or an engine directly (`test_mobile_task_kit.py`). |
| Owner Moments | `owner/OwnerMoments.kt` (`MomentKind`: `QUESTION`, `VALUES`, `APPROVAL`, `SECRET`, `HANDOVER`) and `OwnerMomentsRuntime.kt`. | `VoiceMoments` reads the same open moment; it is the source of truth for what voice may say and accept. |
| OpenRouter key | `ai/OpenRouterSecretStore` (read in `ai/OpenRouterQuickAgent.kt`), plus the model catalogue in `ai/OpenRouterCatalog*.kt`. | Voice uses the same key store and the same catalogue client. Never log the key or put it in diagnostics. |
| Asks start from the overlay | `ui/overlay/OverlayChromeRuntime.submitRequest(text)`. | A voice task calls exactly this with the clean goal; it is then a normal Ask. |

## 2. Hard boundaries (CI-guarded; keep them)

- **Voice never acts.** Nothing in `voice/` imports `PhoneToolExecutor`, `PhoneToolRegistry`, `CycloneAccessibilityService`
  actions, `MindMissions` or any engine. Voice turns speech into a goal (`submitRequest`) or into a `TaskCommand`
  (`TaskCommands.send`), and nothing else. Add `scripts/ci/tests/test_voice_boundaries.py` and extend the Task Kit guard
  to `voice/`.
- **By voice, only *send* can be approved,** and only after a **verbatim readback** plus an explicit yes. Readback
  text must equal the approval text GATE shows; a test compares them.
  - Pay, delete, grant, sign-in, passwords and handovers are never approved by voice: "That needs you on screen when
    you're stopped."
  - A `SECRET` moment is never spoken or heard.
- **The mic opens only on a tap** (or hold). No wake word, no background listening. The microphone foreground service
  runs only while AI mode is listening.
- **No audio persists.** Clips stay in memory as WAV bytes, are dropped after the transcript, and are never written
  to disk (the guard checks for file writes in `voice/`).
  - Transcripts are handled like typed Asks.
  - Everything spoken passes the existing secret redaction first.
- **Silent opens cost nothing:**
  - no speech, a blip, filler or a cancel word → close with **zero network calls**;
  - the Lab suite counts network calls on silence.
- **No voice cloning of the owner. No full messages read aloud while driving** (summaries over 25 words).

## 3. Design (Tilt Glass)

Follow `docs/design/CYCLONE_TILT_GLASS.md`. In short:
- **The AI button:**
  - it is the voice orb (`VoiceOrbButton` visuals) at 84 dp on the idle-bubble glass;
  - hold 1 s then drag, snapping to an edge, with the position remembered per orientation;
  - tap starts listening (reuse `VoicePress`).
- **AI mode** is a Tilt Glass panel (`tiltGlass(30.dp)`) rising from the bottom:
  - the orb sits in it and breathes with the voice level while listening;
  - it swirls while thinking and pulses while speaking, reusing the orb's existing animations (principle 8: motion
    only for real state);
  - captions sit on veil pills: what you said in `GlassMuted`, what Cyclone says in `GlassInk`, 20–23 sp, at most
    2 lines;
  - there is one big **Stop** (`GlassCapsuleButton` primary), plus **Not now** when Cyclone asks;
  - the screen behind dims only while a request is live.
- **While working,** AI mode collapses to the island or the button with a progress ring, and the screen (Maps) is the
  owner's again.
- **Needs you:** the button glows `GlassWarm`, an earcon plays, and AI mode rises by itself.
- **Copy** lives in pure objects (like `OverlayGlassCopy` or `SetupCopy`), tested apart from composables.
- **Accessibility:** content descriptions on every control, font scale honoured, and a still light when Android
  animations are off.

## 4. Package layout (new)

```
apps/mobile/.../voice/
  VoiceTurn.kt           pure state machine (plan 24 §5.2): IDLE→LISTENING→TRANSCRIBING→UNDERSTANDING→ACKING→WORKING
                         →ASKING/READBACK→DONE/CLOSED; events only, no Android types
  VoiceRules.kt          pure: rejection rules (no speech 4 s, <0.4 s, filler, cancel words), spoken-length limits
  VoiceActivity.kt       pure: energy + zero-crossing voice detector, end-of-speech after ~700 ms silence, 15 s cap
  VoiceCapture.kt        AudioRecord 16 kHz mono VOICE_RECOGNITION, AEC/NS when available, BT car mic (API 31+)
  SpeechToText.kt        port + OpenRouterStt (base64 WAV JSON) + OnDeviceStt (SpeechRecognizer, offline mode)
  VoiceUnderstanding.kt  one chat/completions call, structured output {kind, goal, ack, missing, confidence}
  SpeechOut.kt           port + OpenRouterTts (streamed pcm → AudioTrack) + LocalTts fallback (first byte >1.2 s)
  Earcons.kt             bundled short sounds; cached common lines per voice
  VoiceMoments.kt        Owner Moment → spoken turn → TaskCommand (Reply/Fill/Approve/Decline/Stop)
  VoiceRedaction.kt      reuse the existing secret redaction before anything is spoken
  DriverMode.kt          setting, QS tile state, previous-app memory for screen borrowing, plane preference
  VoiceService.kt        foreground service type microphone, only while listening
ui/overlay/DriverButton.kt, ui/overlay/AiModeOverlay.kt    new states in OverlayChromeMachine
```

Keep `VoiceTurn`, `VoiceRules`, `VoiceActivity`, the understanding parser and `VoiceMoments`' mapping **pure** and
JVM-tested. Android glue stays thin.

## 5. The releases

Each release is shippable alone. It passes the full validation in `AGENTS.md`, adds its guard tests, writes
`docs/RELEASE_<version>.md` with honest limits, and states physical Pixel status as UNVERIFIED until the owner tests it.

### D1 — Drive: talk (the foundation)

**Goal:** tap, say a task, hear a short confirmation within about 2 s, and the Mind does it.

**Build:**
1. Settings → **Driver mode** (+ a Quick Settings tile). It swaps the idle bubble for the AI button (§3). The setup
   card for Driver mode explains the mic in one line.
2. `VoiceTurn`, `VoiceRules` and `VoiceActivity` (pure, with full tests on synthetic signals: silence, blip, speech,
   trailing silence, the 15 s cap).
3. `VoiceCapture` + `VoiceService` (the microphone foreground service, only while listening), and the manifest and
   permission-guard update.
4. `SpeechToText`: OpenRouter, with the default model chosen from the live list (a whisper-class turbo model), plus the
   on-device fallback. WAV is built in memory.
5. `VoiceUnderstanding`, with a fast model chosen from the live catalogue.
   - The prompt names the direct tools (timers, alarms, calendar, contacts) and the open Owner Moment (none yet in D1).
   - Strict output parsing: a malformed answer counts as `unclear`, never a guessed action.
6. `SpeechOut`: the OpenRouter TTS stream into `AudioTrack`, `LocalTts` when the first byte takes more than 1.2 s or
   there is no network, earcons, audio focus with ducking, released right after.
7. A task → `OverlayChromeRuntime.submitRequest(goal)`. AI mode collapses while working.
   - **Done / failed** lines are spoken from the task's end state (≤ 20 words).
   - One "still working on it" after 60 s.
   - **Stop** → `TaskCommand.Stop`.
8. Driver-mode plane: background when the capability is ready and the Background Check passed; else borrow the screen
   and return to the previous app when the task ends.
9. Settings → **Voice**:
   - recognition model;
   - understanding model;
   - voice and model picker from the live list, with a preview;
   - language;
   - button size;
   - **Test voice**, which measures time to first sound, transcription and understanding, shown against the targets.
10. Guards: `test_voice_boundaries.py` (no engine imports, no file writes, Task Kit only, no key in logs) and the
    permission guard.

**Exit:**
- Test voice on the Pixel 8 shows the confirmation starting ≤ 2.0 s p50 / 3.0 s p90 after you stop.
- Silent, blip and noise opens make **zero** network calls (counted in tests).
- "Set a timer for 10 minutes" and "add lunch with Sam tomorrow at 12" run direct, with no screen.

### D2 — Drive: conversations

**Goal:** Cyclone asks and you answer by voice; replies are drafted in your style, read back verbatim, and sent only on
yes.

**Build:**
1. `VoiceMoments`, following plan 24 §5.5:
   - `QUESTION` → spoken (≤ 15 words, options as "A, or B?") → `Reply(text)`;
   - `VALUES` → one field at a time → `Fill(values, remember = false)`;
   - `APPROVAL` for a *send* → verbatim readback → yes `Approve` / no `Decline` / "change it to…" `Reply(edit)`, then a
     new readback;
   - other approvals, `SECRET` and `HANDOVER` → the fixed "on screen when you're stopped" line.
2. Understanding reads the open moment, so "yes", "send it", "no" and "tell her…" are answers, not new tasks.
3. **Readback equals sent.** The readback text is taken from the approval moment's exact text (the same string GATE
   shows), never re-worded by a model. A test fails if they can differ.
4. **Drafting in the owner's style:** a Mind prompt rule for reply drafting. It uses the visible conversation
   (tone, language, nicknames) transiently, and never stores it in Brain or learning stores.
5. **The Louella flow** from plan 24 §5.5, end to end, with the tier 0 notification reply first and the open-the-chat
   route as the fallback.

**Exit:**
- 100% readback-equals-sent in tests and in a Lab mission.
- The Louella flow passes on a phone, from both the notification route and the chat route.
- A second unclear answer closes politely.

### D3 — Drive: in the car

**Goal:** it works hands-free in a real car: announcements, the car mic, and interrupting.

**Build:**
1. **Message announcements** (opt-in per app and per contact, driver mode only), from `CycloneNotificationListener`:
   - "Louella wrote: '…'. Reply?";
   - codes and one-time passwords are never read (existing redaction);
   - more than 25 words are summarised; groups are announced by name only;
   - "yes" starts the D2 reply flow from that notification.
2. **Bluetooth car mic** routing (`AudioManager.setCommunicationDevice`, API 31+), with the phone mic as fallback,
   and car audio for speech.
3. **Barge-in:** tap the orb, or start speaking while Cyclone talks (echo cancellation on). Speech stops and listening
   starts.
4. Cached common lines per voice ("Okay.", "Done.", "Sent.", "Still working on it.") so they play instantly.
5. **The Lab voice suite:**
   - fixtures generated once with OpenRouter TTS (several voices, car and road noise mixed in);
   - run through capture → STT → understand;
   - reports latency percentiles, transcript accuracy, kind accuracy and false accepts on silence and noise;
   - the Mind outcomes reuse existing Lab missions.

**Built in alpha.53** (after "Drive: faster", alpha.52):
- Announcements, opt-in per app and per sender. They are said only when Drive is idle; the mic still opens only on a
  tap (the orb glows for a minute). "yes"/"reply" starts the reply, "tell her …" gives the answer, "no" lets it go.
  Groups are named only, with no reply offer; codes and anything redacted are never announced; the understanding
  model hears who wrote, never the message.
- Car microphone: `CarMic` routes to a Bluetooth car kit or headset while listening (`setCommunicationDevice`), brought
  up while the listen earcon plays, and falls back to the phone mic after 1.5 s. Speech stays on car media audio.
- Barge-in by tap (built in D1) now keeps the readback rule: a readback cut short is read again in full before a yes
  counts (`readbackHeard`, CI-guarded).
- Cached lines were built in alpha.52.
- **Not built yet:** barge-in by talking over Cyclone (echo on car audio must be measured first), the Lab voice suite,
  and JEV promotion (it needs the owner's car-test numbers).

**Exit:**
- The Lab voice suite meets the plan 24 §7 targets on the Pixel 8.
- An announced message is answered end to end over car Bluetooth; the car audio result is stated honestly.

### D4 — later (only if the owner asks)

- An on-device speech model for private, offline driving.
- An Android Auto surface.
- A wake phrase, only on the owner's explicit request (plan 24 §9 rejects an always-on wake word).
- Missions that never leave a background display for more apps, as the Background Check passes on more phones.

## 6. Tests and gates you must run for every Drive release

- `cd apps/mobile && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`.
- `python -m pytest scripts/ci/tests -q`: all guards, including the new voice and permission guards.
- `python scripts/ci/release_versions.py --check` and `python scripts/ci/mobile_product_guard.py`.
- Glass or PC changes follow their own lines in `AGENTS.md`. Drive should not need any.
- **Physical:** the owner runs Test voice and one real Drive task on the Pixel 8 in the car. Until then, write
  UNVERIFIED.

## 7. How to work with this owner (what has worked)

- Short replies: what changed, what is verified, what is not. No hype.
- Ship each release through the fast lane and **verify the publish**: the signer `e78c6e0b…`, the versionCode, and
  the release assets and manifest. Then say so.
- Honest limits in every release note; physical status never overstated.
- Plain language in all user-facing copy (the setup-card and Tilt Glass style).
- The owner decides scope. When a choice changes what gets built (for example the order of parallel sessions vs
  Drive), ask once with a recommendation.
- Commit trailers exactly as the session reminder gives them, and no model names in commits or PRs.
- Do not push a second commit to the dev branch while a publish runs.
