# 42 — Cyclone Modes and Live voice: Instant, Flash, Mind

**Written:** 2026-09-29, at 5.0.0-alpha.76 (the parallel Pilot). **Status:** the final build plan for the next major
alpha (5.0.0-alpha.77, "Cyclone Live"); nothing here is built yet.

**Owner's brief:**
- **Instant.** Obvious one-step or very simple requests happen immediately, with decision boxes and Cyclone's tools,
  without a smart model planning first:
  - "swipe up" swipes up;
  - "click Pokemon Go" taps it;
  - "take a picture of me" opens the camera and takes it;
  - "call my mom" opens the call and continues while it's obvious.
- **Promotion.** When Instant can't go on (typed input, a choice, a search) it promotes itself to Flash, and Flash to
  higher modes.
- **Smart mode routing.** Modes are chosen instantly and promoted or demoted as the run needs. It replaces how requests
  are handled today.
- **Live voice.** The same in Drive and voice: a ping-pong. "I say something and it instantly happens."
  - It doesn't talk much and doesn't start a Mind chat.
  - A dedicated decision call either answers or commits to a fast action, well under 10 seconds.
  - "Open my camera": about a second after the words end, the camera is open.

## 0. The answer in short

**Three modes, one router, one baton:**

| Mode | What decides | First move after the request | Plans? | Types text? | Irreversible? |
|---|---|---|---|---|---|
| **Instant** | Local grammar, then decision boxes (typed choices only) | **0.05–0.6 s** | No | No | Never (a call gets a 2 s cancel window) |
| **Flash** | A fast model writes a short plan; the parallel Pilot (plan 41) walks it | ~1–2 s | Short plan | Only the plan's text | Only when planned, with approval |
| **Mind** | The One Mind (plan 16), with the Pilot for routine stretches | several s | Full plan | Yes | With approval |

**Live** is the voice front end of the same router:
- it recognises speech while you talk;
- it commits the moment a complete command is heard;
- it answers with a sound and the action, not with words.

It speaks only when it needs you (a question, "which Mom?") or when you asked for an answer.

## 1. What exists and is reused (verified in code)

| Piece | Where | Today | In this plan |
|---|---|---|---|
| Every phone move with approvals, secret rules and settle | `PhoneToolExecutor` (`phone.swipe`, `click`, `open_app`, `launch_intent`, `direct_timer`, `direct_alarm`, `direct_contacts_find`, `open_settings`, …) | Used by the Mind | Used by every mode |
| App names → packages, intent landings | `fastpath/InstalledAppLexicon`, `ImplicitAppRouter`, `FastPathLanding` | Classic agent | Instant "open X", camera, dial |
| A zero-model command parser | `voice/VoiceIntents` (timers and alarms) | Drive skips the model for these | Grown into the Instant grammar |
| The rapid runner, decision board, bumps, parallel look-ahead, risk by code | `mind/pilot/Pilot.kt` (alpha.76) | Fast mode | Flash's executor; Instant's check |
| The fast decider (fast model or decision endpoint) | `mind/pilot/FastMode.kt` | Fast mode | Every decision box |
| Drive's turn machine, earcons, TTS, barge-in, the car mic | `voice/VoiceTurn`, `VoiceSession`, `Earcons`, `SpeechOut`, `CarMic` | See below | Live |

**Drive's path today, and where the time goes:**
1. The end of speech waits for **700 ms of silence** (`VoiceActivity.Tuning.endSilenceMs`, 500–1,200 in Settings).
2. The whole recording is sent to **cloud speech-to-text** (`OpenRouterVoice.transcribe`). Android's on-device
   recognizer (`SpeechToText`) is the fallback, and its **partial results are ignored** (`onPartialResults = Unit`).
3. **Understanding:** timers and alarms skip the model (`VoiceIntents`). Everything else is a fast-model call
   (`OpenRouterVoice.understand`).
4. **A task:** Drive **speaks an acknowledgement** ("On it.", `VoiceCopy.ack`) and submits the goal to a full Mind
   mission (`VoiceEffect.Submit`).

So "open my camera" today costs:
- 0.7 s of silence;
- ~0.5–1.5 s of cloud transcription;
- ~0.5–1 s of understanding;
- a spoken ack;
- a Mind mission that plans before it opens the camera.

That adds up to several seconds, and Cyclone talks while doing it.

## 2. The decision box (the one primitive)

A decision box is **one fast call that answers several typed questions at once**. Each question has a fixed answer set
built on the phone, and each answer comes with a confidence. It never writes free text.

- **Inputs:**
  - the request;
  - a compact screen: app, title, ≤30 labels, never secret fields or values;
  - candidate lists prepared on the phone: on-screen labels, matching installed apps, matching contacts;
  - a marked screenshot only when the labels are weak (plan 41 §8).
- **Transport:** `PilotDecider` generalised to `DecisionBox`, over OpenRouter's decision endpoint (JEV; OpenAI
  Decisions when available) or a fast model with a strict schema. One wire format, with the tolerant reader built in
  `PilotWire`.
- **Why multi-question:** one call answers everything the next move needs, so a move costs one round trip, or none.

## 3. The router

It runs on every request: the Ask bar, Live voice, Drive, Glass and the Command Center. It replaces the direct
`MindMissions.start` in `OverlayChromeRuntime.runAiRequest`, and Drive's `VoiceEffect.Submit`.

**Stage 0: local grammar (no model, about 5 ms).** It matches:
- gestures: "swipe up/down/left/right", "scroll up/down", "go back", "home", "recent apps";
- "open <app>", with the app in the installed lexicon;
- "tap/click/press <label>", with a unique fuzzy match to a label on the screen;
- timers and alarms (`VoiceIntents`);
- "open the camera", "take a picture", "take a selfie" / "picture of me";
- "call <name>", with a unique contact match;
- flashlight, volume, play/pause and next;
- setting pages.

It covers English and Dutch. A unique, sure match goes straight to Instant with its arguments, and nothing leaves the
phone.

**Stage 1: Board 0 (one decision box, about 0.2–0.5 s),** only when the grammar is unsure:

| Question | Answers |
|---|---|
| `route` | `instant`, `flash`, `mind`, `answer` (a short spoken answer), `ignore` (not for Cyclone) |
| `intent` | the Instant catalogue (§4), or `none` |
| `target` | the prepared candidates, or `none` |

**The rules** (code, over the answers):
- **Instant** only when the intent and target (when needed) are sure, there is no second clause ("and then"), and
  there is no free text to write.
- **Mind** is forced when the request composes a message, names more than one app, or involves money, deleting or
  accounts.
- **Answer** is for questions with short factual answers (§5.4).
- **Flash** otherwise.
- **Unsure routes one mode up, never down.**

## 4. Instant mode

**The catalogue.** Each intent is a typed tool call, with its arguments picked from candidates, never generated:

| Intent | Argument source | Tool | Follow-up moves (max 3) |
|---|---|---|---|
| swipe / scroll (up, down, left, right) | grammar | `phone.swipe` / `phone.scroll` | — |
| back, home, recents | — | `phone.back` / `phone.home` | — |
| tap a named thing ("click Pokemon Go") | on-screen label (unique or boxed) | `phone.click` | — |
| open app ("open my camera") | installed lexicon | `phone.open_app` | — |
| take a photo / selfie | camera intent (front camera extra for "of me") | `phone.launch_intent` | Box B: shutter → tap |
| call someone | contacts (`direct_contacts_find`) | dial intent | Box B: the right number; 2 s cancel window |
| timer, alarm | `VoiceIntents` | `phone.direct_timer` / `direct_alarm` | — |
| flashlight, volume, media | grammar | system actions | — |
| open a setting page | `PhoneSettingsPages` | `phone.open_settings` | — |

**The loop:**
1. Act.
2. Settle (Fast Path fingerprint; plan 41's live screen ledger when built).
3. Box B:
   - **Questions:** `done` (yes/no), `next` (a screen control, or `none`), `promote` (yes/no plus a reason).
   - **Decision:** done → finish. A sure next move within the 3-move limit → act. Anything else → promote.

**What Instant never does:**
- type or search;
- compose a message;
- send, pay, delete or post: an irreversible label (`Pilot.irreversible`) promotes to the Mind, which asks your
  approval;
- act on sensitive screens or inside banking, payment and authenticator apps (opening them is fine);
- take more than 3 moves.

## 5. Live: the voice ping-pong

### 5.1 The pipeline

```
mic ─► on-device streaming recognition (partials every ~100 ms)
        │
        ├─ each partial ─► Stage 0 grammar ──complete & unique?──► COMMIT early (don't wait for 700 ms)
        │                         └─ prefix of a command? ─► PREWARM (resolve app / contact / camera intent)
        │
        └─ end of speech (adaptive) ─► final text ─► Board 0 (one decision call) ─► route
                                                                     │
      Instant ◄──────────────────────────────────────────────────────┤  earcon + action, no speech
      Answer  ◄──────────────────────────────────────────────────────┤  one short spoken line
      Flash / Mind ◄─────────────────────────────────────────────────┘  one short spoken line, then work
```

### 5.2 The four speed moves

1. **Streaming partials.** Turn on `EXTRA_PARTIAL_RESULTS` in `SpeechToText` (on-device first, as today) and run the
   grammar on every partial. Cloud transcription stays as the fallback for accents or languages the on-device
   recognizer can't serve. It's also used when the owner chooses it.
2. **Adaptive end of speech.** When the partial is already a complete, unique Instant command ("open my camera"),
   commit after **~250 ms** of silence instead of 700 ms. Sentences keep the normal 700 ms, so nothing is cut off.
3. **Prewarm while you're still talking.**
   - "open my cam…" resolves the camera package;
   - "call m…" loads contact matches;
   - "take a pic…" prepares the camera intent.
   The commit then only fires the tool.
4. **Silent success.** Instant says nothing: a short earcon (the existing tick) and a haptic, then the action. The
   voice speaks only:
   - when Cyclone needs you ("Which Mom: Mam or Mom Work?");
   - when you asked a question (the answer, one line);
   - when a request goes to Flash or the Mind ("Working on it.", once, and only if it will take more than ~3 s).
   The "On it." acknowledgement disappears for Instant.

### 5.3 Ping-pong

- **Stay open.** After an Instant action the mic stays open for the next command for **8 s**, with no button press
  or wake word: "open camera" → "take a picture" → "swipe left" → "share it". The existing "Listening" state shows it,
  and a soft earcon marks when it closes.
- **The mic never closes on a quick action.** An early commit fires the action but keeps recognising, with no gap
  between "commit" and "listening again", so the rest of a sentence is never lost:
  - **Words within ~1.5 s of the commit are the same sentence.** "open my camera … and take a picture of me" becomes
    one request. The camera is already open (a done move in the baton), and "take a picture of me" continues from
    there in Instant.
  - **A correction undoes what can be undone.** "open my camera … no, the gallery" presses Back when the early action
    opened the wrong app, then does the corrected command. It never undoes anything irreversible, which Instant never
    does anyway.
  - **A continuation Instant can't do promotes with the baton:** "open my camera … and send it to Lou" → the Mind,
    starting from the open camera.
  - **Silence after the action** is the normal ping-pong window below.
- **Barge-in.** Speaking over Cyclone stops it and routes the new words, as Drive already does for speech.
- **One run for a chain.** Commands in one ping-pong window share one run card with one timeline, so the Mind sees
  them as context if a later command promotes.

### 5.4 Answer or act, within 10 seconds

Board 0's `route = answer` covers short questions ("what time is it", "how much battery", "what's on my calendar
today", "who texted me").

- **Local facts first:** time, battery, the calendar provider, notifications (already read by Cyclone). Spoken in
  one line, no model.
- **Otherwise:** one fast-model call with a ≤20-word answer limit.
- **A hard budget of 10 s** from the end of speech. Past it, the request becomes a mission with one spoken line, and
  the answer comes later as an announcement (plan 32, D3).

### 5.5 Worked voice runs (targets, to be measured)

| You say | What happens | End of speech → action |
|---|---|---|
| "swipe up" | partial matches → commit at ~250 ms silence → `phone.swipe(up)` + tick | **≈ 0.3–0.4 s** |
| "open my camera" | "open my cam…" prewarms the camera package → commit → `phone.open_app` + tick | **≈ 0.4 s** to launch; camera visible **≈ 1 s** |
| "take a picture of me" | commit → front-camera intent → Box B finds the shutter (screenshot) → tap → Box B: done → tick | **≈ 2–3 s** to the photo |
| "click Pokemon Go" | partial matches the on-screen label, unique → `phone.click` + tick | **≈ 0.4 s** |
| "call my mom" | contacts prewarmed on "call m…" → one match → "Calling Mam — say stop" → dial after 2 s | **≈ 2.5 s** to ringing |
| "call mom" (two matches) | promote to Flash → "Mam or Mom Work?" → you: "Mam" → dial | one question, then **≈ 1 s** |
| "what time is it" | `answer` → local clock → "It's 22:40." | **≈ 0.6 s** to speech |
| "message Lou I'm late" | Board 0: `mind` (a message to compose) → "Working on it." → Mind with the Pilot; the send is read back for approval as today (plan 32 D2) | the Mind's pace |

### 5.6 Live safety

- **Unchanged:**
  - Drive's readback-and-approve for sends (exact text);
  - approvals for pay, delete, permissions and sign-in;
  - Task Kit for stop, take over and approve (AGENTS.md).
- **Voice commands never type secrets.** Screens with password, code or card fields stop Instant.
- **"Stop", "cancel" and "wacht" always win,** including during the call window, at earcon speed.
- **Nothing recorded is kept** beyond what Drive already keeps: transcripts in memory, redacted in diagnostics.

## 6. Flash mode

**How it works:**
- A fast model writes a short plan, up to 8 steps, in the Pilot's step format, from the request and the baton.
- The parallel Pilot walks it, with the smart model's look-ahead and bumps, exactly as built in alpha.76.

**It promotes to the Mind when:**
- the fast planner is unsure;
- a bump returns `return`;
- the plan needs composition or judgement;
- two failures happen on one step.

## 7. The Mind

The Mind is unchanged, with the Pilot for routine stretches. **It starts from the baton:** its opening message says
what the lower mode did and why it stopped, then the screen now. It never starts from scratch.

## 8. The baton

`RunBaton` holds:
- the goal, and the mode history (`instant → flash`);
- the moves done, with their record lines;
- the screen now;
- the reason for promotion;
- the candidates already found (the two Moms);
- the Live chain's earlier commands;
- the elapsed time.

**A promotion never replays a done move** (a guarded rule). The run card shows the mode chip ("⚡ Instant 0.4 s" →
"Flash" → "Mind") on one timeline, and diagnostics say which mode decided each move.

## 9. Reliability rules (the lessons, applied)

- **Goal first** (plans 14 and 15): the request travels with every box. Stage 0 matches your words against the
  screen, never the screen alone.
- **Unsure goes up, never down.** A promotion costs a second; a wrong tap costs trust.
- **Code decides risk:** irreversible labels, sensitive screens, kept-off apps, calls behind a cancel window.
  Approvals are unchanged.
- **One screen-changing move per decision**, through `PhoneToolExecutor`, with settle and re-observe.
- **Transport success isn't task success.** Box B confirms `done` from the screen, or from the direct tool's result.
- **Watch first, per intent** (plan 41 §9). Each Instant intent turns on by default only after its Lab numbers are
  in; until then it runs one mode up, with Instant watching.
- **Speech is not a guess.** Early commit happens only on a complete, unique grammar match. Anything else waits for
  the normal end of speech and Board 0.

## 10. Settings

**Settings → Model & intelligence → Speed.** It replaces the alpha.76 "Fast mode" card.
- **Speed:**
  - **Auto** (the router; the default once the Lab passes);
  - **Instant for commands** (the default until then);
  - **Always Mind**.
- **Instant calls:**
  - call after a 2 s cancel window (default);
  - always ask;
  - never instant.
- **The fast model / decision model, How sure before acting, Screenshots when needed, and Smart model checks ahead**
  stay as in alpha.76, shared by Flash and Instant.

**Settings → Drive / Voice → Live:**
- **Answer with sounds, not words, for quick actions** (on);
- **Keep listening after a quick action** (8 s; Off, 5 s, 8 s, 15 s);
- **Commit fast on clear commands** (on);
- **On-device recognition first** (on).

## 11. The build: 5.0.0-alpha.77 "Cyclone Live"

Each milestone is a code-only push checked by CI; there is one release at the end. B1 of plan 39 moves to alpha.78.

| # | Milestone | Delivers | Tests and guards |
|---|---|---|---|
| **M1** | Decision box core | `mind/modes/DecisionBox.kt`: multi-question typed choices, candidates, confidences; `PilotDecider` becomes a one-question use; tolerant wire for both routes | `DecisionBoxTest` |
| **M2** | The grammar | `mind/modes/InstantGrammar.kt` (from `VoiceIntents`): gestures, open app, tap a label (fuzzy, unique), camera/photo/selfie, call a name, timers/alarms, flashlight, volume, media, setting pages; English and Dutch; a `prefix()` for prewarm | `InstantGrammarTest`, with your examples as fixtures |
| **M3** | The router | `mind/modes/ModeRouter.kt`: Stage 0 → Board 0 (`route`, `intent`, `target`) → rules. Every entry goes through it (Ask, Drive, voice, Glass, Command Center) | `ModeRouterTest`; guard: no entry calls `MindMissions.start` directly |
| **M4** | Instant engine | `mind/modes/InstantRun.kt`: the catalogue as typed tool calls; act → settle → Box B; 3-move limit; the call cancel window; the never-list | `InstantRunTest` (fake phone): swipe, tap by name, selfie with shutter, call with one and two matches, promote on typing |
| **M5** | Live voice | the mic stays open through an early commit (continuation within ~1.5 s joins the sentence; "no, …" corrections undo what can be undone); `SpeechToText` streaming partials; the grammar on partials; adaptive end of speech (~250 ms on complete commands); prewarm; silent success (earcon + haptic, no "On it."); keep listening 8 s; the answer path with local facts and a 10 s budget; `VoiceTurn` routes through the router instead of `Submit` | `LiveTurnTest` (pure turn machine with scripted partials and timings, including "open my camera … and take a picture of me" and "… no, the gallery"); `VoiceTimings` targets as tests |
| **M6** | Flash mode | `mind/modes/FlashRun.kt`: fast planner (strict JSON plan) + the parallel Pilot + the Mind's side channel | `FlashRunTest` |
| **M7** | Baton and promotion | `RunBaton`; Instant → Flash → Mind; the Mind's opening from the baton; Live chains on one run; mid-run commands as steer or queue (plan 38) | `BatonTest`, `PromotionTest`; guard: no replay of a done move |
| **M8** | Surfaces | The mode chip and one timeline on the run card; the Speed and Live cards; search keywords; Drive's panel shows "Listening" during the ping-pong window | Compose contract tests; `test_modes_guard.py` |
| **M9** | Lab and release | An Instant + Live suite: your examples plus 30 more, voice clips for the grammar and early commit; measured end-of-speech → action p50/p90; promotion accuracy; wrong-move rate; alpha.77 release notes | Default rule: an intent turns on by default only at ≥ 98% agreement when sure |

**Order:**
- M1 → M4: Instant end to end from the Ask bar.
- M5: Live voice on top.
- M6 → M7: Flash and the promotion ladder.
- M8 → M9: surfaces and proof.

**Targets** (end of speech or send → first move, p50; measured in M9, not promised):

| Request | Target |
|---|---|
| Grammar command by voice | ≤ 0.4 s |
| Typed grammar command | ≤ 0.15 s |
| Board 0 Instant | ≤ 0.9 s |
| Answer from local facts | ≤ 0.7 s to speech |
| Flash first move | ≤ 2.5 s |
| Selfie end to end | ≤ 3 s |
| "Call my mom" to ringing | ≤ 3 s, including the 2 s window |

## 12. Risks

- **A confident wrong tap from a fuzzy match or a misheard word.**
  - Early commit needs a complete, unique grammar match. Otherwise it waits for the normal end of speech and Board 0.
  - Box B checks the result.
  - "Stop" wins at once.
- **Calls are consequential:** the cancel window, a setting, and never on an ambiguous match.
- **Camera apps differ** (the shutter is an icon): a screenshot for Box B, and the camera intent's own capture mode
  where available.
- **The on-device recognizer's quality and languages vary:** cloud transcription stays as the fallback, and early
  commit applies only to the recognizer's final-looking partials.
- **The decision API contract is unknown** (plan 41 §1): the same port works with JEV or a fast model.
- **Replacing the entry path touches every surface:** one router function with the Task Kit rules guarded, and
  "Always Mind" as a one-tap way back.

## 13. Owner decisions

1. **Calls:** a 2 s cancel window (recommended), always ask, or never instant?
2. **"Picture of me":** front camera and the shutter tapped at once (recommended), or a 3 s self-timer?
3. **Default Speed:** "Instant for commands" until the Lab passes, then Auto (recommended)?
4. **Keep listening after a quick action:** 8 s (recommended), or another length?
5. **Numbering:** alpha.77 "Cyclone Live", shifting B1 to alpha.78 (recommended)?

## 14. As built (alpha.77)

**Owner decisions (§13) taken with the recommended defaults, to revisit:**
- calls use the 2 s cancel window;
- "picture of me" uses the front camera and the shutter at once;
- the default is "Instant for commands";
- the mic keeps listening for 8 s;
- this build is alpha.77, and B1 moves to alpha.78.

**Built:**
- **M1:** `mind/modes/DecisionBox.kt` and `OpenRouterDecisionBox`. It has the chat route with a strict schema and
  the decision route (text only), on Fast mode's model, with a 5 s deadline.
- **M2:** `InstantGrammar` (parse, `quick`, `prefix`). `VoiceIntents` now exposes `timerSeconds` and `alarmTime`.
- **M3:** `ModeRouter`:
  - Stage 0: the grammar and local answers;
  - the rules;
  - Board 0, in Auto only.

  `CycloneModes.handle` is the one entry for Ask and Drive: `OverlayChromeRuntime.runAiRequest`, including simple
  app launches when the Mind is on.
- **M4:**
  - `InstantRun` and `AndroidInstantHands`, which move through `CycloneAgentEnvironment`.
  - A camera branch in `phone.launch_intent` (`action: camera`, `front`), and the direct tools `phone.direct_flashlight`,
    `phone.direct_volume` and `phone.direct_media`.
  - Calls use `tel:` and then the Call button, found by name or by the decision box.
- **M5, in part:**
  - `VoiceEffect.Quick`, which goes to the router with no understanding call and no ack;
  - `VoiceEvent.QuickDone`;
  - silent success with the done earcon;
  - `KeepListening(8 s)`, with continuation and "no, …" replacement (`VoiceQuick`).
- **M6:** a Flash run is a Mind mission with a quick-run note (`MindPrompt.FLASH` / `FLASH_WITH_PILOT`).
- **M7:** the baton reaches the Mind's opening message through `MindMissions.start(handover, flash)`. A stop never
  promotes.
- **M8, in part:** the Speed card with the Live switches, and `test_modes_guard.py`.

**Not built yet:** streaming partials, early commit, prewarm, the haptic, undo on "no", `FlashRun` with its own
planner, the mode chip and timeline, search keywords, routing for Command Center and Glass, and M9 (the Lab).

## 15. As built (alpha.78): voice keeps the screen

The owner's first Live test found four problems. Their causes and fixes:
- **A spoken request opened the Ask panel.** `OverlayChromeRuntime.submitRequest(driving = true)` now goes straight to
  the modes router: no analysis state, no composer. A mission voice started shows as the minimized island.
- **Slow quick actions:**
  - One screen read per move (`AndroidInstantHands.current()`); only "tap …" reads the screen before routing
    (`InstantGrammar.needsScreen`).
  - Drive's voice windows follow `OverlayGesturePassthrough`, so Cyclone's own gestures never land on them.
- **Stop:** voice's Stop cancels a quick action at once (`OverlayChromeRuntime.stopVoiceRequest` →
  `CycloneModes.cancel`). The voice panel with Stop stays up while it runs.
- **"I didn't catch that" loop:** `voice/VoiceEars` replaces the one-way switch to Android's recognizer. An ear that
  fails hands over in the same turn, and a broken recognizer hands back to the recording.
- **Logs:**
  - `voice/VoiceRunLog`, written through `VoiceRunSink` by `ui/overlay/VoiceTraceSink`, puts every voice turn in the
    run history with its stage timings.
  - `mind/modes/ModesTrace` does the same for every Instant run.
- **Decisions:** the decision box moved to `mind/decide` (JEV, text only; OpenAI Decisions frozen; plan 41 §15).

