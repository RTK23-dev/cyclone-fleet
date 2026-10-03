# 37 — The mission workspace: structured context that makes a run feel like it understands

**Status:** plan, 2026-09-28, revised the same day after a flexibility review (§15). **W1 and W2 built in alpha.66 (§16); W3 built in alpha.67 as memory v2 (§17).** The revisions: guidance instead of gates, no new
tools (existing ones gain optional arguments), nothing scrapped (folded and recallable). Four build runs (W1–W4, §12),
inserted before fleet health in plan 35. Builds on the
Mind (plan 16), Hands (21), planes (25–28), direct actions (29), the App Manual (36) and parallel sessions (26 §6).
Nothing is built yet.

**The owner's ask:**
> "Is it possible to have not only one global user notes but also per app manual that has filled in blocks like people
> and content. So for the Instagram example it lists the actual close friends or often asked about chats and remembers
> short notes about them like Louella is my girlfriend, Instagram name lo.06. Then my real idea was modules or context.
> A run with multi apps gets a section of the app map navigation in its prompt, gets the global user notes, and the run
> context and goals and current page info, all very structured so it can make decisions and think inside one run chat.
> Then it has a dedicated workflow for switching apps, home screen, etc., and it keeps the total conversation intact and
> the chain of thought and goal, and swaps out the current page context. The context of every previous page gets
> compacted or scrapped so the chain of thought doesn't become millions of tokens, but it can remember where it came
> from, what reasoning it did before, where it is going and what the plan is. If needed it can change the plan, and it
> should show the mission diverted in a nice way for the user too." … "How to make the structured context so good it
> will make a Cyclone run feel like AGI."

**In one sentence:** every model call is assembled from eight owned modules in a fixed order, so the stable part is
cached and the model always sees the goal, a checkable definition of done, the people and notes that matter, one short
block per app it already visited, a live state (plan, collected facts, where it is and how to get back, surprises),
the current app's section of the map and manual, and exactly one full screen — about 25–30k tokens whether the run is
10 turns or 200.

---

## 0. Decisions (locked unless the owner changes them)

| # | Decision | Why |
|---|---|---|
| D1 | **One conversation stays the mission's memory** (plan 16). The workspace changes *what each call shows*, not who decides: the model still decides every step. | The Mind's strength is one continuous line of thought; we keep it and remove the noise around it. |
| D2 | **The harness does bookkeeping, the model does judgement.** Where the phone is, what was collected, loops, what changed on the screen, and whether an action did what it said are computed by code, never by an extra model call. | Cheaper, faster and more reliable than asking a model to summarise itself. |
| D3 | **Stable first, changing last.** Rules, brief and "your world" never change inside a mission; the journal changes only when an app stay closes; the live state, app section and screen are rebuilt every turn and sent last. | Provider prompt caching reuses the prefix; the newest facts sit where attention is strongest. |
| D4 | **People and notes are a separate, owner-owned store** (§8), never part of the App Manual. | The manual's CI-guarded promise stays: "the app's own words, never your content" (plan 36 §4). |
| D5 | **Nothing personal is learned silently.** People cards and app notes are written by the owner or proposed by the model and saved only on the owner's OK. | Consent, and no surprise data on the phone. |
| D6 | **Behind a switch, promoted by the Lab.** The workspace ships as a Lab variant (`context: workspace`) and an owner setting; it becomes the default only when it wins every suite (§11). | "Consistently better", measured, never assumed. |
| D7 | **Boundaries are unchanged.** PhoneToolExecutor is the only mutation engine; one screen-changing action per turn; approvals for pay, send, delete, permission and sign-in; no secrets in any module, journal, record or model. | The laws of every plan since 16. |
| D8 | **Guidance, not gates.** The workspace informs the model; it never refuses an action. The only refusals are the ones that exist today (safety boundaries, invalid arguments, secrets). Every new argument is optional; a model that ignores the structure works exactly like a classic run. | A billion different goals and apps: rigid structure breaks on the ones nobody designed for. |
| D9 | **No new tools.** Existing tools gain optional arguments (`open_app`, `plan_update`, `note`, `remember`, `recall`, every screen-changing tool). The toolbox stays at 39 tools. | Every extra tool is a choice the model can get wrong; models navigate a small, familiar toolbox best. |
| D10 | **Nothing is scrapped, only folded.** Old screens shrink to one line in the prompt, but their full text stays in the mission journal; `recall` unfolds any earlier turn or app stay on demand. | Compaction can never make a fact unrecoverable, so it can be aggressive safely. |
| D11 | **The screen is the truth.** When the live state and the screen disagree, the screen wins; the rules say so. | Bookkeeping can be wrong (a missed transition, a mislocated screen); the model must never be steered by a stale state. |

## 1. What a run does today (the gaps this closes)

From the code (`mind/MindConversation.kt`, `mind/MindLoop.kt`, `mind/MindPrompt.kt`, `mind/PhoneMindToolbox.kt`,
`mind/MindMemory.kt`):

| Part | Today | Gap |
|---|---|---|
| Opening message | Goal, situation, recent missions, `MindMemory.digest()` (≤200 flat facts, 3,000 chars) | No people, nothing per app, retrieval by shared words only ("my girlfriend" finds nothing unless a fact says "girlfriend") |
| Map and manual | `mapCard` shows once per app, on the **first** visit | After compaction it is gone; on a **return** to the app it is never shown again |
| Plan | `plan_update` goes to the owner's card; the model gets "Plan updated (n steps)" | The plan is never restated; it lives in an old tool call |
| Notes | `note` answers "Noted." | No ledger of collected facts; they scatter through history |
| History | Every screen stays verbatim until **150,000 chars** (`MindBudget.maxContextChars`), then old results shrink to one line | Cost grows with the square of the turns; the previous app's screens are noise for dozens of turns |
| App switch | No boundary (`rebind` only after a plane move) | No "where I came from, what I got there" |
| Plan change | Silent | The owner never sees the mission divert |
| Actions | A tool succeeding means the phone accepted it | Nothing checks whether the result matched what the model meant |

## 2. The module stack

Every request is modules 1–5 (the stored conversation, folded per stay) followed by modules 6–8 (rebuilt each turn,
never stored in the conversation, journaled only as the last snapshot for resume).

| # | Module | Contents | Budget | Refreshed | Source |
|---|---|---|---|---|---|
| 1 | **Rules** | Identity, boundaries, how a turn works, the workspace legend | ≤10k chars | Never in a mission | `MindPrompt.system` |
| 2 | **Brief** | The owner's goal verbatim; the definition of done (§5); resolved people and places; constraints; deadline | ≤6k chars | Only when the owner adds something mid-run (appended, not rewritten) | Goal + `plan_update(done)` or the harness's draft + resolver (§8) |
| 3 | **Your world** | People in this mission; relevant facts (`MindMemory.search` + owner notes); app notes for apps in the plan; 1–3 recipes of similar past missions (W4) | ≤8k chars | At start; when a new app enters the plan (appended) | `OwnerNotes`, `MindMemory`, `MissionRecipes` |
| 4 | **Journal** | One block per closed app stay (§3); the model's own reasoning lines and one-line tool calls of closed stays | ~600 chars per stay | Appended when a stay closes | `MissionWorkspace` |
| 5 | **This app stay** | The last 6 turns of the current stay in full; older turns of this stay as one-liners | ≤32k chars | Every turn | Conversation |
| 6 | **Live state** | Plan with the current step; collected facts with source; open questions; surprises; where + the way back; time left | ≤6k chars | Every turn, sent after module 5 | `MissionWorkspace` |
| 7 | **App section** | The map near the current screen; manual abilities that fit the current plan step; owner notes for this app; learned traps (W4) | ≤8k chars | On arrival in an app and when the plan step changes | `MindMaps`, manual, `OwnerNotes`, `AppTraps` |
| 8 | **Screen** | The current screen (refs, field values), "what changed since the last screen", the expectation check (§4); one screenshot | ~16k chars typical, never shorter than classic + 1 image | Every turn | `MindScreen`, `ScreenDelta`, `ExpectCheck` |

**Budgets are soft and prioritised.** When the total would exceed the target, modules shrink in this order: 3 (fewer
facts and recipes), 4 (oldest stay blocks to one line), 5 (older turns of this stay to one-liners), 7 (map to the
nearest screens only). The screen (8), the live state (6) and the brief (2) are never cut below what they need; the
screen is never shorter than today's classic render, and `screen_find`/`scroll` read beyond it as today.

**Light when the mission is light.** A module with nothing to say is left out entirely. A short single-app mission
(a timer, a toggle, a question) looks almost like a classic run: rules, brief, the screen and a one-line live state.
The plan, journal and app section appear when there is a plan with 2+ steps, a second app, or turn 6.

**Total target:** ≤100k chars (≈25–30k tokens) at any turn. `MindBudget.maxContextChars` stays as the hard ceiling
for classic runs; workspace runs get `MindBudget.workspaceChars = 100_000` and compact per module, not globally.

**Caching:** within one app stay the wire of turn *n* is a byte-exact prefix of turn *n+1* up to module 5
(CI-tested, §10). For providers that need explicit breakpoints (Anthropic models through OpenRouter), `MindModel`
adds `cache_control` after module 3 and after module 4. Diagnostics record cached vs uncached input tokens per turn.

**Legend in the rules** (module 1), so the model reads the workspace the same way every time:

```
## Your workspace (rebuilt every turn, always in this order at the end)
LIVE STATE — plan, collected facts, where you are, surprises. Cyclone's bookkeeping, to help you; the SCREEN is the
truth when they disagree.
APP SECTION — how this app is laid out and what it can do, near where you are. Hints, not orders: use them when they
fit, ignore them when the screen shows otherwise.
SCREEN — the current screen. "Changed:" says what is new. "Check:" says whether your last expect held.
Older screens are folded to one line to keep you fast; nothing is lost: recall(turn=…) or recall(stay=…) shows one
again. Use the structure when it helps; the goal is what counts.
```

## 3. App stays, the journal and switching apps

**An app stay** is the time the mission spends in one app. The launcher counts as "Home".

**What does not open a stay (transient surfaces):** the share sheet and intent chooser, the system photo and file
pickers, the permission controller, Google sign-in and Play Services sheets, Custom Tabs and in-app browsers opened from
the app, the keyboard, the notification shade, Cyclone's own overlay and the Secrets Card. They belong to the stay
that opened them: picking a photo in Instagram is still "in Instagram". The list lives in one place
(`mind/workspace/Surfaces.kt`), is tested, and unknown packages are treated as a real app only once they persist.

**Hysteresis:** a new app opens a stay only when it stays in front for 2 observations or the model moved there on
purpose (`open_app`, `home`, `open_link` to another app). Returning to the previous app within 3 turns **re-opens the
same stay** instead of starting a new one, so a quick look elsewhere never folds the work in progress.

**Closing a stay** (in `MissionWorkspace.closeStay`, no model call):
1. The stay's tool results shrink to their one-line briefs in the prompt (the full text stays in the journal, D10);
   its screenshots are removed; provider `reasoningDetails` of its assistant turns are dropped (they are opaque and
   large; they stay only for the open stay).
2. The model's own visible text of each turn stays, trimmed to 300 chars per turn.
3. The last screen of the stay stays as a short sketch (title, list headings, fields), so "where I left it" is
   concrete.
4. A **stay block** is appended to the conversation as a HARNESS message:

```
JOURNAL · stay 2 · Maps (turns 9–15)
Did: searched "Kerkstraat 12", chose bike.
Got: ETA = 22 min (collected)
Left: route screen (s4). recall(stay=2) shows it again.
Surprise: the Directions sheet opened collapsed once.
Carry: reply to Louella with the ETA.
```

`Did` comes from the stay's successful screen-changing calls (tool + target text), `Got` from ledger entries added
in the stay, `Left` from the last screen and the map locator, `Surprise` from missed checks (§4), and `Carry` from the
model's `carry` argument when it gave one (otherwise the current plan step, or nothing).

**Switching apps uses the tools the model already knows** (D9):
- `open_app(app, why?, carry?, resume?)`: `why` and `carry` are optional and feed the stay block and narration;
  `resume=true` brings the app back where it was left (Recents when its task is alive, otherwise it opens the app and
  shows the stay's `Left` sketch so the model can `go_to` there). Without the new arguments it behaves as today.
- `home`, `back`, `open_link`, `open_settings`, notifications and planes work as today; the workspace follows the
  package changes they cause.
- Module 7 is rebuilt for the app in front, **also on a return** to an app visited before (the first-visit rule of
  `mapCard` does not apply in workspace runs).
- Direct actions (calendar, contacts, clock, notification replies) need no stay and never close one.

**Unfolding (D10):** `recall` gains `turn` and `stay` arguments. `recall(stay=2)` returns the stay's journal block plus
its last full screen; `recall(turn=14)` returns that turn's full tool result from the journal. Without them it answers
from memory as today.

## 4. Every turn: expect, act, check

**The turn contract.** Every screen-changing tool gains two optional arguments, suggested (not required) in the
rules:
- `step`: the plan step number this action serves;
- `expect`: what should be true after it, in plain words ("chat with lo.06 opens", "the toggle is on").

Leaving them out costs nothing: the action runs, the screen comes back, and the check line is simply absent.

**The check** (`mind/workspace/ExpectCheck.kt`, pure and tested) compares the new observation with `expect` and the
previous one:

| Result | When | Shown in module 8 |
|---|---|---|
| `✓` held | The expected app is in front and the expected words or state are present | `Check: ✓ chat with lo.06 is open` |
| `✗` missed | Another app, or a named thing is absent while something else is present | `Check: ✗ expected the lo.06 chat, got a profile page "lo.06_official"` |
| `~` unchanged | The screen fingerprint did not change (Fast Path settle already ran) | `Check: ~ nothing changed; the tap may not have landed` |
| `?` can't tell | The expectation names nothing checkable | nothing (no noise) |

It matches: app names (installed labels), quoted words and handles (exact), states ("on/off/checked/selected" against
the element's state), and "opens/shows X" against visible text and the map locator. It never calls a model.

**Conservative by design:** it reports `✗` only when it is sure (another app is in front, or a quoted word or handle
is absent while a different one is present). Anything fuzzy is `?` and shows nothing. A false `✗` would push the model
off a correct path, so the Lab tracks the false-miss rate (a `✗` followed by the mission succeeding on the same path)
and the matcher is tuned against it.

**What follows from a check:**
- `✗` and `~` add a **surprise** to the live state (`S2: turn 14, expected chat, got profile`).
- Two surprises on the same step give a harness note: "Step 3 has surprised you twice. If this route keeps failing,
  consider another (a different route, abilities_find, or ask the owner)." It is advice; the model may carry on when it
  sees why (a slow load, a one-off dialog). The existing loop breaker (A41-3) is unchanged.
- Lab metric: **expect hit rate** = `✓ / (✓ + ✗ + ~)` per mission and suite (§11).

**"What changed"** (`mind/workspace/ScreenDelta.kt`): the difference between the previous and the new element trees,
at most 6 lines: new dialogs and sheets, fields whose value changed, controls that appeared or disappeared, the title.
It uses the same fingerprints as the Fast Path settle.

## 5. The definition of done

**Where it comes from (no extra turn):**
- `plan_update` gains an optional `done` argument: 1–5 checks, each `{kind, value}` with `kind` in `app_shows` (words
  visible in an app), `sent_to` (a message to a recipient in an app), `setting` (a named setting is on/off/a value),
  `answer` (an answer the owner asked for), `other` (plain words, verified by evidence). The model gives it with its
  first plan, whenever that is; it may refine it later (a refinement is shown to the owner, like a diversion).
- Without it, the harness drafts one from the goal and the recipe when that is unambiguous (a send names its
  recipient, a setting names its value), shown as "Done when (draft)". A goal with no clear check gets none: the
  finish works as today, with evidence.
- `people` and `constraints` come from the resolver (§8) and the goal's own words ("don't post publicly",
  "under €20"), not from a separate call.

The done checks sit in module 2 and on the task card ("Done when: …"). The owner can correct them with the existing
steer path (Task Kit `Reply`), which appends to the brief.

**Finishing:** `task_finish` looks for each check **across the whole mission**, not only the final screen: a screen
seen earlier (from the journal), a ledger value, the approved `MindSend` of this mission (recipient and app), or the
summary for `answer`. If a checkable one is nowhere to be found, the first finish gets one note naming it; a second
finish is always accepted and the check is recorded as `unverified` in the run record. `other` checks never block.
This follows the existing "evidence is required" pattern: one nudge, never a trap.

## 6. Plan, diversions and what the owner sees

**Plan steps** gain two optional fields: `app` (the app a step happens in, used to prefetch module 7) and `why`
(used for narration and diversion cards). `plan_update` never refuses a plan; a plan without `why` is simply shown
without one. A mission may have no plan at all (short missions rarely need one).

**A diversion** (`mind/workspace/Diversion.kt`) is recognised in two ways only, so rewording a step never raises a
false card:
- the model says so: `plan_update(divert={from, to, why})`;
- the harness sees an approval whose recipient, app, amount or audience differs from what the goal or the done checks
  named.

| Kind | Example | What happens |
|---|---|---|
| Minor | Reordered, reworded or extra steps | Plan card updates; nothing else |
| Diverted | "DM on Instagram" → "message on WhatsApp", model-declared | A **Mission diverted** line on the task card, the overlay island and Glass: ~~DM on Instagram~~ → WhatsApp — "her DMs are closed to non-followers". The mission carries on |
| Diverted at an approval | The send, pay or post approval differs from what the owner named | The existing approval card shows the change on top ("You asked for Instagram; this goes by WhatsApp because …"). One owner touch, the same one as today |

**No new stop.** A diversion never adds a separate waiting card: the approvals that already exist for send, pay,
delete, permission, sign-in and posting are the moments where a change of who, how, how much or how public matters,
and they now say what changed. Where the owner left the choice open ("message her on whatever works"), no change is
flagged at all.

**The owner's view:**
- **Narration:** one line per step, built from the plan step and the model's `why` (no model call): "Getting her
  address from WhatsApp…", "Checking the bike time in Maps…". It replaces the raw status text on the task card.
- **Plan card:** steps with ✓ / → / ☐, the current step highlighted, the app icon per step.
- **Journal chips:** WhatsApp → Maps → WhatsApp, tappable on the phone's mission panel and in Glass.
- **Finish:** "Sent at 18:42 · delivered ✓ · chat with lo.06", from the definition-of-done checks.

## 7. The live state in detail

```
LIVE STATE · turn 17 · 4 min 10 s used of 30
Plan:  ✓1 read Louella's address (WhatsApp)  ✓2 bike time (Maps)  →3 reply with ETA (WhatsApp)  ☐4 confirm delivered
Collected:  address = Kerkstraat 12 (WhatsApp, t7) · ETA = 22 min (Maps, t14)
Open: —
Surprises: S1 t11 Directions sheet collapsed (resolved)
Where: WhatsApp › chat "Louella ❤️" · came from Maps · Home and back: Recents
```

- **Collected** is the ledger: `note(text)` keeps working and gains an optional `key`; the harness stamps app and turn.
  Values pass `MindRedaction.scrubText` and the `MindMemory` secret refusal; a refused value is never stored. The
  ledger survives every compaction and is journaled for resume. At most 30 entries; the oldest resolved ones fold
  into the stay blocks.
- **Where** comes from the package, the map locator (`MindMap.locate`) and the stay trail.
- **Loops:** the same screen fingerprint plus the same action three times adds a hard line here ("You are repeating
  e12 on the same screen") and counts as a surprise.

## 8. People and notes (the owner's own store)

**Store:** `mind/people/OwnerNotes.kt`, a JSON document encrypted with AES-GCM under an Android Keystore key
(`cyclone-owner-notes`), at `Cyclone Brain/Owner notes.enc`. Schema `cyclone-owner-notes-v1`:

```
Person  { id, name, aliases[], relation?, handles{package → handle}, note (≤200), updatedAt, source: owner|proposal }
AppNote { id, package, kind: note|chat|list, title, text (≤300), items[] (list only, ≤50), updatedAt }
```

Examples: Louella · girlfriend · Instagram `lo.06`, WhatsApp "Louella ❤️"; WhatsApp chat note "Family" = the family
group; Instagram list "Close friends" with the names the owner typed or approved; Instagram note "post from
@mybrand, not my personal account".

**How entries are made (always with consent):**
1. **By hand:** Settings → AI → **People & notes** (next to the Mind memory list in `CycloneAiSettingsActivity`):
   add, edit, delete, "Forget everything".
2. **Proposed in a run:** `remember` with a `person` or `app` argument posts a card through the shared inbox (a new
   `OwnerRequestKind.PROPOSAL`): "Remember: Louella is your girlfriend, Instagram lo.06? **Save** / **Edit** / **No**".
   Answers go through Task Kit (`Approve`/`Decline`/`Reply`). Nothing is saved without Save.
3. **Suggested:** a name the owner's goals mention in three missions within 30 days gets a "Add Louella to People?"
   suggestion on the chat page. Names are never taken from screens for this.

The proposal path uses the existing `remember` tool (D9): `remember(text, person?, app?)`. Plain `remember` works as
today; with `person` or `app` it becomes a People & notes proposal. The model can always use what the owner said
**within the mission** (the ledger) without saving anything.

**Retrieval (before the first turn):** `mind/people/Resolver.kt` matches the goal against names, aliases, relations
(girlfriend, mom, boss…, in English and Dutch) and handles. Unique matches go into module 2 ("People: Louella —
girlfriend; Instagram lo.06; WhatsApp 'Louella ❤️'"); several matches go in as candidates: the model first uses the
phone to tell them apart (only one of them has a chat in this app, the goal names the app) and asks the owner only if
it still can't; nothing matched means nothing is added, and the model works from the goal as today. Relations are
matched by a small word list plus the owner's own words on each card ("Lo", "my love"), so it grows with use. Module 7 shows only the handle for the current app.

**The send check:** when the mission's `sent_to` check or a person card names a handle for the current app and the
chat on screen shows a different one, the send approval card says so plainly ("This chat is lo.06_official, not
lo.06") and the model is told. It never sends silently to a near match.

**Privacy rules (CI-guarded):**
- Owner notes never enter the App Manual, the dictionary, map exports, the Marketplace, recipes, Glass manual views or
  learning stores.
- Lab runs never read them (`freshMemory` covers owner notes too).
- They reach the model only through modules 2, 3 and 7 of the owner's own missions, and the PC only if the owner opens
  People & notes in Glass (read-only in W3; editing on the phone).
- The secret refusal of `MindMemory` applies to every field.

## 9. Recipes, traps and speed (the memory that compounds)

- **Mission recipes** (`mind/learn/MissionRecipes.kt`): when a mission completes with its definition of done met, the
  harness stores a recipe: goal shape (verbs and app names only), the apps in order, the key moves as app words (from
  the stays' `Did` lines, filtered through the dictionary's chrome filter so no content survives), and the surprises
  that were resolved. Retrieval by goal similarity (the `AbilityIndex` scorer); 1–3 go into module 3 as "Last time a
  mission like this worked like: …". The canary guard of plan 36 covers them.
- **App traps** (`mind/learn/AppTraps.kt`): a surprise with the same screen and the same kind in two missions becomes a
  trap line in module 7 for that app ("After typing the caption, Post moves below the keyboard; hide the keyboard
  first"). The owner can clear them per app.
- **Prefetch:** when the current plan step is done and the next step names another app, module 7 for that app is
  prepared on a background thread.
- **Known routes without a model call:** when the next step matches a manual ability with a verified path, the rules
  steer the model to `go_to(ability=…)`, which walks it with plain-code checks (alpha.64).

## 10. Build: files and tests

### Phone (`apps/mobile`)
- **New `mind/workspace/`:**
  - `MissionWorkspace.kt`: stays, ledger, plan versions, surprises, where; `closeStay`, `openStay`, `liveState()`,
    `journalBlock()`, `toJson()/fromJson()` for resume.
  - `WorkspaceAssembler.kt`: builds the wire from the conversation (modules 1–5) plus modules 6–8; per-module
    budgets; the prefix-stability invariant.
  - `ExpectCheck.kt`, `ScreenDelta.kt`, `Diversion.kt`, `DoneCheck.kt` (pure).
- **Changed:**
  - `MindConversation.kt`: stay markers on messages; `closeStay(range)` compaction; `toWire(native, tail)` appends
    the rebuilt modules as the last user message (after tool results and screenshots, as screens are today).
  - `MindLoop.kt`: `MindBudget.workspaceChars`; asks the assembler instead of `conversation.toWire` when the
    workspace is on; surprise notes; checkpoint includes the workspace.
  - `MindPrompt.kt`: the workspace legend and turn contract (only in workspace runs); `mission()` unchanged for
    classic.
  - `PhoneMindToolbox.kt` (no new tools, D9): `open_app(why?, carry?, resume?)`; `recall(turn?, stay?)`;
    `plan_update(app?, why?, done?, divert?)`; `note(text, key?)` as ledger; `remember(person?, app?)` as proposals
    (W3); `step`/`expect` on screen-changing tools; `mapCard` per stay in workspace runs; `task_finish` through
    `DoneCheck`.
  - `mind/workspace/Surfaces.kt`: the transient surfaces that never open a stay.
  - `MindTools.kt`: `MindToolResult.check` (the expectation result) and `stayChange`.
  - `mission/MindMissions.kt`: builds modules 2–3 at start (resolver, memory search, notes); the workspace switch
    (Lab variant or setting); narration, plan card, journal chips and diversion events to the task card, overlay and
    behind notifications (alpha.65 surfaces).
  - `mission/OwnerInbox.kt`: `OwnerRequestKind.PROPOSAL` (W3); `task/TaskCommands.kt` routes it. Approval cards gain an
    optional "what changed" line for diversions seen at an approval.
  - `mind/lab/MindLab.kt`: variant key `context` (`classic` | `workspace`, default `classic` until promoted).
  - `ui/overlay/CycloneAiSettingsActivity.kt`: **Mission workspace** switch (W1–W2, off by default), **People & notes**
    (W3).
  - Run record (A41-3) and `runs.*` ops: stays, plan versions, checks, diversions, done checks, cached-token share.
- **Tests (JVM):** `FlexibilityTest` (a model that uses none of the new arguments completes the fake missions exactly
  as in classic; no workspace path refuses an action; a photo picker, share sheet, permission dialog or Custom Tab
  never opens a stay; a quick return re-opens the same stay; `recall` unfolds any folded turn byte-exact),
  `MissionWorkspaceTest` (stays open and close on package changes; the ledger survives compaction;
  budgets hold at 200 turns; resume restores the workspace), `WorkspaceAssemblerTest` (prefix stability within a stay;
  modules in order; nothing over budget), `ExpectCheckTest`, `ScreenDeltaTest`, `DiversionTest`, `DoneCheckTest`,
  `OwnerNotesTest` (encryption round trip with a fake key; secret refusal; forget), `ResolverTest` (relations,
  aliases, ambiguity), `SendCheckTest`, `MissionRecipesTest` (canary never kept).

### Gateway (`apps/device-gateway`)
- `lab/missions.py`: suites `multiapp` (8 missions: address from Messages → Maps ETA → reply; a code from Gmail into
  Keep; a calendar event from a message; a Wi-Fi name into a note; …), `long` (5 missions of 30+ turns), `people`
  (W3: seeded cards with a canary recipient; a wrong recipient fails the mission).
- `lab/stats.py`, `lab/verdict.py`: expect hit rate, surprises caught before a wrong action, tokens and cached share
  per mission, stays, diversions, done-check pass rate, wrong-recipient count.
- `runs.*` pass the new record fields through to Glass and the agent tools.
- **Tests:** suite validation, stats maths, verdict with the new fields.

### Glass (`apps/glass`)
- Run inspector (`pages/runPage.ts`): a stays timeline (app chips with the journal block), plan versions with
  diversions struck through, the check mark per step, the definition of done with pass/fail.
- Lab page: the new metrics per arm; the `context` knob in the experiment form.
- People & notes (W3): a read-only view under Knowledge, opened on request only.
- **Tests:** vitest for the timeline, plan diff and Lab metrics; `glass_guard.py` stays green.

### Guards (`scripts/ci/tests/test_mission_workspace_guard.py`)
1. Workspace modules are built only in `WorkspaceAssembler`; nothing else appends model-visible context in workspace
   runs.
2. Every module passes `MindRedaction` before it is sent; the ledger refuses secrets.
3. Owner notes are read only by `Resolver`, `WorkspaceAssembler` and the People UI; never by manual, dictionary,
   mapping, recipes, Lab or export code.
4. `OwnerNotes` writes only encrypted bytes; no plaintext file with that name exists.
5. The diversion rule: when a send, pay or post approval differs from the recipient, app, amount or audience the owner
   named, the approval card carries the "what changed" line.
6. `task_finish` goes through `DoneCheck`.
7. Recipes and traps pass the chrome filter; the plan 36 canary guard covers them.
8. Proposal cards are answered only through Task Kit.
9. **No new gates:** the workspace code returns no refusal of its own; the toolbox spec count stays at 39 (new
   arguments only).

## 11. How we prove it (and when it becomes the default)

**Arms:** `context: classic` vs `context: workspace`, same build, same model, ≥30 trials per arm per suite, run by
Cyclone Lab (plan 18).

**Promotion rule** (all must hold, per model the owner uses):

| Measure | Rule |
|---|---|
| Success on `smoke`, `core`, `map`, `hands`, `planes` | Workspace ≥ classic within the Lab's noise band (no regression) |
| Success on `multiapp`, `long` | Workspace better, significant at the Lab's threshold |
| Tokens per mission | ≥40% fewer on `long` and `multiapp`; not more on `core` |
| Wrong recipient / wrong account (`people`) | 0 |
| Done-check pass rate on completed missions | ≥95% |
| Median model turn latency | Not worse than classic |
| Turns per mission on `core` | Not more than classic (the structure must not add steps) |
| False-miss rate of checks | ≤2% of checks |

**Targets that define "feels like it understands"** (tracked every build, not gates for W1):
- first-try success on `multiapp` ≥ 90%;
- questions asked for information already in People & notes: 0;
- surprises caught before a wrong action: 100%;
- a 3-app errand done in under a minute;
- expect hit rate rising release over release.

**A blind review** each week: 20 classic and 20 workspace recordings side by side, rated "would I trust this with my
phone?".

## 12. Releases

| Run | Release | Delivers | Exit criteria |
|---|---|---|---|
| **W1** | **alpha.66: Mission workspace** | Modules 1–8 with budgets and caching; app stays with transient surfaces and hysteresis, journal blocks, `open_app(why, carry, resume)` and `recall(turn, stay)`; the ledger, where-line and loop line; module 7 on every return; plan `app`/`why`/`divert`; diversions (minor, declared, shown on the existing approvals) with narration, plan card and journal chips; the setting (off) and the Lab `context` knob; suites `multiapp` and `long`; stays in the run record and Glass inspector | All unit tests and guards; Lab: no regression on `core` with the workspace on; the first `multiapp`/`long` numbers published in the release notes |
| **W2** | **alpha.66 (with W1): Expect and done** | `step`/`expect` on screen-changing tools, `ExpectCheck`, `ScreenDelta`, surprises and the step note; `plan_update(done)`, the harness's draft and `DoneCheck` across the whole mission on `task_finish`; expect hit rate and done-check metrics in the Lab and Glass | Lab: expect hit rate reported; done-check pass rate ≥95% on completed missions; still no regression |
| **W3** | **alpha.67: People and notes** | `OwnerNotes` (encrypted), People & notes on the phone, proposals and suggestions, `Resolver`, the send check, Glass read-only view; the `people` suite | `people` suite with 0 wrong recipients; privacy guards; canary absent everywhere |
| **W4** | **alpha.69: Recipes, traps, speed; promotion** | `MissionRecipes`, `AppTraps`, prefetch, `cache_control` breakpoints, cached-token metrics; the promotion run of §11 and, if it passes, the workspace becomes the default for owner missions | The §11 promotion rule holds for the owner's model and its backup |

Fleet health and alerts (plan 33 §10–11, planned as alpha.66) moves to **alpha.69**; plan 35 is renumbered.

Every release states physical acceptance as owed until the owner tests it on the Pixel.

## 13. Risks

| Risk | Guard |
|---|---|
| A closed stay loses a detail the model needs later | The ledger holds collected values with their source; the stay block says where the app was left; `recall(stay)` unfolds it and `open_app(resume=true)` returns there |
| The model ignores the live state | Same place, same headings every turn; `expect` makes the model name its intent; surprises are listed where it reads |
| Structure costs more tokens on short runs | Modules are empty when there is nothing to say; the Lab rule "not more on `core`" |
| A weaker model is confused by the format | Per-model promotion (§11); classic stays available per model |
| An outdated person card | The send check on screen; the card's age shown in module 2; editing is one tap |
| A prompt injection through a screen, a recipe or a note | Screen text stays information, never instruction (the trust rule); recipes hold app words and steps only; notes are owner-written or owner-approved |
| Caching differs per provider | The cached share is measured; the design is still cheaper without caching because the history stays small |
| The structure is too rigid for an unusual goal or app | D8–D11: no gates, no new tools, nothing scrapped, the screen wins; §15 lists the escape hatches; the Lab tracks turns and false misses |
| The workspace code fails mid-run | The assembler falls back to the classic wire for that turn and records it; the mission never stops because of the workspace |

## 14. Not in this plan

- A second model for summaries or planning (D2: the harness does bookkeeping; the Mind decides).
- Reading the owner's messages to learn people automatically (D5).
- Changing approval boundaries, the one-mutation rule or PhoneToolExecutor.
- JEV (parked).

## 15. Flexibility review (revision of 2026-09-28)

The first draft was checked against the code (`PhoneMindToolbox` has 39 tools, `recall` already exists, `open_app`
takes just `app`) and against how phones really behave. What was too rigid, and what changed:

| First draft | Why it was too rigid | Now |
|---|---|---|
| New tools `switch_app`, `home(why)`, `back_to`, `mission_define`, `propose_note` | 39 → 44 tools; more choices to get wrong; models already use `open_app`/`home` well | No new tools; optional arguments on `open_app`, `plan_update`, `note`, `remember`, `recall` (D9) |
| `mission_define` required before the first action | An extra model turn on every mission, even "set a timer"; many goals only become clear after looking | `done` is optional on the first `plan_update`; the harness drafts it only when the goal is unambiguous (§5) |
| `task_finish` refused when a check fails on the final screen | Answers are often read three screens earlier; apps word "sent" differently; a wrong check traps the model | Checks are looked for across the whole mission; one note, then a finish is always accepted as `unverified` (§5) |
| A stay closes on every package change | Share sheets, photo pickers, permission dialogs, Google sign-in and Custom Tabs are other packages; folding Instagram while picking a photo would lose the caption work | Transient surfaces never open a stay; hysteresis of 2 observations; a quick return re-opens the same stay (§3) |
| Old screens "compacted or scrapped" | A folded fact could be gone for good | Folded, never scrapped: `recall(turn/stay)` unfolds byte-exact (D10) |
| Hard module budgets | A long list or web page could be cut | Soft, prioritised budgets; the screen is never shorter than classic (§2) |
| `plan_update` refused without `why` | The model stops using the plan instead of adding a why | Never refused; `why` optional; no plan needed for short missions (§6) |
| Diversions detected by comparing plan text | Rewording a step would raise false "diverted" cards | Only model-declared, or seen at an approval that differs from what the owner named (§6) |
| "Needs your OK" diversion card that blocks the send | A second owner touch on top of the send approval; also fires when the owner left the choice open | The change is shown on the existing approval; nothing flagged when the owner left it open (§6) |
| "Trust the live state over older messages" | A wrong bookkeeping line would steer the model wrong | The screen is the truth; the live state helps (D11, legend) |
| Two surprises → "change approach" | A slow load or one-off dialog would push the model off a correct route | Advice ("consider another route"); the model decides (§4) |
| `✗` whenever the expectation isn't met | Fuzzy expectations would produce false misses | `✗` only when sure; everything else `?`; the false-miss rate is a Lab metric (§4) |
| Ambiguous person → always ask | Often the phone itself tells them apart | Use the phone first, ask only if still ambiguous (§8) |

**Escape hatches the model always has:**
- ignore every new argument and work as a classic run;
- `recall(turn=…)` / `recall(stay=…)` for anything folded;
- `screen_read`, `screen_look`, `screen_find` and `scroll` for more of the screen than the module shows;
- replan at any time; a plan is never required;
- finish with evidence even when a check could not be verified (recorded, not refused);
- the owner can switch the workspace off in Settings, and every mission falls back to classic for a turn if the
  workspace fails.

**Why agents will navigate this easily:** the model sees the same tools it knows, one screen in the same format as
today, and at the end three labelled blocks that answer the questions it would otherwise have to dig for (what is my
plan, what did I already get, where am I and how do I get back, what is this app like around here). Nothing in them
has to be obeyed. The Lab rules (§11) make "not more turns on `core`" and "≤2% false misses" hard conditions, so
structure that gets in the way cannot become the default.

## 16. As built (alpha.66: W1 + W2)

W1 (the workspace) and W2 (expect and done) shipped together as alpha.66, behind **Settings → AI → Mission workspace
(new)** (off) and the Lab `context` knob (absent = classic).

**Built:**
- `mind/workspace/`: `MissionWorkspace` (stays with surfaces and hysteresis, ledger, plan, done checks, diversions,
  surprises, folding with journal blocks, recall, live state, metrics, resume), `Surfaces`, `ScreenFacts`/`ScreenDelta`,
  `ExpectCheck` (conservative, whole-token handles), `DoneCheck`, `WorkspaceSpecs` (optional arguments only).
- `MindConversation.fold`/`unfoldedToolChars`/`toWire(native, tail)`; `MindLoop` (`workspace`, `workspaceChars`
  100k, the live state as the last message, `endTurn` folding, step advice, every call `guarded`); the workspace in
  the mission journal (redacted) for resume.
- `PhoneMindToolbox`: facts per observation, "Changed:"/"Check:" under screens, the app's section on every stay,
  manual lines following the plan step, `open_app(why, carry, resume)`, `recall(turn, stay)`, `note(key)` as ledger
  (secrets refused), `plan_update(app, why, done, divert)`, the done nudge in `task_finish`, the approval line and the
  sends it records, narration on the task card.
- `MindOwnerPort.diverted` (task card line + mission event); `MindPrompt.workspaceRules()`.
- Lab: `MindLabVariant.context`, gateway `CONTEXTS`, suites `multiapp` (6) and `long` (5), arm stats `workspace`
  (expect hit rate, stays, diversions, done) and `promptTokens`; Glass Lab toggle and arm line.

**Differences from the plan above:**
- **The screen stays in the newest tool result** (as before) instead of a rebuilt module 8; the live state follows it
  as the last message. The effect is the same (one full screen, the live state last) with no duplication.
- **The app section is part of the arrival screen's result**, so it folds with its stay; it returns on each new stay.
- **Diversions at approvals** ride on the existing card (§6 as revised); there is no separate card.

**Not yet (W3–W4 and small follow-ups):** People & notes, recipes and traps, prefetch, `cache_control` breakpoints,
a harness-drafted done check, and the stays timeline in Glass's run inspector (the record is already in the mission
metrics as `workspace`).

## 17. As built (alpha.67: W3 as memory v2)

The owner asked for memory that is "especially sensitive to people asking to remember something from the run" and
"doesn't become a mess", like ChatGPT and Claude, or an open-source project that has mastered it. W3 was built by
upgrading the Mind's memory rather than adding a second store, so there is one place for everything Cyclone keeps.

**The technique:** mem0 (Chhikara et al., 2025): each candidate memory is compared with similar existing ones and
becomes ADD, UPDATE, DELETE or NOOP. Cyclone does the comparison in code (person cards by name; word overlap for the
rest) and lets the running Mind be the judge where code can't: similar memories come back with the result, and the
Mind says `replaces=<id>` or calls `forget`. No second model call. Like ChatGPT's saved memories, every change is shown
("Memory updated") and the owner manages the list.

**Built:**
- `MindMemory` v2: kinds `person` / `preference` / `app` / `fact`, `source` (owner or learned), person cards with
  relation, handles per app and note, history of earlier versions, NOOP/UPDATE/ADD with similar ones returned,
  `replaces`, one-off values refused unless the owner asked, eviction of learned memories first, `peopleIn(goal)`, a
  grouped `digest(goal)`, sealing at rest (`KeystoreMemorySealer`, AES-GCM) with migration of the plain file.
- `RememberIntent` (English and Dutch; questions and reminders excluded), heard in the goal (mission brief line), in
  messages during the mission (a harness note) and in `owner_ask` answers; the one-time reminder at `task_finish`.
- `remember` gains optional `kind`, `person`, `relation`, `app`, `handle`, `replaces` (no new tool); people only when
  the owner named them; `MindOwnerPort.memoryUpdated` (task card line + mission event); the grouped Memory card.

**Differences from §8:** one encrypted store instead of a separate `OwnerNotes`; people the owner names in a task are
kept directly (the owner said it), people read on screens are refused with a hint to ask; lists (close friends) are
kept as notes on the person or app rather than a separate type. The resolver is `peopleIn` plus the digest order; the
handle check at send is the alpha.66 approval line.

