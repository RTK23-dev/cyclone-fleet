# 21 — Mind Hands and Desk: reliable text delivery and a working scratchpad

**Status:** Phase 0 + Phase 1 (Hands) built in **alpha.41** (2026-09-26); the real ChatGPT capture (0.1) and the
on-phone Hands suite results are still owed. Desk (Phase 2) follows Cyclone Drive. Final build plan written 2026-09-25. Supersedes the "Mind Desk" plan (`BUILD_PLAN_MIND_DESK_2026-09-25.md`) where
they differ.
**Evidence used:**
- the failed run `ai-5b3ed992-7ae7-4e62-8aed-24d236a8c4c1` ("please prompt this current [chat] on ChatGPT to find
  better examples"): 11.6 min, 82 decisions, nothing sent;
- the current code: `PhoneMindToolbox.kt`, `CycloneAgentEnvironment.kt`, `CurrentTargetRevalidation.kt` and
  `PhoneToolExecutor.kt`.

The owner's ask has two parts:
1. **Hands:** Cyclone must reliably get text it wrote into any app: ChatGPT's composer, Notes, WhatsApp, Gmail, a
   search box.
2. **Desk:** the thinking session gets a real scratchpad. It can write, keep, reread and edit files during a mission,
   then copy or paste them anywhere.

Part 1 is what broke this run. Part 2 is what makes Cyclone useful for writing work. Build them in that order.

---

## 1. Checking the previous analysis claim by claim

| Claim | Verdict | Evidence |
|---|---|---|
| "The run did not fail at thinking; Mind wrote a good follow-up." | **True** | Turn 2 `type_text` carries a clear, specific 400-character prompt. |
| "It failed because compose and deliver are the same step; the draft only lived inside `type_text`." | **False** | The draft was never lost. Turn 10 re-sent the identical text from the model's own context. Losing the draft was not the problem. |
| "The composer node kept going stale; long strings through set-text are flaky on Compose/WebView." | **Misleading / unproven** | Every failure returned in under 1 s, before any text reached the field. `tap e8` failed the same way, and a tap involves no text at all. The refusal is Cyclone's own fail-closed **target revalidation** (below), not Android rejecting set-text. |
| "v0: `clipboard_set` + `paste_text` unblocks ChatGPT-class composers." | **Would not have fixed this run** | Pasting still needs to target and focus the same composer through the same revalidation gate, which refused even a tap. A paste tool on top of a broken targeting layer fails identically. |
| "~11 minutes of retries." | **Unknown** | The record keeps 13 of 82 decisions (`eventsTruncated: true`). We cannot see what the other 69 did. That is an observability bug in its own right. |
| "Clipboard exists in the executor but Mind cannot use it." | **True** | `phone.set_clipboard` / `get_clipboard` exist in `PhoneToolExecutor`; Mind has no clipboard tool. |
| "`note` is a lie; it stores nothing." | **Half true** | `note` returns "Noted." and writes nothing to disk. The text survives in the conversation, because tool results are shortened but the model's own calls are kept. It does not survive a summary compaction or a resume from a summary. Worth fixing, not a root cause. |
| "Paste ≠ send; GATE unchanged; secrets never on the clipboard." | **True, keep** | These are correct invariants. |

**Bottom line:** the earlier plan builds a nice scratchpad on top of a targeting layer that could not tap the ChatGPT
reply box. Fix the hands first, then the desk. Also, the choice between type and paste belongs to the executor, not to
the model.

---

## 2. What actually happened

1. **Turn 1: `screen_read`.** ChatGPT is shown, with a long answer and the "Reply to ChatGPT" composer.
2. **Turn 2: `type_text e8`.** The executor refuses it: `STALE_OBSERVATION` or `TARGET_NOT_FOUND`, which the toolbox
   turns into *"the element moved or disappeared"*.
3. **Turns 3 and 9: `tap e8` / `tap e16`.** The same refusal. A plain tap fails, so text delivery is not the issue.
4. **Turns 5, 7 and 13: `tap_point`.** These succeed but report *"did not visibly change"*. The likely explanation is
   that the tap focused the composer and the keyboard opened, but the keyboard is not part of the app's
   accessibility tree, so Cyclone's screen fingerprint did not change. The next step then went back to `e8` and hit
   the gate again.
5. **The rest (events truncated).** The model looped. Finally it gave the owner the text to send by hand, as plain
   text, not on the clipboard.

**Why the gate refuses.** The Mind's environment is built with `revalidateTargets = true`
(`CycloneAgentEnvironment(context, …)`). Before every ref action it re-captures the screen and calls
`CurrentTargetRevalidation.resolve`, which fails closed when:
- the target's label is blank or redacted → `AMBIGUOUS`;
- no element with the **same role, label and semanticName** exists → `DISAPPEARED`. An editable field's label is
  its hint or current text, which changes as it gains focus or content;
- more than one logical match exists → `AMBIGUOUS`. ChatGPT exposes several nodes with the same long label; turn 12's
  `screen_find` shows e13, e15 and e18 all as "What you're describing…";
- **any other clickable element overlaps the target** → `AMBIGUOUS`. A composer `EditText` inside a clickable input
  container always overlaps its parent. `sameLogicalControl` only excuses nested nodes with the *same label and
  role*, which a container and its field do not share.

Every one of these statuses is reported to the model as "moved or disappeared". The real status
(`Target revalidation: AMBIGUOUS`) is in `safeMessage`, and `describe()` throws it away. So the model kept retrying a
refusal it could never pass.

The most likely single cause is the **overlap-with-container rule**, possibly together with the label rule on the
editable field. This is a hypothesis; Phase 0 confirms it from a real captured tree before anyone changes the gate.

**The run record also failed us:** `stepCount: 1`, `toolCalls: 0`, `cause.kind: "unknown"`, 13 of 82 events kept.
Glass could not show what went wrong, which is how a wrong diagnosis got written.

---

## 3. Principles for the build

1. **The executor owns delivery.** The model says *put this text in that field*. `PhoneToolExecutor` picks the
   method and verifies by reading the field back:
   1. set-text (`ACTION_SET_TEXT`);
   2. paste (clipboard + `ACTION_PASTE`);
   3. a focused-input fallback.

   The model never has to know which one worked.
2. **Fail closed, but say exactly why.** Target safety stays; each refusal names its reason and the next thing to
   try. "Moved or disappeared" is only used when that is what happened.
3. **Compose once, deliver many.** Long or important text is written to the mission desk once and delivered by
   reference, so a retry re-sends identical bytes and costs no new tokens.
4. **A failed automation ends in a one-tap handoff, not an essay.** The draft goes on the clipboard, and the owner
   card says *"Tap the reply box and paste."*
5. **Structure and privacy invariants unchanged.**
   - No secrets on the desk or clipboard (`vault_fill` only).
   - Desk files are mission-scoped, never in Brain or learning stores, and deleted with the mission.
   - Paste is not send: GATE still decides send, post, pay and delete.
   - No code execution or shell for the model. "Running internals" means managing its own text files, nothing else.

---

## 4. The build

### Phase 0: evidence (1 day, before any gate change)

| # | Work | Where |
|---|---|---|
| 0.1 | On the Pixel, in the same ChatGPT chat, capture the observation and raw tree around the composer: before tap, after `tap_point` and with the keyboard open. Save it as a redacted **test fixture**. | gateway `debug.snapshot`; fixture in `app/src/test/resources/fixtures/chatgpt-composer/` |
| 0.2 | Surface the real refusal. `describe()` maps each `TargetDrift` to honest text with the next step. `AMBIGUOUS`: *"Several controls overlap e8; tap it with tap_point, then type_text with focused=true."* `DISAPPEARED`: *"e8 is gone; read the screen."* | `PhoneMindToolbox.describe`, `CycloneAgentEnvironment` (carry `report.status`) |
| 0.3 | Fix the Mind run record: one step per `MIND_ACTION`, tool call and failure counters, cause from the last failed tool, and full events up to a byte cap, not a fixed count. | `RunInsight` / runs adapter for Mind missions |

**Exit:** a unit test that feeds the captured ChatGPT tree to `CurrentTargetRevalidation` reproduces the refusal and
names the rule that fired.

### Phase 1: Hands (alpha.41, after Planes — plan 25; the release that makes typing just work)

| # | Work | Where |
|---|---|---|
| 1.1 | **Revalidation fixes, driven by the fixture.** (a) A clickable element that is an ancestor or descendant (nested raw path) of an **editable** target is the same logical control, not a competing target. (b) An editable target matches on role + resourceId (or same raw path) even when its label (hint or current text) changed. (c) Every other rule stays fail-closed, and there are tests for each distinct-sibling case. | `CurrentTargetRevalidation.kt` + tests |
| 1.2 | **`type_text` with `focused: true` and no ref.** It types into the node with input focus (`findFocus(FOCUS_INPUT)`), which must be editable, visible, not a password field and in the current package. Tapping the box, then typing, is what a person does, and it works whenever refs do not. | `PhoneMindToolbox.typeText`, executor `phone.type` target `{"focused": true}` |
| 1.3 | **The delivery ladder inside `phone.type`.** Set-text, then read back. If the field does not contain the value (normalised compare), fall back to clipboard + `ACTION_PASTE`, then read back. Then restore the owner's previous clipboard, or clear ours. The result reports `method: set_text / paste`, `verified: true / false`. Values over 4 000 characters go straight to paste. Password or secret fields are refused before any of this, as today. | `PhoneTypeEngine` / `PhoneToolExecutor` |
| 1.4 | **Focus is a screen change.** After `tap_point` or `tap`, the result says *"The text box has focus (keyboard open)"* when an editable gained input focus, so the model does not tap it again. | `PhoneMindToolbox.finishAction` (check focused editable) |
| 1.5 | **Loop breaker.** Two identical failures on the same target inject one harness line: *"This control refuses (reason). Try: tap_point on it, then type_text focused=true."* Four consecutive failed attempts to put text into a field → an **owner moment**: the draft goes on the clipboard and the card says *"I wrote this but could not enter it. It's copied: tap the reply box and paste."* with **Done** / **Try again**. | `MindLoop` harness, Owner Moments (existing card family), Task Kit for the buttons |
| 1.6 | Prompt rule (one line): *"To write into a box: tap it, then type_text; if the ref is refused, use focused=true. Don't retype a long message, reuse your draft."* | `MindPrompt.kt` |

**Exit (measured in Cyclone Lab, not just unit tests):**
- The exact failing mission (a ChatGPT follow-up in an existing chat) reaches "text in the composer" in ≥ 19 of 20
  runs, with a median under 25 s. Sending is the model's next step, without a GATE card, because it is a chat reply
  the owner asked for.
- Also ≥ 95%: a 2 000-character note in Google Keep, the Gmail compose body, WhatsApp draft text (not sent; GATE
  stays), and the Chrome search box.
- No regression on the Lab core suite. No secret values in clipboard telemetry. The Owner-moment handoff fires on a
  forced-failure fixture.

### Alpha.41 build plan (2026-09-26, from the code as it is now)

Reading the code again before building found a second wall behind the first. **Even when targeting passes, a Mind
`type_text` outside Chrome's address bar is refused.** `CycloneAgentEnvironment.act` strips `user_authorized` from
`phone.type` and only grants it back through `TaskTypingAuthorization` (Chrome URL bars for "open X" / "search X"
goals); `PhoneTypeEngine.decide` then rejects the type as `POLICY_DENIED`, which the toolbox shows as "Not allowed".
So ChatGPT, Keep, Gmail and WhatsApp could never be typed into by the Mind, whatever the gate did. Second, the type
verification accepts "the text did not change" as success (`unchangedAsLabel`), so a set-text that silently does
nothing is reported as typed.

| # | Work | Where | Test |
|---|---|---|---|
| H1 | **Owner missions may type.** A Mind mission is the owner's own request; its environment grants typing into an ordinary editable field (enabled, not password, not a sensitive hint). The engine keeps its own sensitive-field refusal; sending stays with GATE. The step agent keeps `TaskTypingAuthorization`. | `CycloneAgentEnvironment` (`ownerMission` flag), `MindMissions` | env test: Mind env authorizes a composer, never a password field; step env unchanged |
| H2 | **Honest refusals** (0.2). The revalidation status travels in the failure code; the toolbox names it with the next step to try. | `CycloneAgentEnvironment`, `PhoneMindToolbox.describe` | toolbox test per status |
| H3 | **Revalidation fixes** (1.1), from a synthetic ChatGPT-composer fixture shaped like the failed run (editable inside a clickable container, hint label that changes on focus, repeated long labels): nested clickable around an editable target is the same control; an editable matches by role + resource id or raw path when its label changed. Distinct siblings stay ambiguous. | `CurrentTargetRevalidation` | fixture test reproduces AMBIGUOUS / DISAPPEARED before, MATCHED after; sibling cases stay closed |
| H4 | **Delivery ladder** (1.3): set-text, strict read-back; if the field does not hold the text, paste (selection over the field + `ACTION_PASTE`, clip marked sensitive), read back; then restore the owner's clip or clear ours. Over 4 000 characters goes straight to paste (limit raised to 20 000 for paste). The result says `method` and `textVerified`; an unverified type is reported to the model as unverified, never as typed. Secret fields: unchanged. | `PhoneTypeEngine`, `CycloneAccessibilityService` | engine tests with a host where set-text no-ops, where paste works, where neither works |
| H5 | **Focused typing** (1.2): `type_text` with `focused: true` types into the one editable with input focus (visible, not password, not sensitive). | toolbox, env (`phone.type` without id when `focused`), `PhoneTypeEngine.decide` | engine + toolbox tests |
| H6 | **Focus is a change** (1.4): after a tap, "The text box has focus (keyboard open)" when an editable gained focus. | toolbox | toolbox test |
| H7 | **Loop breaker + handoff** (1.5): two identical failures on one field add one harness line with the next step; four failed attempts to put text in → the draft is copied to the clipboard and an owner question says "I wrote this but could not enter it. It's copied: tap the box and paste." with Done / Try again. | toolbox (`TypingTracker`), owner port, prompt | tracker tests |
| H8 | **Prompt** (1.6) and **run record** (0.3): Mind steps are `MIND_ACTION`/`MIND_RESULT` steps in RunInsight with counts and a cause from the last failed tool. | `MindPrompt`, `RunInsight`, `AgentRunDiagnosticV39` | RunInsight test on a Mind trace |
| H9 | **Lab Hands suite** (Phase 3): text-delivery missions (Keep note, Gmail draft body, Chrome search, Settings search, Play search, Messages draft, WhatsApp draft, ChatGPT prompt) checked by the typed token on screen; nothing is sent. | gateway `lab/missions.py` | gateway tests |

Physical capture of the real ChatGPT tree (0.1) stays an owner step with `debug.snapshot`; the synthetic fixture
is replaced by the real one when it arrives. Release gate: the Hands suite on the Pixel (≥ 95%) is reported with
the release as UNVERIFIED until run.

### Phase 2: Desk (after Cyclone Drive — plan 24)

A mission-scoped scratchpad the Mind can manage like files.

**Model tools.**

Each tool is one call, verified, and returns the file list afterwards:

| Tool | What it does |
|---|---|
| `desk_write` | `{name, text, mode: replace/append}`. Names are `a-z0-9-_.`, ≤ 40 characters, `.md` / `.txt`. |
| `desk_read` | `{name, from?, to?}` returns the text, with line numbers for editing. |
| `desk_edit` | `{name, find, replace}` does an exact, single-occurrence replace, so the Mind can revise a draft without rewriting it. |
| `desk_list` | Lists the desk files. |
| `desk_delete` | Deletes one file. |

Delivery takes a desk file wherever it takes text:

| Tool | What it does |
|---|---|
| `type_text` | Accepts `{ref or focused, draft: "prompt.md"}` in place of `text`. |
| `copy_text` | `{draft or text}` puts it on the phone clipboard for the owner or for pasting elsewhere. It is cleared at mission end if the clipboard still holds our clip. |
| `share_text` | `{draft, app?}` shares it through Android (`phone.share`). This is the most robust route into apps that accept shared text: Notes, Keep, Gmail, WhatsApp, or a new ChatGPT chat. |

`note` becomes `desk_write notes.md append`, so notes survive compaction and resume.

**Storage and limits.**
- Files live in `files/Cyclone Brain/Missions/<missionId>/desk/`: at most 20 files of 20 000 characters each.
- They are deleted with the mission.
- They are listed in `situation()` ("Desk: prompt.md (412 chars), notes.md") and re-listed after a summary
  compaction.

**Safety.**
- `desk_write` refuses secret-shaped content, using `MarketRules.SECRET_SHAPE` together with the existing
  diagnostics redactor, and tells the model to use `vault_fill`.
- Desk text is model-authored working text, so it may appear in run diagnostics. It is never mirrored to Brain,
  Learn, the Atlas or the Marketplace.

**The owner can see it.**
- **Phone:** the mission card shows *Drafts* with **Copy** and **Share** per file (Task Kit commands).
- **Glass:** the run inspector gets a *Desk* tab with read-only text and **Copy**, using the browser clipboard in
  Glass itself. No PC clipboard bridge and no gateway write op are needed. There is one new read-only op,
  `runs.desk {runId}`, returning file names plus text capped at 20 000 characters, validated like the other V5 ops.

**What the Desk is not:**
- no code execution, shell, scripts or file access outside the mission folder;
- no reading files from other apps;
- no automatic sending.

**Exit:**
- A Lab mission *"read this ChatGPT answer, write a better follow-up prompt, save it, and send it"* shows
  `desk_write` → `type_text draft=` → sent, with the draft visible in Glass.
- A resumed mission still sees its desk.
- A unit test proves a secret-shaped `desk_write` is refused and nothing is stored.

### Phase 3: prove it stays working (every release after)

- **Lab "Hands" suite:** 8 text-delivery missions across ChatGPT, Keep, Gmail, WhatsApp (draft only), Chrome,
  Messages (draft only), Settings search and Play Store search.
- **Release gate:** below 95% delivery on this suite blocks the alpha.
- **Fixtures:** every real refusal captured from an owner run becomes a regression fixture for
  `CurrentTargetRevalidation`.
- **Metric on the dashboard:** *text delivered on the first try* and *owner handoffs per 100 text tasks*.

---

## 5. What not to build, and why

- **A separate `paste_text` tool the model must choose.** Choosing between set-text and paste is a device detail. The
  executor tries both and verifies, so the model only chooses *where*.
- **The desk before the hands fix.** It would have produced a perfectly stored draft that still could not be tapped
  into ChatGPT.
- **A PC clipboard bridge (Companion).** Glass already runs in the PC browser: `navigator.clipboard.writeText` on the
  desk text covers "copy to my PC" with no new trust surface.
- **Loosening revalidation globally.** Only the editable-field cases proven by fixtures change. Distinct siblings and
  overlays stay fail-closed; the approval and GATE paths depend on that.
- **Exposing `clipboard_get` to the model.** The owner's clipboard can hold anything, including passwords. Mind writes
  to the clipboard; it does not read it.

---

## 6. Order of work and ownership

| Step | Paths | Owner lane |
|---|---|---|
| 0.1–0.2 fixture + honest refusals | `agent/tools/*`, `mind/PhoneMindToolbox.kt`, tests | mobile |
| 0.3 Mind run record | runs adapter (phone), Glass run inspector copy | mobile + glass |
| 1.1 revalidation (fixture-driven) | `agent/tools/CurrentTargetRevalidation.kt` | mobile (one agent; high-risk file) |
| 1.2–1.4 focused typing, delivery ladder, focus signal | `PhoneToolExecutor.kt`, type engine, toolbox | mobile |
| 1.5–1.6 loop breaker + handoff + prompt | `mind/MindLoop.kt`, owner moments, `MindPrompt.kt` | mobile |
| Lab Hands suite + gate | gateway `lab/` suite, release checklist | gateway |
| 2.x Desk | `mind/desk/*` (new), toolbox, mission card, `runs.desk` op, Glass Desk tab | mobile, then gateway + glass |

**Release sequence** (the owner put map-guided runs first; Hands follows right after, because every map-guided run still has to type):

| Release | Contents |
|---|---|
| **alpha.37** | Map-guided runs (plan 20), as the owner decided on 2026-09-25: built. |
| **alpha.38** | Mapping missions from Glass and the Glass Atlas (plan 22), as the owner decided on 2026-09-26. |
| **alpha.41** | Hands: Phase 0 + Phase 1, with the Lab Hands suite as the gate (alpha.40 is Planes, plan 25). |
| **alpha.44** | Desk (Phase 2), after Cyclone Drive (plan 24, alpha.42–43). |

---

## 7. Decisions, with recommended defaults

1. **Clipboard after a paste:** restore the owner's previous clip if we saved it, otherwise clear ours. *(Recommended.)*
2. **Chat replies the owner explicitly asked for** ("prompt ChatGPT…"): no GATE card to press send in an AI chat app;
   messages to people keep GATE. *(Recommended; matches today's policy for owner-directed sends, confirm.)*
3. **Desk visibility:** phone mission card and Glass read-only, both in the Desk release. *(Recommended.)*
4. **Hands handoff threshold:** four failed attempts on one field, then the clipboard handoff card. *(Recommended.)*

## 8. Definition of done

- The failing mission succeeds ≥ 19/20 in the Lab.
- The Hands suite is at ≥ 95%, and refusals name their real reason.
- A failed delivery always ends with the text on the clipboard and a one-tap handoff.
- Desk files survive resume and compaction, are visible to the owner, and never hold secrets.
- Physical Pixel 8 results are reported honestly per app.
