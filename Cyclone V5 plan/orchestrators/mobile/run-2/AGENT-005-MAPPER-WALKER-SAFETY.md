# RUN 2 — AGENT 005 — SAFE MAPPER WALKER

**Read first:** `RUN-2-SHARED.md`  
**Branch (issued now):** `v5/mobile/mapper-walker-safety`  
**PR target:** `v5/integration`

You own the Android **mapping decision/walk engine**, not the gateway control plane.

## Mission

Implement “Mapping is Follow Me with the agent holding the phone” while preserving Cyclone physics:

```text
fresh observe
→ classify structural room / candidate doors
→ consult Atlas hint
→ choose exactly one safe unexplored action
→ PhoneToolExecutor
→ settle
→ fresh observe
→ verify
→ write mapping-persona Atlas
→ update session progress
```

The mapper explores doors, not content.

## Owned paths

- new `apps/mobile/**/mapping/crawl/**`
- mapper-specific tests
- minimal call sites needed to consume Agent 004 session and existing Atlas/Vault APIs

Do not register gateway ops. Do not rewrite AtlasStore.

## Required behavior

### 1. Observation first

No mutation may be selected without a current observation for the exact bound session/display.

After every screen-changing action:
- wait/settle using existing runtime conventions;
- re-observe;
- verify state change or meaningful structural progress.

Unchanged screen is not permission to click again blindly.

### 2. Structural exploration

Prioritize:

- tabs;
- menus;
- navigation drawers;
- account/settings surfaces;
- search entry surfaces;
- safe app-level rooms.

Avoid walking individual content rows repeatedly.

Examples:
- learn “thread screen” from one safe sample if necessary;
- do not open 400 messages;
- do not enumerate a person's conversations into Atlas.

### 3. Safety

Before mutation, classify danger using existing safety/GATE semantics.

Autonomous mapper must never approve:
- payment/subscription/purchase;
- send/post/public action;
- delete/destructive action;
- logout-all;
- permission grant requiring GRANT.

Mark danger / dark door and choose another safe door or stop.

### 4. Secrets

On a real credential wall:
- signal Agent 004 session as `needs-secret`;
- invoke the existing Run-1 phone card flow through its narrow seam;
- perform no other screen mutation until resolution;
- after verified fill, obtain a fresh observation before the next decision.

The mapper never sees plaintext.

### 5. Human handoff

If control changes to HUMAN/companion:
- immediately stop autonomous mutation;
- report human-control;
- preserve map progress;
- resume only after Agent 004 authority says the job owns input again and after a fresh observation.

### 6. Budget / convergence

Honor Agent 004's job budget.

Stop safely on:
- max new screens;
- max elapsed time;
- repeated no-progress;
- door retry limit;
- no safe unexplored doors.

Coverage may remain `partial`. Never infer `mapped` merely from termination.

### 7. Atlas writes

Autonomous crawl defaults to `persona=mapping`.

Write only structural data:
- screen purpose/category;
- safe capabilities;
- doors/edges;
- danger;
- confidence/evidence;
- fact-slot definitions where structurally justified.

Do not persist dynamic labels/person names/content.

Do not execute saved Atlas paths as macros.

### 8. Tests

Required deterministic tests:

1. every chosen mutation has a preceding fresh observation;
2. exactly one screen-changing mutation per decision;
3. next decision waits for after-observation;
4. payment candidate is never clicked;
5. send-public candidate is never clicked;
6. delete/logout-all/GRANT candidate is never clicked;
7. secret wall pauses before another mutation;
8. human-control stops mutation immediately;
9. budget exhaustion persists progress and leaves `partial`;
10. repeated unchanged result does not cause double click;
11. mapping writes never alter live persona;
12. content/person fixture does not become Atlas room label.

## Return

`RETURN-RUN2-005-mapper-walker-safety.md`

Include a short trace from a deterministic test showing observe → one action → observe → verified write.
