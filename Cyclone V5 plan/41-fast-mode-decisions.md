# 41 — Fast mode: decision models for very fast runs

**Written:** 2026-09-29, at 5.0.0-alpha.75, the day OpenAI announced its Decisions API. **Status:** the parallel Pilot
is built in alpha.76, behind Fast mode (off by default); see §14. Watch mode, the ledger (F0) and Builds A and C are
plans.
**Owner's brief:** use ChatGPT's decisions AI, images included, to make Cyclone runs very fast. It becomes Cyclone's
**Fast mode**, configured in Settings. Compare three builds and pick the best structure, using everything the One Mind
taught us about reliability.

## 0. The answer in short

- **Build B, "Pilot"** is the right structure, built in phases:
  - the Mind (the full model) keeps the goal, the plan and every judgement;
  - a decision model answers one small question per step ("which control serves this step?", "did it work?") in
    about 0.15 s;
  - the Mind is called back the moment anything is unsure, surprising or needs the owner.
- **It stands on the live screen ledger** (the "what changed" report ranked first earlier today). Without it, the fast
  lane can't tell whether a step worked, and that is where fast systems fail.
- **It ships watch-only first,** like JEV in Drive: the decision model answers next to the Mind, changes nothing, and
  earns each question type with measured numbers before it may act.
- **Build A ("Referee")** is Pilot's first phase, not a competitor. **Build C ("Routes and forks")** comes after, once
  recipes are common, for runs Cyclone has done before.
- **Expected speed:** estimated 3–5× on routine stretches. The estimate is explained in §2; the Lab measures it (§9)
  before any default changes.

## 1. What a decision model is, and what Cyclone already has

### 1.1 OpenAI's Decisions API (announced 2026-09-29)

**What is reported:**
- It **picks, it doesn't write.** You send context (text or images) and questions whose answers you define in
  advance. It returns the most likely answer and a confidence.
- **It is fast:** built on a specialised GPT-6 Luna, about **150 ms** per answer, against about 1.6 s for an ordinary
  Luna call.
- **It takes images.** TypeSafe's JEV, the model it answers, does not.
- **Suggested uses:** classify content, route requests, and decide an agent's next step.
- **Status:** limited preview; broad release "in the coming days".

**Not known yet:**
- the endpoint, the request and response shapes, and the model ids;
- limits (how many choices, image sizes, rate limits);
- the price and any accuracy figures;
- whether OpenRouter will carry it.

**One report says its confidence is self-reported, not calibrated.** Treat every confidence as a hint until our own
data calibrates it (§9).

**Reading note:** the network used for this plan blocks openai.com and most news sites, so these facts come from
search summaries of the launch coverage (sources in §13), not the primary pages. Recheck them against the official
documentation when it is published.

### 1.2 What Cyclone already has

- **A decision lane, watch-only:** `voice/JevShadow.kt` (alpha.52). For every Drive request, JEV is asked "what kind
  of request is this?" next to the understanding model.
  - The request is typed: `state` plus `questions`, each a `choice` with `instructions` and `choices`.
  - The answer reader is tolerant: the alpha API has returned `answers`, `decisions` or bare keys.
  - The tally is measured: agreement, median time, and agreement when sure (confidence ≥ 0.8).
- **The endpoint:** JEV is called through OpenRouter's alpha decisions endpoint
  (`https://openrouter.ai/api/alpha/decisions`, `voice/OpenRouterVoice.decide`) with the owner's existing OpenRouter
  key. If OpenAI's Decisions appears there, Fast mode needs no second key.
- **The decision shapes are already known.** Plan 36 §7.5 planned JEV for typed choices in the App Manual (organizer
  decisions, ability picks) with the same promotion rule.
- **The owner parked JEV on 2026-09-28.** This plan reopens the lane for screen decisions only on the owner's say-so
  (§12).

## 2. Where the time goes in a run today

One step of the One Mind (plan 16; `MindLoop`, `PhoneMindToolbox`):

| Part | Today | Note |
|---|---|---|
| The model's decision | **Several seconds; 14–20 s on the reasoning route of alpha.23** (plan 13 R6) | A full conversation call, not streamed, one screen-changing action per turn |
| The action | Tens of ms to ~0.5 s | `PhoneToolExecutor` |
| Settle | 0.3 s, then +0.5 s / +1.0 s | Fixed Fast Path timers |
| Reading the screen | ~0.1–0.5 s | Accessibility tree → `MindScreen.render` (≤120 controls, ≤3,500 chars of text) |
| Screenshot (when asked) | ~0.3–1 s, plus ~1k image tokens | Marked, ≤1,280 px, only the latest one kept |

**The model's decision is the bottleneck.** Every tap, even an obvious one ("tap Lou in the chat list"), costs a full
decision.

**The Pilot's arithmetic** (estimates, to be measured):
- Per fast step: decision 0.15 s + network ~0.2–0.5 s from the phone + action and settle ~0.4–1.5 s → **about 1–2 s**.
- A 10-step run where the Mind takes 2 turns (plan, one surprise) and the Pilot takes 8 steps: about 2 × 6 s + 8 × 1.5 s
  ≈ **25 s**, against about 10 × 6 s + settles ≈ **70 s** today.
- That is where "3–5× on routine stretches" comes from.

## 3. The lessons Fast mode must keep

Every one of these was paid for in a real run.

| # | Lesson | Where it was learned | What it means for Fast mode |
|---|---|---|---|
| L1 | **Shortcuts that act on the screen before anything reads the goal hijack runs.** A leftover login page took over "set a timer" three times; the model was asked zero times. | Plan 14 (alpha.23 runs), plan 15 | A fast decider never acts on the screen alone. Every fast decision carries the goal and the Mind's current step, and the Mind owns the plan |
| L2 | **The harness is the harness; the model is the agent.** | Plan 15 | Fast mode is a faster way to carry out the Mind's steps, not a second agent with its own ideas |
| L3 | **One conversation is the mission's memory.** | Plans 16 and 37 (D1) | Fast steps are written back into the Mind's conversation as compact lines, so its line of thought continues |
| L4 | **Keyword "done" checks lie** (any two goal words passed "alarm set"). | Plan 13 R3 | A decision model never declares a mission done. `task_finish` stays with the Mind and its evidence |
| L5 | **Checks must be conservative:** `✗` only when sure; a false miss pushes the model off a correct path. | Plan 37 §4 | Fast "did it work?" answers below the sureness bar count as "unsure", which means ask the Mind, never "failed" |
| L6 | **The screen is the truth.** | Plan 37 (D11) | Every fast decision is made on a fresh observation; nothing acts on a remembered screen |
| L7 | **A missing after-state is "not settled yet", not failure.** | Plan 13 R1–R2 | Fast mode waits on the ledger's event settle, never on a guess |
| L8 | **Guidance, not gates; no new tools; a model that ignores the structure still works.** | Plan 37 (D8, D9, §15) | The Mind gets no new tools. Handing steps to the Pilot is an optional argument on existing ones; Fast mode off is exactly today |
| L9 | **Direct first, then the screen.** Most tasks need no screen at all (intents, providers). | Plan 29 | Fast mode speeds up screen work only. Direct abilities and verified `go_to` routes stay first |
| L10 | **Watch, measure, then promote.** JEV watches Drive; the workspace was a Lab variant until it won. | Plans 32, 36, 37 (D6) | Every question type starts watch-only and is promoted per type by the numbers (§9) |
| L11 | **Boundaries are unchanged.** PhoneToolExecutor only; one screen-changing action per decision turn; approvals for pay, send, delete, permission and sign-in; no secrets anywhere. | AGENTS.md, plan 16 | A fast decision is a decision turn (one mutation). Gated actions raise the same approval card. The Pilot can't type into secret fields or run `vault_fill` |
| L12 | **Privacy of evidence:** no screenshots, reasoning or secrets in journals; redaction must not mangle the evidence. | Plan 16, plan 13 R7 | Images go to the decision model only through the privacy gate (§8) and are never stored |
| L13 | **Unstable targets must not be retried blindly** (ambiguous chips tried three times). | Plan 14 cause 5 | An ambiguous pick (two close choices) is "unsure" → the Mind. The same pick is never retried twice |
| L14 | **Diagnostics must say who decided.** | Plan 14 cause 8 | Every step records "Mind" or "Fast (model, confidence)" in the run record and on the run card |

## 4. The questions a decision model may answer

Fast mode is a small catalogue of typed questions. Each has a fixed answer set, always including an "unsure" way out.

| Q | Question | Answers | Context sent | Images |
|---|---|---|---|---|
| **Q1 Pick** | Which control carries out this step? | Up to ~12 shortlisted refs (`e4 "Lou" · list item`), plus `scroll_down`, `scroll_up`, `back`, `not_here`, `ask_mind` | goal, step, `expect`, the rendered screen, the shortlist | When accessibility is weak or the labels are icons (§8) |
| **Q2 Check** | Did the last action do what the step expected? | `worked`, `nothing_changed`, `dialog`, `login_wall`, `loading`, `error_shown`, `other_screen`, `unsure` | step, `expect`, the ledger's change report, the new screen | Only after `nothing_changed` from the tree |
| **Q3 Dialog** | A dialog or sheet appeared. What serves the step? | `dismiss` (safe close/cancel), `accept` (only when it is not a gated kind), `ask_mind` | step, dialog text, its controls | No |
| **Q4 Which one** | Several results match; which is the one the goal means? | the candidates (`Lou (lo.06)`, `Lou Parker`, …), `none`, `ask_mind` | goal, the person or place named, candidates with their visible details | Profile pictures only with the owner's image setting on |
| **Q5 Where am I** | Is this screen on the plan's route? | `on_route`, `off_route`, `blocked` (login, captcha, permission), `unsure` | plan, step, app, title, screen summary | Optional |
| **Q6 Privacy gate** | Does this screen show a password, one-time code, card or banking screen? | `clear`, `sensitive` | the tree summary (no image) | **Never** (the gate decides whether an image may be sent at all) |

**Never asked of a decision model** (these stay with code or the Mind):
- whether an action needs the owner's approval (fixed rules in code);
- whether the mission is done (the Mind, with evidence; L4);
- what to type: text comes from the Mind's step or the owner, never a decision model;
- anything about secrets; `vault_fill` is the Mind's call and the owner's hand;
- replanning. A decision model can only say `ask_mind`.

## 5. Three builds

### Build A — Referee (decisions check, the Mind still acts)

**How:** the Mind decides every action, as today. The decision model only answers Q2, Q3 and Q6:
- Q2 replaces the Mind's own "did that work?" reasoning on each new screen;
- Q3 closes routine dialogs without a model turn;
- Q6 gates screenshots.

**Speed:**
- Small gains: some turns get shorter (the check is given, not reasoned) and dialog turns are skipped.
- Estimated 10–25%. The Mind's decision per action remains the bottleneck.

**Reliability:**
- Highest of the three; nothing new acts except Q3's dismiss.
- The check line helps the Mind: L5 in a faster form.

**Cost:** small. Mostly the ledger plus a `FastDecider` port.

**Verdict:** a safe first phase. On its own it doesn't deliver "very fast".

### Build B — Pilot (the Mind plans, decisions carry out the steps)

**How:**
1. The Mind works as today. When it knows the next few steps, its existing `plan_update` (plan 37) or any
   screen-changing call may carry an optional `fast: true` on the steps it is sure about. Each step has an `expect`
   ("chat with Lou opens").
2. For each fast step, the Pilot loops:
   - asks Q1 on the fresh screen;
   - if the answer is sure (above the per-question bar, §9) and is not `ask_mind`, `not_here` or ambiguous, carries out
     exactly **one** action through `PhoneToolExecutor` (a decision turn, L11);
   - waits on the ledger's event settle (L7);
   - asks Q2 on the change report.
3. `worked` moves to the next step. Anything else goes straight back to the Mind: `unsure`, `dialog` (after one Q3 try),
   `login_wall`, `error_shown`, `other_screen`, a gated action, a secret field, or two misses on a step.
4. The Mind gets one compact message:

   ```
   Fast steps 3–5: ✓ tapped e4 "Lou" (0.93) → chat opened; ✓ tapped e9 "Message"; ✗ step 6: a dialog "Turn on
   notifications?" appeared — your call.
   ```

   It continues from the current screen (L3, L6).

**Typing:** a step like "type the ETA" is carried out only when the Mind wrote the exact text in the step. The Pilot
never composes text. Sends stay gated with the exact-text approval (plan 32/37).

**Speed:** the largest. Routine stretches run at ~1–2 s per step; the Mind is asked at the start and at surprises.
Estimated 3–5× on most missions (§2).

**Reliability risks, and their answers:**
- **A confident wrong pick** (the worst case). Answers:
  - Q1 shortlists only controls that match the step's words or map entries;
  - ambiguity (two choices close in confidence) counts as unsure;
  - Q2 checks every step against `expect`;
  - a miss goes back to the Mind before a second action;
  - per-app demotion: two wrong picks in an app turn Fast off for that app for the run.
- **Drift** (the Pilot follows a stale plan). Answers: Q5 on every new app or screen type, and `fast` steps expire
  when the app changes unexpectedly.
- **Mind continuity** (it loses track of what happened). Answer: the compact record above, and `recall` (plan 37 D10)
  of the full fast steps.

**Cost:** medium. It needs the ledger, the `FastDecider` port, the Pilot loop, the shortlist, one optional argument,
the record lines, the settings and the Lab metrics.

**Verdict:** the best balance of speed and reliability. It keeps every lesson: goal-first, the Mind owns the plan and
the finish, one action per decision, conservative checks, and a way back at every step.

### Build C — Routes and forks (known routes, decisions only at the forks)

**How:**
- For goals that match a verified recipe (plan 37 §9) or a manual ability with a verified path (`go_to`, plan 36),
  Cyclone walks the route in plain code.
- The decision model answers only the forks: "which of these screens are we on?" (Q5), "a dialog: dismiss or ask?"
  (Q3), and "which result is Lou?" (Q4).
- The Mind is asked only when there is no route, or a fork is unsure.

**Speed:** the fastest on repeated tasks: routes need almost no model at all. No gain on new tasks.

**Reliability risks:**
- This is the shape that failed in alpha.23: route-first shortcuts that act on the screen (L1).
- It is safe only if the **goal picks the route** (the Mind or a recipe match on the goal, never the screen), and a
  route step that doesn't match its expected screen stops at once.
- Recipes go stale when apps update (plan 36 §7.6), so the recipe's own checks must be strict.

**Cost:** high for coverage: it is only as good as the number of verified routes and recipes, which grow slowly.

**Verdict:** a later speed layer for repeat tasks, fed by recipes. Not the foundation.

## 6. Comparison

| | A Referee | **B Pilot** | C Routes and forks |
|---|---|---|---|
| Speed on new tasks | 1.1–1.25× | **3–5×** (routine stretches) | ~1× (no route) |
| Speed on repeat tasks | 1.1–1.25× | 3–5× | **5–10×** where a route exists |
| Risk of a wrong action | Lowest | Low–medium, bounded per step | Medium; the alpha.23 shape if done wrong |
| Lessons at risk | none | L1, L13 (handled by design) | L1, L6, L9 |
| Works on apps never seen | Yes | Yes | No |
| Build size | Small | Medium | Large (coverage) |
| Needs the ledger | Yes | Yes | Yes |

(Speed figures are estimates until the Lab measures them.)

**Recommendation:** build **B**, in the order A → B, and add **C** later as recipes grow.
- A is B's first half (the Check and the Dialog questions);
- C reuses B's questions at the forks;
- nothing is thrown away.

## 7. Fast mode in Settings

Settings → Model & intelligence → **Fast mode**:

| Setting | Choices | Default |
|---|---|---|
| **Fast mode** | Off · Watch · On | **Watch** when a decision model is available, else Off |
| **Decision model** | OpenAI Decisions (when available) · JEV · A fast standard model | The best available. A standard model is labelled honestly as "a fast standard model", never as "Decisions" |
| **Screenshots for fast decisions** | Never · When needed · Every step | **When needed** (§8) |
| **How sure before acting** | Careful · Balanced · Quick | **Careful**. Each level shows the measured rate from the owner's own runs ("Careful: 98% right, 2.9× faster") |
| **Keep Fast off in** | a list of apps | Banking, payment and authenticator apps pre-listed |

**Always true, whatever the settings:**
- the full Mind takes over when unsure;
- approvals are unchanged;
- Fast mode never types secrets and never finishes a mission.

**Watch** runs the decision model next to the Mind, changes nothing, and fills in the numbers.

**The run card** shows the split and the time: "14 steps fast · 2 by the Mind · 41 s". Each step says who decided (L14).

**Keys:** OpenRouter first (one key, as JEV today). A separate OpenAI key only if Decisions stays off OpenRouter. It
is stored in the same encrypted secret store, and Carry never moves it (plan 40).

## 8. Images

**When one is sent:**
- the tree is weak (canvas, game, some web views);
- Q1's shortlisted labels are icons or empty;
- Q2's tree says `nothing_changed` (the picture may show what the tree missed);
- Q4 needs faces or thumbnails, and the owner turned images on.

**How:**
- the same marked screenshot the Mind gets (refs drawn on);
- or a crop around the shortlist, which is smaller and faster;
- scaled to the decision model's documented size (unknown yet; ≤1,280 px today).

**The privacy gate, before any image:**
1. **Code first:**
   - never a screen with a password, code or card field in the tree;
   - never a screen from the "Keep Fast off" apps;
   - screens marked secure capture black anyway.
2. **Then Q6** on the tree text as a second layer. Code rules can't be overruled by a model's `clear`.

**Never stored:** images are sent, used and dropped. They are not in journals, run records or Glass (L12).

## 9. How we prove it

**Watch first (per question type).** For every step the Mind takes, the decision model answers the matching question
alongside, and the harness compares:
- **Q1:** did the decision model pick the control the Mind picked?
- **Q2:** did its verdict match the Mind's next move? A Mind that carries on counts as `worked`; a retry or a different
  route means it didn't.
- **Q3–Q5:** agreement with the Mind's own next action.

**Metrics** (Lab suites of plan 18, and the owner's runs in Settings):
- agreement overall and **when sure**, at each sureness level;
- time per answer (median and 90th percentile);
- how often each answer is "unsure";
- in On mode: fast steps per run, Mind turns per run, wall time per run, wrong fast actions (a Q2 miss after a sure
  Q1), and Mind take-overs.

**Promotion rules:**

| From → to | Condition |
|---|---|
| Watch → On, per question type | ≥ 98% agreement when sure on ≥ 200 samples across ≥ 10 apps; median ≤ 0.5 s from the phone |
| On (Careful) as the default | Lab `core` suite: success not lower than the Mind alone; wall time ≥ 2× faster; wrong fast actions ≤ 1 per 100 steps |
| Automatic step-down | Two wrong picks in an app in a run turn Fast off for that app for the run; a wrong-action rate above 2% in a week steps the sureness level up and tells the owner |

**Calibration:** the provider's confidence is only an input. The bar per question is set from our own agreement data
(the sureness at which agreement reaches 98%), because the reported confidence may not be calibrated (§1.1).

## 10. Build phases

| Phase | Delivers | Needs |
|---|---|---|
| **F0: the ledger** | Event settle, the change report, kept transients (toasts, snackbars): the foundation of every build | Nothing new |
| **F1: the port + Watch** | A `FastDecider` port with adapters (OpenRouter decisions for JEV and, when available, OpenAI Decisions; a fast standard model; a local fake for tests); Q1–Q6 as pure question builders and tolerant readers; Watch mode and its tally; the Fast mode settings | F0; the official Decisions docs for that adapter |
| **F2: Referee (A)** | Q2 check lines for the Mind; Q3 dialogs; Q6 privacy gate for screenshots | F1 numbers for Q2, Q3, Q6 |
| **F3: Pilot (B)** | `fast` steps on `plan_update` and screen-changing calls; the Pilot loop; the Mind record lines; the run card split; the demotion rules | F1 numbers for Q1; F2 live |
| **F4: Routes and forks (C)** | Recipe and ability routes walked in code with Q3–Q5 at forks, only when the goal picked the route | F3; plan 37 W4 recipes |

**Each phase:**
- is behind a switch and is a Lab variant first;
- adds guards in `scripts/ci/tests`:
  - no decision-model path to approvals or `task_finish`;
  - no image without the privacy gate;
  - no secret fields;
  - every fast action goes through `PhoneToolExecutor`;
- reports physical checks as UNVERIFIED until the owner tests them.

## 11. Risks

- **The API changes or stays in preview.** Behind the port, Fast mode works with JEV or a fast standard model, and the
  answer reader stays tolerant (as `JevShadow.parse`).
- **Price.** Unknown. The run card shows the cost per run; Watch mode costs the decision calls with no benefit, so it
  is capped to a sample rate once numbers are in.
- **Latency from the phone** is higher than the quoted 150 ms (network, image upload). Measure it in F1 before
  promising anything.
- **Too many choices.** The limit on answer options is unknown. The shortlist keeps Q1 at ≤12 and falls back to
  `ask_mind`.
- **Over-trust.** A fast wrong action in the wrong app can cost more than the time saved. Hence the "Keep Fast off"
  apps, Careful by default, and the step-down rules.

## 12. Owner decisions

1. **Reopen the decision-model lane** (JEV was parked on 2026-09-28) for screen questions, starting with Watch.
   Recommended: yes, Watch only.
2. **The decision model:** OpenAI Decisions when documented, JEV meanwhile, or both in Watch to compare. Recommended:
   both in Watch.
3. **Default screenshots:** When needed (recommended) or Never.
4. **Default "Keep Fast off" apps:** banking, payment and authenticators (recommended), plus any the owner adds.
5. **Order:** F0 (the ledger) next, then F1. Recommended: yes; F0 helps every run even with Fast mode off.

## 14. As built (alpha.76): the parallel Pilot

**The owner's direction (2026-09-29):**
- The Pilot, with the fallback to the smart model decided by the rapid model itself.
- Then the parallel version: the rapid model makes most low-risk decisions itself; only irreversible ones ask a human.
- It uses tool calls when a step needs them. "A rapid yes/no decision board".
- It starts from a full run plan by the smart model, and bumps the smart model where the screen doesn't match.

Three versions were compared visually (Step, Goal, Parallel). The Step Pilot was built first (a5547bbb); the parallel
Pilot is built on it and is what alpha.76 ships.

**How it works** (`mind/pilot/Pilot.kt`, pure plus one look-ahead thread; `PhoneMindToolbox.pilotRun`):
- **The plan.** The Mind's `pilot` tool takes the whole run: 1–20 steps, each
  `{do, expect, text, app, link, risk: irreversible}`. `MindPrompt.PILOT_RULES` asks for a full plan and uses the
  Instagram message as the example. The tool is offered only when Fast mode is on.
- **The decision board.** One rapid call per move answers three things:
  - `fits_plan` (yes/no);
  - `needs_smart` (yes/no);
  - the move. The moves are ≤12 shortlisted controls (never secret fields; a text field gets the step's exact
    `text`), `step_done`, `open_app` and `open_link` (only when the step names them), `press_enter` (only after
    typing), `scroll_down`, `scroll_up`, `back`, `wait`, and `hand_back` with a reason.
- **Bumps.** A board "no fit" or "needs smart", `hand_back`, below the sureness bar, no or invalid answer, a repeat,
  or too many moves asks the smart model's side channel (`MindPilotAdvisor`). That is the mission's own model with a
  small no-tools request and a JSON verdict:
  - `revise` replaces the plan from the current step, and the run carries on;
  - `ok` carries on (or, for a doubt the rapid model can't resolve, hands the step to the Mind);
  - `return` hands the step to the Mind.
  At most 3 bumps per run. Hard problems (needs the owner, sensitive screen, secret text, a refused move, stopped)
  never bump; they go to the Mind.
- **In parallel.** The look-ahead starts when the run enters a new app, and at a step with an irreversible step
  within the next three. It runs on a background thread while the rapid model keeps moving. A verdict that arrives is
  applied at the next move. A planned irreversible move waits (up to 30 s) for a pending review.
- **Risk is code.** `Pilot.irreversible` (send, pay, delete, post, confirm, … in English and Dutch) and Enter
  outside a search field count as irreversible:
  - unplanned: bump the smart model to confirm, or hand back when there is no side channel;
  - planned: it goes through `act`, so the owner's approval asks as always.
- **The Mind receives** the record (moves with confidence and time, bumps, revisions), who handed back and why, the
  steps left, and the real screen.
- **Settings:** Model & intelligence → Fast mode (off by default): route, model, how sure, screenshots, and "Smart
  model checks ahead" (on).

**Tests:**
- `PilotTest` (21) and `PilotToolboxTest` (7);
- `test_pilot_guard.py`: moves only through `act`; sensitive checks before questions; unplanned irreversible moves
  never happen; a planned one waits for the review; hard problems skip the side channel; tool moves use the plan's
  own app and link; no finishing, secrets or approvals decided by the Pilot;
- the workspace guard counts 40 specs.

**Not yet:**
- Watch mode and its tally;
- the ledger's event settle (F0);
- the run card split, and Lab suites and promotion;
- OpenAI Decisions itself (no public contract yet).

Side-channel calls are not counted in the mission's usage. Physical use is UNVERIFIED.

## 15. Frozen: OpenAI Decisions; JEV connected (alpha.78, the owner's call of 2026-09-30)

OpenAI's Decisions API is not on OpenRouter yet and has no public contract to build against. The owner froze it.
Until it is available, **JEV answers every Cyclone decision, text only**. This is an emergency build, shaped so that
OpenAI Decisions replaces it in one small update.

**The port:** `mind/decide/Decisions.kt`
- **`DecisionProvider`:**
  - `JEV`: live. Model `~typesafe/jev-latest`, OpenRouter's decisions endpoint, `vision = false`.
  - `OPENAI_DECISIONS`: frozen. `live = false`, no endpoint or model yet, `vision = true`.
- **`Decisions.ACTIVE`:** the one switch. `Decisions.active()` falls back to JEV while the chosen provider is frozen or
  has no endpoint or model.
- **`ProviderDecisionBox`:** the modes router's decision box (Speed → Auto, Instant's follow-up controls). A provider
  without vision never gets a screenshot, and `DecisionBox.sees` tells callers not to take one: capturing it costs
  time for nothing.
- **`Decisions.post`:** the one HTTP call for decisions (short deadline, nothing logged). The Pilot's "Decision model"
  route uses it too, with the active provider's model; that model is no longer a free text field in Settings.
- **Drive's JEV watch** (`voice/JevShadow`, `voice/OpenRouterVoice.decide`) keeps its own call, because voice may not
  import mind code. It only watches and never decides.

**What JEV can't do, and what happens instead:**
- **No screenshots.** An icon-only control (a camera shutter with no name) is picked from the labels only; when that
  isn't enough, Instant hands the run up to Flash or the Mind.
- **The Pilot on the decision route** gets text only (its screenshot switch applies only to a provider with vision).

**Switching to OpenAI Decisions** (one small update, when it is on OpenRouter or its official docs are out):
1. **Read the official contract:** the endpoint, the model id, the request shape (state plus typed questions), the
   answer shape, image input, the limits and the price.
2. **In `DecisionProvider.OPENAI_DECISIONS`:** set `model`, `endpoint` and `live = true`, and set `vision` to what the
   docs say.
3. **Only if its request or answer differs** from the decisions shape: adapt `BoxWire.decisionsBody` /
   `PilotWire.decisionsBody` and the tolerant readers (`BoxWire.parseDecisions`, `PilotWire.parseDecisions`). Add a
   test with a real example answer from the docs.
4. **If it needs its own key** (not OpenRouter): a key store beside `OpenRouterSecretStore`, used only by
   `Decisions.post` for that provider. Never logged.
5. **Measure first:** set `ACTIVE` only after a Lab comparison of OpenAI Decisions against JEV on the modes and Pilot
   questions (watch-only first, as JEV was): answer rate, agreement with the Mind, confidence calibration and time.
6. **Update:** `DecisionsTest`, `scripts/ci/tests/test_decisions_guard.py` (it pins `ACTIVE = JEV` and the frozen
   entry today), the Settings copy ("JEV … OpenAI Decisions replaces it"), the release notes and this section.

## 13. Sources

- [DevDay 2026 Recap (OpenAI)](https://openai.com/index/devday-2026-recap/)
- [OpenAI answers TypeSafe's Jev with a Decision API built on Luna (The New Stack)](https://thenewstack.io/openai-decision-api-luna/)
- [OpenAI expands Codex and its API at DevDay… a Decisions API (The Decoder)](https://the-decoder.com/openai-expands-codex-and-its-api-at-devday-with-security-scans-a-decisions-api-and-ultrafast/)
- [OpenAI launches Decisions API to take on Jev](https://pasqualepillitteri.it/en/news/19372/openai-decisions-api-jev)
- [OpenAI Decisions API Limits the Output Space Itself (FourWeekMBA)](https://fourweekmba.com/ai-openai-decisions-api-finite-output-space/)
- [OpenAI DevDay 2026: every announcement, with prices (dev.to)](https://dev.to/axrisi/openai-devday-2026-every-announcement-with-prices-and-availability-1mbh)
- [OpenAI Developers on X](https://x.com/OpenAIDevs/status/2105003318917697873)
- [req_llm issue #1062: waiting for the official Decisions contract](https://github.com/agentjido/req_llm/issues/1062)
- [GPT-6 Luna on OpenRouter](https://openrouter.ai/openai/gpt-6-luna)
- In the repository: plans 13, 14, 15, 16, 29, 32, 36, 37; `voice/JevShadow.kt`; `voice/OpenRouterVoice.kt`;
  `mind/MindLoop.kt`; `mind/PhoneMindToolbox.kt`; `mind/MindScreen.kt`; `mind/MindConversation.kt`.
