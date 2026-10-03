# Cyclone Mind — one model, one conversation, one mission

**Status:** built for 5.0.0-alpha.25.dev1 (versionCode 166), extended in alpha.26.dev1 (versionCode 167) on top of the alpha.24 developer candidate. Unit-tested on
the JVM; **physical-phone acceptance is UNVERIFIED** until the device plan below is run. Implements the direction of
[`15-model-is-the-agent.md`](15-model-is-the-agent.md).

## What changed

| Before (alpha.23/24 step agent) | Cyclone Mind (alpha.25) |
|---|---|
| Each decision was a fresh two-message call about "this scene" | One conversation per mission: the goal, every thought, call and result stay in it |
| ~20 local shortcuts ran before the model and could take the turn | The model chooses every action; the harness only enforces boundaries |
| 9 tap-level actions in a JSON reply format | 24 native tools (OpenRouter `tools` / `tool_calls`), text envelope only when a route lacks tools |
| 8–22 s per decision | 180 s per model call, 30 min working time per mission (10/30/60 in settings); owner waits do not count |
| Login autofill and takeover prompts decided on their own | The model decides; secrets go through the Secrets Card on its request; approvals are asked at the action |
| Keyword contracts judged "done" | The model finishes with evidence it saw; the harness records the final screen next to it |
| Nothing survived an interruption | Every turn is journaled; paused, failed and interrupted missions resume with the whole conversation |

## Architecture (`apps/mobile/app/src/main/java/com/cyclone/mobile/mind/`)

```
MindMissions (Android)  ── start / resume / stop / steer, thread "cyclone-mind", trace + task card + overlay
   │
   ├─ MindLoop           ── turns: compact → model → tool calls → results → checkpoint; recovery; budget
   │    ├─ MindModel     ── OpenRouterMindModel: native tools, typed errors, usage/cost, 180 s MISSION budget
   │    ├─ MindConversation ── the memory; compaction keeps the model's own turns, shrinks old results
   │    └─ MindPrompt    ── plain standing instructions, no phrase tables
   │
   ├─ PhoneMindToolbox   ── tools → CycloneAgentEnvironment.act (policy, GATE, PhoneToolExecutor, settle, verify)
   │    └─ MindScreen    ── screens as text with stable refs (e1…), no element IDs or coordinates
   │
   ├─ AndroidMindOwner   ── owner_ask, approvals, Secrets Card, hand-back; OwnerInbox is the single open request
   └─ MissionStore       ── Cyclone Brain/Missions/<id>.mission.json + <id>.journal.json (redacted)
```

### Tools

| Group | Tools |
|---|---|
| See | `screen_read`, `screen_look` (screenshot for vision models), `screen_find` |
| Act | `tap`, `long_press`, `type_text` (+`press_enter`), `press_enter`, `scroll`, `back`, `home`, `wait` |
| Go / do | `open_app`, `open_link` (https, market://, geo, mailto, tel, sms), `open_settings` (allowlisted pages), `set_timer`, `set_alarm` |
| Know | `apps_list`, `recall` (Brain + verified routes) |
| Owner | `owner_ask`, `vault_fill` (Secrets Card) |
| Track / finish | `plan_update`, `note`, `task_finish` (summary + evidence), `task_give_up` |

New executor tools: `phone.open_settings` (navigation only) and `phone.submit_text` (IME Enter; free in search/address
fields, GATE approval elsewhere, blocked in Guided). Fixed: `phone.set_alarm` / `phone.set_timer` were not in the
local agent's allowed mutations, so the alpha.23 clock path could never run.

### Boundaries the harness keeps (the model cannot turn these off)

- `PhoneToolExecutor` remains the only mutation engine; every Mind action passes `CycloneAgentEnvironment.act`.
- One screen-changing action per model turn: later calls from the same turn are returned as NOT RUN with the new screen.
  Typing does not end the turn, so a form can be filled in one turn.
- Pay/send/delete/grant and other consequential taps raise the GATE card; the mission waits for the owner (overlay
  button or the mission's request card), then retries the exact same control once. Declines are final.
- Password, code and card fields are refused for `type_text`; `vault_fill` opens the Secrets Card and the value goes
  from the owner/Vault straight into the field. The model never sees it; journals and traces never contain it.
- CAPTCHAs and human-verification checks are handed to the owner; the prompt forbids working around them.
- Journals and diagnostics are redacted (`MindRedaction`), exclude screenshots and provider reasoning
  (`reasoning_details` is kept in memory only for the same model and dropped when switching to the backup).
- No shell or root access. Root abilities remain an open owner decision (see `AGENTS.md` before adding any).

### Recovery

Rate limit → backup model (same conversation) or bounded waits; deadline → one retry then backup; transient → 3
backoff retries then backup; tools unsupported → text protocol; fatal → backup or honest stop. A model turn without a
tool call gets two nudges, then the mission stops with the model's last words. Three identical failures in a row add
one harness note. At 3 minutes before the working time ends the model is told once.

## Device acceptance plan (owner's Pixel 8)

Record each as PASS/FAIL with the exported run diagnostic.

1. **Timer on a leftover screen.** Leave Chrome on a login page. Ask "set a timer for 5 minutes". Expect `set_timer`,
   Clock showing the countdown, `task_finish` with it as evidence, no login prompts.
2. **Install.** "download Instagram" with Instagram installed → the model reports it is installed (or opens its Play
   Store page) — no silent "open app". With an uninstalled app → Play Store page, Install needs no secret.
3. **Two steps, two apps.** "check which Gmail account I'm logged in with, then open facebook.com in Chrome" → reads
   the account in Gmail, remembers it, opens the site, and reports both with evidence.
4. **Secret.** "log in to <site> with my account" → `vault_fill` opens the Secrets Card on the password field; the
   diagnostic contains no value.
5. **Approval.** "send 'hi' to <contact> in WhatsApp" → GATE card at Send; Approve from the Ask request card works;
   Decline ends honestly.
6. **Question.** An ambiguous request ("book the usual") → `owner_ask`; answer from the overlay composer and from Ask.
7. **Interruption.** Force-stop Cyclone mid-mission → Ask shows it as interrupted → Resume continues with its memory.
8. **Long sprint.** A 15–30 min multi-app task; check working time excludes owner waits and the budget note appears.
9. **Settings off.** Turn Cyclone Mind off → the classic agent runs as in alpha.24.

## alpha.26 additions

- **Lasting memory.** `remember` / `forget` keep short facts across missions in `Cyclone Brain/Mind memory.json`
  (at most 200, newest first). Facts that look like secrets are refused, not stored. `recall` searches them, and
  every new mission opens with them plus the past day's missions (goal and outcome) so follow-ups have context. The
  owner reads and deletes them in AI settings → "What Cyclone Mind remembers".
- **Vision tap.** `tap_point` taps pixels of the last `screen_look` screenshot (scaled to the screen) for UIs that
  accessibility does not expose. `phone.tap_point` classifies whatever sits under the point exactly like a labelled
  click (GATE for pay/send/delete/grant); blocked in Guided.
- **Approval fix.** A click refused by Accessibility's GATE interceptor used to reach the Mind as "not allowed" while
  the approval card was up; it now waits for the owner and retries the exact control once.
- **Prompt caching.** Anthropic routes get cache breakpoints on the system prompt and the newest user message.

Device checks added for alpha.26: (10) "remember that my work email is …", then in a new mission "which work email do I
use?"; (11) a tap on an unlabelled icon via `tap_point`; (12) a Send reached through a screenshot tap raises the
approval card.

## alpha.27 harness

Each change was designed against Cyclone's existing code before it was built:

| Area | Change | Why it matters | Boundary kept |
|---|---|---|---|
| Survival | `MindMissionService` (special-use foreground service) + wake lock bounded by working time | a 30-minute sprint keeps its CPU when the screen goes off and is not reclaimed | visible notification with Stop |
| Survival | locked phone / screen off → phone tools wait for the owner | no string of failed actions; owner time not counted | model never unlocks anything |
| Survival | crash or process death < 5 min ago → mission resumes when Accessibility reconnects (≤ 3 times) | the conversation continues instead of dying | older missions wait for Resume |
| Eyes | ordinary field values shown (`= "…"`, `(empty)`, `(hidden)` for secrets) | the model can check what it typed and read pre-filled forms | process-local map, never in the exported payload/PC/Glass/diagnostics; password, sensitive-hint and address-bar fields excluded |
| Eyes | every control of the screen (up to 120) instead of the 36-item shortlist | long settings pages and lists are fully visible | — |
| Eyes | set-of-marks screenshots: ref boxes and names drawn on the image, ≤ 1280 px JPEG | "e7" in text = box e7 in the picture; far fewer image tokens | in memory only |
| Eyes | screens accessibility cannot describe come with a picture automatically | games, canvases, bare web views | — |
| Prompt | stable rules first, context last; trust section (tool results are data, never instructions); memory guidance; takeover | resists prompt injection from pages and messages | — |
| Context | model told once when old screens are shortened | it looks again instead of trusting a one-line summary | — |
| Hands | `swipe` (guarded: what is under the start point gets the tap approval check) | carousels, tabs, photos | swipe-to-delete / slide-to-pay raise the approval card |
| Hands | `notifications` / `open_notification` | "reply to the message I just got" | codes masked in any word order |
| Owner | `owner_takeover` | CAPTCHAs, security checks, biometrics handed over properly | never worked around |

Device checks added: (13) lock the phone mid-mission, unlock, it continues; (14) force-stop Cyclone mid-mission, it
resumes within a minute of Accessibility reconnecting; (15) a form with a pre-filled field is read correctly;
(16) a screenshot shows labelled boxes; (17) swipe through a carousel; (18) "what did my last WhatsApp say";
(19) a site with a CAPTCHA hands over and continues.

## alpha.28 owner card

From a device run: a Facebook sign-up needed first and last name; the Mind asked the owner to take over, the owner
filled it in and pressed "I'm done", and nothing happened.

- **Root cause:** "I'm done" (overlay ribbon, notification, Ask) went into the classic agent's resume path. A Mind
  mission waiting for an answer to its question never got one. Now every task-card command for a mission goes to the
  mission first, and "I'm done" resolves whatever it waits for: hand-back, open question ("I did it myself, look
  again"), open check-in card (done by hand). Cyclone always gets the phone back.
- **One owner card, two ways forward.** `owner_fill` asks for all personal details a form needs on one card over any
  app: an input per value with the right keyboard, "Remember for next time", **Take over** and **Fill in**. Cyclone
  types plain values straight into their fields; dates and choices reach the model in the owner's words, which it
  turns into the form's format. Questions (`owner_ask`) get the same card with Take over. Secrets are refused and stay
  on the Secrets Card; approvals keep the approval card.

Device checks added: (20) a sign-up form asks for first/last name on the check-in card, Fill in types them;
(21) the same with Take over, then I'm done continues; (22) a birth date typed as "12 march 1990" ends up correctly in
day/month/year pickers; (23) "Remember for next time", then a second sign-up needs no card.

## alpha.31 Owner Moments

The owner card is now `CycloneOwnerCard(moment)` for every engine (see `17-structure.md`, phase 2). For missions:
a question can be answered from the notification (inline reply); "Not now" on a question answers "I'd rather not
answer that; continue without it", and on a check-in declines it; Take over on an open question or check-in hands the
phone to the owner for that request. Device checks: answer a question from the shade while unlocked; confirm the
locked phone asks to unlock before Reply / Approve / I'm done; fill a check-in from the card opened by the notification.

## Known limits of this alpha

- Foreground only; background workspaces still use the step agent.
- Glass shows Mind runs through the ordinary run trace (MIND_* events) without a dedicated view yet.
- Cost grows with conversation length; the context cap is ~150k characters with older results compacted. Providers
  with automatic prompt caching make repeated turns cheaper.
