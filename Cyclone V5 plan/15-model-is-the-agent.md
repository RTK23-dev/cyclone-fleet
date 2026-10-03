# Cyclone Agent Runtime — the model is the agent

**Status:** proposed 2026-09-25. Supersedes `14-goal-first-core.md`, which still put rules around the model instead of
giving the model the job. Nothing below is built yet.

## The question

The same models that build whole programs from one prompt in Claude Code or Codex cannot set a timer inside Cyclone.
The models did not get dumber. Cyclone makes them dumber. This document says exactly how, and what replaces it.

## How Cyclone uses a frontier model today (verified in code)

| What a capable agent needs | What Cyclone gives the model | Where |
|---|---|---|
| **A continuous conversation**: its own earlier thinking, actions and results | Every call is a fresh two-message exchange: system prompt + one JSON blob. It never sees what it planned or saw before, only a list of action strings | `requestPageDecision` |
| **The whole job** | "You are Cyclone Page Agent… operates one semantic scene at a time… return a short plan for THIS SCENE only" | `PageAgentProtocol.SYSTEM_PROMPT` rules 1–3 |
| **Real capabilities** | 9 tap-level tools (click, type, scroll, back, home, open_app, http/https URL). No alarm/timer, no Play Store, no settings intents, no wait, no screenshot on its own initiative, no asking you a question, no notes or plan | schema in the prompt |
| **Time to think** | 8 s (EASY), 22 s otherwise, per decision; a reasoning model at 15–20 s is cut off and the task stops | `horizonBudget` |
| **Clear perception** | A sanitized control list; titles often `<redacted>`; screenshots discouraged unless the harness escalates | prompt rule 6, model context |
| **Authority over its own actions** | ~20 local shortcuts run first and take the turn (login autofill, cookie, landings, fast paths, Atlas, skills, graph…); in two alpha.23 runs the model was never asked | `planNext` |
| **A plain brief** | 15 rules of internal jargon (PC_AGENT_CONTEXT, operatingMode STRUCTURED/FREE, fingerprint settle…) and a strict JSON reply format | system prompt |
| **To judge its own result** | Keyword contracts decide "done"; the model's evidence is second | `GoalContract` |

Each patch in alpha.21–23 (clause regexes, capability enums, phrasing tables, login rules) added another rule around
the model. That was the wrong direction, including the plan in `14-goal-first-core.md`.

## Principle

**Cyclone is the harness; the model is the agent.** The harness owns the phone, safety, perception quality, reliable
execution, memory, time and evidence. The model owns understanding, planning, choosing actions, recovering and
deciding when it is done, as it does in Claude Code.

## Architecture

```
Owner's sentence
   │
   ▼
┌──────────────────────────── one task = one conversation ────────────────────────────┐
│ system: who you are, this phone (apps, locale, time), your tools, the safety rules  │
│ user:   "set a timer for 5 minutes"                                                 │
│ assistant: (thinks) plan → tool_call clock.set_timer {seconds: 300}                 │
│ tool:   result + settled screen: Clock · Timer · 4:59 · Pause                       │
│ assistant: finish {summary, evidence: "timer 4:59 running in Clock"}                │
└─────────────────────────────────────────────────────────────────────────────────────┘
   │ every tool call goes through                                   ▲
   ▼                                                                │ tool results
Cyclone harness: PhoneToolExecutor + GATE + approvals + Secrets Card + settle + proofs
```

### 1. One continuous conversation per task
- Native tool calling (OpenRouter `tools`), not strict JSON in text. Models that lack native tools use a JSON
  tool-call envelope as a fallback.
- The model sees its full history: its thinking summaries, every tool call, every result.
- **Context management** as in Claude Code: older screens compact into short summaries, so a 40-step task fits;
  secrets never enter the context.
- **Prompt caching** keeps the system prompt, tools and history cheap and fast on every turn.

### 2. A real toolset (what the phone can do, described like an API)

| Group | Tools |
|---|---|
| See | `screen.observe` (structured tree with stable ids), `screen.look` (screenshot, whenever the model wants), `screen.find(text/role)` |
| Act | `tap`, `long_press`, `type`, `scroll`, `swipe`, `back`, `home`, `wait_for(condition, max_s)` |
| Go | `apps.open`, `apps.list`, `web.open(url)`, `settings.open(page)` (Wi-Fi, Bluetooth, display…), `store.open(app)` |
| Do (system intents) | `clock.set_timer`, `clock.set_alarm`, `share.text`, `calendar.add` (later), `maps.navigate` |
| Know | `phone.info` (installed apps, locale, time, model), `atlas.route(app, room)`, `brain.recall(topic)`, `people.lookup(name)` |
| Ask | `owner.ask(question, choices?)`, `owner.approve(action)`, `vault.fill(field)` (Secrets Card; the value never returns to the model) |
| Track | `plan.update(steps)` (visible to the owner live, like a todo list), `facts.note(key, value)` (masked in reports) |
| Finish | `task.finish(summary, evidence)`, `task.give_up(reason)` |

Every "Act/Go/Do" tool returns the **settled** new screen, so the model always works from fresh state; this is the
alpha.23 settle controller doing its job underneath.

Today's shortcuts do not disappear; they become **tools the model chooses**: login autofill becomes `vault.fill`,
learned routes and Atlas doors become `atlas.route`, compiled skills become callable skills. Nothing takes a turn
away from the model.

### 3. Time that matches reasoning
- A task budget (e.g. 5–10 minutes, owner-adjustable), not an 8–22 s cut-off per thought. A single model call may think
  as long as it needs up to a generous ceiling (e.g. 120 s); streaming shows "thinking…" and the current plan step.
- Latency is attacked where it belongs: prompt caching, fewer turns (the model can issue several safe actions in one
  turn when it predicts the screens), and a direct system intent instead of ten taps.
- The owner sees progress continuously: the plan, the step in progress, the last result.

### 4. Safety stays in the harness, not in the prompt
- `PhoneToolExecutor` remains the only mutation engine; GATE, approval boundaries (pay/send/delete/permission/
  authentication/account creation/install) and the one-prompt rule are enforced on tool calls, whatever the model
  intends.
- Screen text is wrapped as untrusted data in tool results; instructions found on screens are never followed.
- Credentials only through `vault.fill` (Secrets Card); the model never receives them.

### 5. Verification: the model proves, the harness checks
- `task.finish` must cite evidence from the current screen or facts. Typed validators (timer running, alarm enabled,
  signed-in email, connected network) re-check claims where they exist and reject false ones with the reason, so the
  model can continue.
- Keyword contracts are no longer the judge.

### 6. Memory across tasks
- The Atlas, Brain and people memory are what the model reads through `atlas.route` / `brain.recall` /
  `people.lookup`, and what the harness writes after verified tasks, the way a person learns an app. They inform; they
  never act on their own.

### 7. Evaluation like a studio, not a phrasing table
- A task suite (100+ real tasks: timers, installs, messages, sign-ups, settings, multi-app) run on an emulator farm and
  the owner's phone, graded by outcome (was the timer running, was the app installed), with cost and time per task.
- Every model/prompt/harness change is measured against it before release; regressions block the release.
- No hand-written example lists teach the model language; the suite measures whether it understood.

## What is retired

| Retired | Why |
|---|---|
| `planNext` shortcut chain and STRUCTURED/FREE modes | the model decides; shortcuts become tools |
| `PageAgentProtocol` scene-only JSON protocol | replaced by a conversation with native tools |
| Clause compiler regexes, capability enums, phrasing tables | the model plans (`plan.update`); facts via `facts.note` |
| Keyword goal contracts as completion judge | evidence-based `task.finish` + typed validators |
| Login takeover prompt | `vault.fill` / `owner.ask` |

## What is kept (and becomes more valuable)

`PhoneToolExecutor` and GATE, settle controller and deferred proof, typed proofs, Secrets Card and Vault, Atlas and
learned routes (as knowledge), Glass (plan/steps/evidence view), run diagnostics, the terminal command.

## Build plan

| Step | Deliverable | Done when |
|---|---|---|
| R1 | Agent loop: one conversation per task, native tool calling via OpenRouter, context compaction, streaming progress, task budget | a task runs end to end with the See/Act/Go tools only |
| R2 | Toolset: system intents (clock, store, settings, web), `owner.ask/approve`, `vault.fill`, `plan.update`, `facts.note`, `task.finish` with validators | timer, alarm, install, Wi-Fi and the Gmail→Facebook sign-up (to the approval stop) run on the emulator |
| R3 | Knowledge as tools: `atlas.route`, `brain.recall`, `people.lookup`, skills | mapped apps finish in fewer turns, measured |
| R4 | Eval harness: task suite + outcome graders on an emulator in CI; cost/time per task | baseline recorded; release gate wired |
| R5 | Switch Ask to the new runtime behind a setting, old path kept one release for comparison; Glass shows the live plan | the owner's phone runs the suite; old path removed after |

## Risks

- **Cost and latency per task**: more context per turn. Mitigation: caching, compaction, system intents that finish
  tasks in one call, a faster model for simple tasks chosen by the owner.
- **Model variety**: tool-calling quality differs by model; the eval suite ranks models on the owner's tasks.
- **Prompt injection from screens**: data wrapping plus harness-side GATE; tested in the suite.
