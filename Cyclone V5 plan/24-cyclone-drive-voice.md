# 24 — Cyclone Drive: a voice assistant on top of the Mind (plan)

**Status:** D1 (Drive: talk) built in alpha.49, D2 (conversations) in alpha.50; the how-to is [plan 32](32-cyclone-drive-build.md). Written 2026-09-26. Moved after plan 26 (background that always works, alpha.42) and
parallel sessions (alpha.43): Drive is now alpha.44–45. Originally proposed: alpha.42 (Drive: talk) and alpha.43 (Drive:
conversations), after alpha.40 Planes (plan 25: background work and the switch pill, so Maps can stay on screen) and
alpha.41 Hands (plan 21: reliable typing of dictated replies).

**The owner's ask, in short:**
- A **Driver mode** toggle in Settings.
- When it is on, the Ask capsule becomes a **large AI button**:
  - hold it for one second, then drag it anywhere;
  - tap it once and the screen goes clearly into **AI mode**, like Siri.
- AI mode starts **listening**, ignores accidental taps and unclear requests without wasting calls, and turns the
  request into a clear goal with a fast model.
- It **confirms in one short line** so you don't have to watch it, then Cyclone works.
- It **tells you when it is done, or when it needs you**. Example: "Louella wrote in your DM 'I will be home late'.
  How would you like me to respond?"
- You answer out loud. It drafts in your voice using the recent conversation, reads the draft back and sends it
  after you say yes.
- Voice runs on the owner's **OpenRouter key**, using a cheap, fast, good-quality voice model.

---

## 1. How the big assistants solve this (public behaviour, not internals)

Every production voice assistant is the same pipeline of small, specialised stages, not one big model.

| Stage | What Siri / Google Assistant do (documented behaviour) | Why it matters for Cyclone |
|---|---|---|
| **Activation** | A button press or a wake word. Mis-activations are rejected early ("false trigger mitigation"). | We use the button only (no always-on microphone): no background listening, no battery cost, no privacy risk. |
| **Start sound and visual** | An earcon plus a full-screen edge glow the moment listening starts. | The user knows at once it is listening, without looking. |
| **End-of-speech detection** | On-device, from voice activity; closes the mic a moment after you stop talking. | This alone sets most of the perceived speed. |
| **Speech recognition** | Streaming, with an on-device model first and the cloud as backup. | We record on the phone, cut at end-of-speech and send one short clip. |
| **Understanding** | Intent plus the missing details (who, what); asks one follow-up question when something is missing. | A fast, small model with a strict output shape. Rules come before any model call. |
| **Confirmation style** | *Implicit* for ordinary actions ("Setting a timer for 10 minutes"); *explicit* for consequential ones ("Here's your message to Louella: '…'. Send it?"). | Maps exactly onto Cyclone's existing approval rules (GATE). |
| **Running the task** | The task runs; the assistant stays quiet while it works. | Cyclone's Mind is the executor; voice is only another surface for it. |
| **Proactive turns** | "Announce Notifications" (Siri) and driving mode (Google): incoming messages read out, with "Reply?". | This is the "Louella wrote…" moment. |
| **Reply dictation** | Dictate, the reply is read back, then "Send it?"; "change it" edits it. | The same loop, with the Mind drafting in your style. |
| **Speech output** | Streaming speech with a first sound in well under a second, short sentences, and the user can interrupt it. | We stream the audio and play it as it arrives. |
| **Driving rules** | A glanceable UI, large targets, no long reading; sensitive actions deferred until parked. | Our safety defaults (§6). |

The lesson: **speed comes from the pipeline, not the model.** You hear a sound in 0 ms. The mic closes about 0.7 s
after you stop. You hear a one-line confirmation about 2 s after you stop. Then the assistant stays silent until it
has something worth saying.

## 2. What Cyclone already has (evidence)

- **One engine for the work:**
  - The Mind (`mind/MindMissions.kt`) runs every Ask.
  - It asks its owner through `owner_ask` / `owner_fill` (`mind/PhoneMindToolbox.kt:855`).
- **Owner Moments** (`owner/OwnerMoments.kt`) already describe every "needs you" state:
  - the kinds are `QUESTION`, `VALUES`, `APPROVAL`, `SECRET` and `HANDOVER`;
  - every answer is a `TaskCommand` sent through **Task Kit** (`task/TaskCommands.kt`). Voice becomes one more
    surface, like the overlay card and the notification, and is guarded the same way (`test_mobile_task_kit.py`).
- **Approval boundaries:** GATE classes pay / send / delete / grant are already enforced on the phone.
- **Voice today is dictation only:**
  - `ai/OverlayChromeController.kt:818` uses Android `SpeechRecognizer` to fill the composer;
  - `RECORD_AUDIO` is already declared;
  - there is no speech output.
- **The overlay** is a `TYPE_ACCESSIBILITY_OVERLAY` window with the idle capsule, composer and task states
  (`ui/overlay/*`). The driver button and AI mode are new states of the same window, not a new app surface.
- **Incoming messages:** `CycloneNotificationListener` already sees notifications (the source for announcements).
- **One map:** grounded skills (alpha.39) make repeated voice asks faster, for example "reply to Louella" walks
  straight to the chat.

## 3. What OpenRouter offers for voice today (checked live on 2026-09-26)

Queried `GET /api/v1/models?output_modalities=speech|transcription` and read the TTS/STT docs.

**Speech out (text-to-speech):**
- Endpoint: `POST /api/v1/audio/speech`, OpenAI-compatible.
- Request: `model`, `input`, `voice`, `response_format` (`mp3` or `pcm`, 16-bit LE; the default is `pcm`), `speed`,
  and `provider.options` (for example a tone instruction).
- Response: a **raw audio byte stream**, which we can play as it arrives.
- 21 models, for example:
  - `google/gemini-3.8-flash-lite-tts` (the fast member of its family);
  - `hexgrad/kokoro-82m` (very cheap, 8 languages);
  - `deepgram/aura-2`;
  - `openai/gpt-4o-mini-tts-2025-12-15`;
  - `mistralai/voxtral-mini-tts-2603`;
  - `x-ai/grok-voice-tts-1.0`;
  - `minimax/speech-2.8-turbo`.

**Speech in (transcription):**
- Endpoint: `POST /api/v1/audio/transcriptions`.
- Request: base64 JSON (`input_audio.data`, `format` of `wav`/`mp3`/`ogg`/`webm`/…, optional `language`), or an
  OpenAI-style multipart upload.
- Response: JSON text. It is **not streaming**: one request per utterance.
- 24 models, for example:
  - `openai/whisper-large-v3-turbo` (cheap, fast);
  - `openai/gpt-4o-mini-transcribe` (higher accuracy);
  - `nvidia/nemotron-3.5-asr-streaming-multilingual-0.6b` (cheapest);
  - `qwen/qwen3-asr-flash-2026-02-10`;
  - `deepgram/nova-3`.

**Understanding (fast text):**
- Examples: `google/gemini-3.1-flash-lite` or `google/gemini-2.5-flash-lite`, with `structured_outputs` and very low
  cost.
- The owner's chosen Mind model is not used for this step; this step must be fast.

**All-in-one audio:**
- `openai/gpt-audio-mini` takes audio in and gives audio out in one chat call.
- Tempting, but slower to first sound, and it mixes understanding with speaking. **Rejected for v1** (see §4).

**Cost of one voice exchange:**
- A 4 s utterance plus a few short spoken lines stays in the order of **a tenth of a cent**.
- Prices change, so the Settings page reads them live from `/models` and shows an estimate per 100 requests.

## 4. Decisions (and what we reject)

1. **A pipeline, not a speech-to-speech model.** The stages are record → end-of-speech → transcribe → understand
   → confirm (TTS) → Mind → speak.
   - Each stage is swappable, measurable and can be tested without a phone.
   - An all-in-one audio model can't be split into "confirm fast" and "work carefully", and it would try to answer
     tasks itself instead of handing them to the Mind.
2. **Our own recording with on-device end-of-speech detection.** We use `AudioRecord` with the `VOICE_RECOGNITION`
   source (echo cancellation when the speaker is playing), 16 kHz mono, and a small energy/zero-crossing voice
   detector in Kotlin.
   - The mic closes after about 700 ms of trailing silence; a clip is at most 15 s.
   - Android `SpeechRecognizer` is kept as an **offline/private fallback** only. It owns the mic, so it can't share
     it with our detector, and it gives us no audio to send to OpenRouter.
3. **Rules first, model second.** The rules (§5.2) cost nothing:
   - no speech → close silently;
   - under 0.4 s of speech → treat as an accidental tap;
   - "cancel / never mind" → close.
   - Only real speech is transcribed, and only a real transcript is sent to the understand model.
4. **One small "understand" call with a strict output shape.** It returns
   `{kind: task|reply|answer|confirm|cancel|none|unclear, goal, ack, missing, confidence}`, where:
   - `goal` is the clean sentence the Mind receives;
   - `ack` is at most 8 words ("On it: replying to Louella");
   - `missing` becomes the single follow-up question when something essential is absent.
5. **Speech output streams; a local engine is the safety net.**
   - We request `pcm` and play it with `AudioTrack` as bytes arrive.
   - If the first byte takes longer than 1.2 s, or there is no network, the same sentence is spoken by Android's
     on-device `TextToSpeech`, so the confirmation always happens.
   - Earcons are local and instant.
6. **The Mind does the work; voice never acts.**
   - A task becomes an Ask exactly as if typed (`OverlayChromeRuntime.submitRequest`).
   - Answers to the Mind go through **Task Kit** (`TaskCommand.Reply` / `Approve` / `Decline`).
   - Voice has no tool of its own and never calls PhoneToolExecutor.
7. **Silence while working.**
   - After the confirmation, AI mode shrinks back to the button with a progress ring, so the screen (or Maps) is
     yours again.
   - Voice speaks only for: **done**, **needs you**, a single "still working on it" after 60 s, or failure.
8. **Borrow the screen, give it back.**
   - Today the Mind acts on the foreground display. In Drive it remembers the app you were in (for example Maps),
     does the task, and returns you to it.
   - Navigation audio keeps playing. Our speech ducks it through audio focus
     (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`).
   - With plan 25 (Planes), Drive missions run on a background display by default and Maps is never touched; screen
     borrowing is the fallback when background is unavailable.

## 5. The build

### 5.1 The button and AI mode (UI)

**Setting:**
- Settings → **Driver mode**, plus a Quick Settings tile.
- When on, the idle capsule is replaced by the **AI button**: an 84 dp orb with a soft animated gradient, sized for a
  quick glance and a big thumb.

**Gestures on the button:**
- **Hold 1 s:** haptic tick, the orb lifts, drag to any edge. It snaps to the nearest edge and the position is
  remembered per orientation.
- **Tap:** AI mode.

**AI mode (not shown while idle; it takes the screen only while a request is live):**
- The screen dims.
- An aurora glow runs along the screen edges.
- The orb moves to the lower centre and reacts to your voice level.
- Large captions show, top to bottom:
  - what you said (grey);
  - what Cyclone says (white).
- One big **Stop** button, and "Not now" when Cyclone asks.

**States:**

| State | Look |
|---|---|
| Listening | Orb breathes with your voice level; captions show "Listening…". |
| Thinking | Orb swirls. |
| Speaking | Orb pulses with the speech; you can interrupt by tapping it or starting to speak (§5.3). |
| Working | Collapsed back to the button with a progress ring; the screen is usable. |
| Needs you | Button glows, earcon, AI mode opens by itself and speaks. |
| Done | Short chime, one sentence, then back to the button. |

**Accessibility:**
- Every state has a content description.
- Captions honour the system font scale.
- Motion is reduced when the system asks for it.

### 5.2 The turn-taking engine (pure Kotlin, testable)

`voice/VoiceTurn.kt` is a state machine:
- **States:** `IDLE`, `LISTENING`, `TRANSCRIBING`, `UNDERSTANDING`, `ACKING`, `WORKING`, `ASKING`, `READBACK`,
  `DONE`, `CLOSED`.
- **Events:** speech start/stop, silence timeouts, transcript, understanding, TTS start/end, Owner Moment
  open/close, task end, and a tap on Stop.
- It has no Android types in it, so JVM tests cover every path.

**Rejection rules (no network):**
- No speech within 4 s of opening: soft earcon and close. **No calls made.**
- Speech shorter than 0.4 s or below the noise floor: close ("accidental tap").
- The transcript is empty or only filler ("uh", "hmm"): close.
- The transcript is a cancel word ("cancel", "never mind", "stop"): "Okay." and close.

**Model rules:**
- `none` → close quietly.
- `unclear` → **one** specific follow-up ("Who should I message?"). A second unclear answer gives "Okay, tap me when
  you're ready." and closes.
- `task` / `reply` → confirm and start the Mind.

**Length limits for spoken lines:**
- confirmation: 8 words or fewer;
- done: 20 words or fewer;
- questions: 15 words or fewer, plus up to 3 options.
- Anything longer is summarised; nothing long is ever read out while driving.

### 5.3 The audio stack (Android)

**`voice/VoiceCapture`:**
- `AudioRecord`, 16 kHz mono PCM, `VOICE_RECOGNITION` source.
- Echo and noise suppression when available (`AcousticEchoCanceler`, `NoiseSuppressor`).
- The voice detector and end-of-speech timing are unit-tested on synthetic signals.
- Bluetooth car audio: route the mic to the car through `AudioManager.setCommunicationDevice` (API 31+) when a
  car headset is connected, with the phone mic as fallback.

**`voice/SpeechToText`:**
- A port with two adapters: `OpenRouterStt` (default model `openai/whisper-large-v3-turbo`, set per owner) and
  `OnDeviceStt` (Android `SpeechRecognizer`, offline/private mode).
- The recorded clip is encoded as WAV in memory and never written to disk.

**`voice/SpeechOut`:**
- `OpenRouterTts` (default `google/gemini-3.8-flash-lite-tts`, voice set per owner, streaming `pcm` into
  `AudioTrack`).
- `LocalTts` (Android `TextToSpeech`) as fallback.
- Earcons from bundled short WAVs.
- Common lines ("Okay.", "Done.", "Still working on it.") are synthesised once per voice and cached as short files,
  so they play instantly.

**Interrupting while Cyclone speaks:** barge-in (interrupting while it speaks) — tap the orb, or start speaking,
detected with echo cancellation on. That stops the speech and starts listening.

**Audio focus:**
- Transient with ducking while speaking.
- Released right after, so music or navigation comes back.

**Keeping it alive:**
- A foreground service of type `microphone`, only while AI mode is listening.
- The Android mic privacy indicator shows as expected.

### 5.4 Understanding and confirmation

`voice/VoiceUnderstanding` makes one `chat/completions` call to the owner's chosen **fast model** (default
`google/gemini-3.1-flash-lite`) with `structured_outputs`.

**The prompt includes:**
- the transcript;
- the open Owner Moment, if any (so "yes", "no" or "tell her…" are read as an answer, not a new task);
- a few recent goals (for "do that again").

**It never includes:** screen content or messages. Drafting a reply is the Mind's job (§5.5), on the phone,
with the chat in view.

**Examples:**
- "uh reply to Louella that I'm fine with it and see her later" →
  `{kind: reply, goal: "Reply to Louella's latest message: I'm fine with it, see you later.", ack: "Replying to Louella."}`
- "text my mom" → `{kind: unclear, missing: "What should I tell your mom?"}`
- "yeah send it" with a readback open → `{kind: confirm}`

### 5.5 Conversations: questions, replies and read-back

**Owner Moments become voice turns** (`voice/VoiceMoments.kt`); the Owner Moment stays the one source of truth:

| Moment | What Cyclone says | Your answer becomes |
|---|---|---|
| QUESTION | The question (≤ 15 words), options read as "A, or B?" | `TaskCommand.Reply(text)` |
| VALUES | Each field asked in turn ("What time?") | `TaskCommand.Fill(values)` |
| APPROVAL: send | **Verbatim readback**: "I'll send Louella: '<draft>'. Send it?" | yes → `Approve`; "change it to…" → `Reply` with the edit, then a new readback; no → `Decline` |
| APPROVAL: pay / delete / grant | "That needs you to confirm on screen when you're stopped." | Nothing by voice; the card waits on screen |
| SECRET | "That needs a password; I'll wait until you're stopped." | Never by voice: passwords are never spoken or heard |
| HANDOVER | "Your turn on the phone." | Nothing by voice |

**The "Louella wrote…" reply, end to end:**
1. You say "reply to Louella". Understand returns `reply`, and Cyclone confirms: "Opening your chat with Louella."
2. The Mind opens the chat; the map makes this quick for a grounded skill or a known chat. It reads the last visible
   messages, then asks: `owner_ask` "Louella wrote 'I will be home late'. How should I answer?"
3. Voice speaks that question and listens. You say: "tell her that's alright, hope to see her soon for the movie".
   That goes back as `TaskCommand.Reply`.
4. The Mind drafts the message **in your style**. It uses the visible conversation (tone, language, nicknames such
   as "baby"), transiently: the conversation is never stored in Brain. It types the draft (this needs plan 21
   Hands), then presses Send. GATE catches the SEND and raises an APPROVAL moment with the exact text.
5. Voice reads back: "I'll send Louella: 'Hey baby, that's alright, hope to see you soon so we can watch that
   movie.' Send it?"
   - "yes" → Approve, the message is sent, "Sent."
   - "change the end to …" → the Mind edits, then a new readback.
   - "no" → Decline.

**Announcing incoming messages** (alpha.43, opt-in per app and per contact, driver mode only):
- When a notification arrives from an allowed chat app, Cyclone says: "Louella wrote: 'I will be home late.' Reply?"
- "yes" starts step 2 above, with the message as the starting point.
- Rules:
  - codes and one-time passwords are never read (the existing redaction);
  - messages longer than 25 words are summarised ("a long message about dinner");
  - group chats are announced by name only.

### 5.6 Safety defaults in driver mode

- The mic opens **only** when you tap. There is no wake word, and it never listens in the background.
- By voice, only **send** is allowed, and only after a verbatim readback and an explicit yes. Pay, delete,
  permission grants, passwords and handovers wait for you on screen.
- AI mode shows nothing long: captions of one or two lines in large type, and never the message history.
- A 60 s "still working" line, then silence. The only interruptions are done, needs you, or failed.
- Stop is always one tap: it cancels the voice turn **and** the task (`TaskCommand.Stop` through Task Kit).
- Nothing about audio is persisted:
  - recordings stay in memory and are dropped after the transcript;
  - transcripts are the goal text and appear in run diagnostics like a typed Ask;
  - spoken output passes through the existing secret redaction before synthesis.
- Cyclone is not a replacement for Android Auto. The Settings page says to use it hands-free, mounted, and to keep
  your eyes on the road.

### 5.7 Settings: Voice

**Settings:**
- Driver mode on/off.
- Button size.
- Voice picker with a preview: the model and voice from OpenRouter's live `/models?output_modalities=speech`
  list, with a short "Hi, I'm Cyclone" sample.
- Recognition: OpenRouter model, or **On-device (offline, private)**.
- Understanding: the fast model.
- Announce messages: per app and per contact.
- Language: auto, or fixed.

**Test voice** measures, on this phone:
- time to first sound;
- transcription time;
- understanding time;

and shows them next to the target budget (§7). This is how the owner picks a fast voice, rather than trusting a
model page.

## 6. Architecture (where every piece lives)

```
AI button tap
   │
   ▼
VoiceTurn (state machine) ── earcon (0 ms), AI mode overlay
   │
VoiceCapture: AudioRecord → voice detector → end-of-speech (≈0.7 s)
   │   no speech / blip / filler → close silently (no network)
   ▼
SpeechToText: OpenRouter /audio/transcriptions (or on-device)
   ▼
VoiceUnderstanding: fast model, strict shape {kind, goal, ack, missing}
   │   none → close · unclear → one question · confirm/answer → Task Kit
   ▼
SpeechOut: "Replying to Louella." (streamed pcm, local fallback)
   ▼
OverlayChromeRuntime.submitRequest(goal) ──► Cyclone Mind (PhoneToolExecutor, GATE, map, skills)
   │                                              │
   │   Owner Moment opens (QUESTION/APPROVAL…) ◄──┘
   ▼
VoiceMoments: speak the question / readback → listen → TaskCommand via Task Kit
   ▼
Task ends → "Sent." / "Done: timer set for 10 minutes." → back to the button (and back to Maps)
```

**New package `voice/`:**
- `VoiceTurn`
- `VoiceCapture`
- `VoiceActivity` (the voice detector)
- `SpeechToText` (+ `OpenRouterStt`, `OnDeviceStt`)
- `VoiceUnderstanding`
- `SpeechOut` (+ `OpenRouterTts`, `LocalTts`, `Earcons`)
- `VoiceMoments`
- `VoiceRedaction`
- `DriverMode` (the setting and screen-borrowing memory)

**Overlay:** `ui/overlay/DriverButton.kt` and `ui/overlay/AiModeOverlay.kt`, as new states in `OverlayChromeMachine`.

**Unchanged:** the Mind, Task Kit, Owner Moments and GATE.

**New guards:**
- `scripts/ci/tests/test_voice_boundaries.py`: `voice/` never imports PhoneToolExecutor or the phone tool registry,
  and never writes audio files.
- The Task Kit guard is extended to cover `voice/`.

## 7. Targets and how we measure them

| Measure | Target |
|---|---|
| Earcon after tap | < 100 ms |
| Mic closes after you stop | 0.7 s (adjustable 0.5–1.2 s) |
| Confirmation starts after you stop (p50 / p90) | 2.0 s / 3.0 s |
| Accidental opens that cost a network call | 0 |
| Unclear handled with one question | ≥ 90% of unclear cases |
| Readback matches the text actually sent | 100% (checked against the GATE approval text) |

**Lab "voice" suite:**
- Test utterances are generated once with OpenRouter TTS (several voices, with car and road noise mixed in) and
  saved as fixtures.
- They are run through capture → STT → understand on JVM or device.
- The suite measures latency percentiles, transcript accuracy, kind accuracy, and the false-accept rate on silence
  and noise clips.
- The Mind outcomes reuse the existing Lab missions ("reply to X", "set a timer", "navigate to…").

## 8. Release plan

| Release | Contents | Gate |
|---|---|---|
| **alpha.40 Planes** (plan 25) + **alpha.41 Hands** (plan 21) | Background work with the switch pill (Maps stays on screen); reliable typing into composers with a check that the text arrived. | Lab planes and Hands suites. Drive depends on both. |
| **alpha.42 Drive: talk** | The setting, driver button (hold-drag, tap), AI mode UI, recording and end-of-speech, OpenRouter STT, understanding, the confirmation line, OpenRouter streaming TTS with local fallback, done / failed lines, screen borrowing (return to the previous app), Voice settings with Test voice, the Lab voice suite, guards. | Confirmation p50 ≤ 2.0 s in Test voice on the Pixel 8; zero network on silent opens. |
| **alpha.43 Drive: conversations** | Owner Moments by voice (question, values, send readback, deferred approvals), reply drafting in the owner's style, "change it to…", message announcements (opt-in), Bluetooth car mic routing, interrupting while it speaks. | 100% readback-equals-sent; the Louella flow passes end to end on a phone. |
| later | Missions on a background virtual display (Maps never leaves the screen), a wake phrase (only if the owner asks), on-device STT model, Android Auto surface. | — |

## 9. Not in this plan (on purpose)

- **An always-listening wake word.** It costs battery and privacy, and needs a heavy on-device model. The tap is the
  wake word.
- **Voice cloning of the owner.** The endpoint supports it, but the spoken voice should sound like the assistant,
  not like the owner.
- **Reading full messages or feeds aloud while driving.** Messages are summarised.
- **Voice approvals for pay, delete, grant or passwords while in driver mode.**
