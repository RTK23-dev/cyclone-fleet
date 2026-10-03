# RUN 2 — SHARED AGENT BRIEF

**Product:** Cyclone Mobile 5.0  
**Run:** 2 — mapping session + safe mapper + canonical places  
**Milestone target:** mobile side of alpha.3, while preserving the alpha.2 Atlas/Glass contract  
**State:** ISSUED — CODING MAY START NOW

This file is mandatory for all three Run-2 implementation agents.

## 0. Start now / merge later gate

Run 2 is intentionally parallel with the final Agent-003 closeout.

All three Run-2 agents may code immediately from the single **RUN2_START_BASE_SHA** recorded in `mobile/STATUS.md`.

The gate applies at **merge/final-validation time**, not coding-start time:

1. PR #144 (protocol / needs-secret) is merged.
2. PR #145 (Vault / Secrets Card) is merged.
3. PR #146 (durable Atlas / Follow Me) must be corrected, have its required return, pass final CI, and merge before any Run-2 implementation PR is accepted into `v5/integration`.
4. After #146 merges, the orchestrator records **RUN1_CLOSEOUT_SHA**.
5. Every Run-2 implementation branch must rebase/merge-forward onto that exact closeout SHA before final review.
6. Final CI is required again on the rebased combined head.
7. Temporary compile seams/stubs are allowed only when they are narrow, explicitly marked, and removed/rebased before merge.

Do **not** stop coding merely because #146 is still open. Keep path ownership clean and treat the final closeout rebase as the integration checkpoint.

## 1. Run-2 goal

Run 1 created the headset memory and secret boundary. Run 2 gives the phone a controlled mapping loop.

The intended product flow is:

```text
operator chooses a Place
→ mapping.start binds an existing session/display
→ mapper observes the current phone page
→ Atlas gives a sketch of known structure
→ mapper chooses exactly one safe exploratory mutation
→ PhoneToolExecutor executes it
→ settle + re-observe + verify
→ update mapping-persona Atlas + progress cursor
→ repeat within a strict budget
→ pause for human / needs-secret / danger / control loss
→ stop with honest partial coverage
```

Run 2 is **not** Ask/Louella and is **not** a rapid graph macro runner.

## 2. Required reading

1. `Cyclone V5 plan/README.md`
2. `01-headset-and-laws.md`
3. `04-app-maps-canvas.md`
4. `05-secrets-vault.md`
5. `06-atlas-and-mapper.md`
6. `08-protocol-gateway.md`
7. `09-cuts-and-milestones.md`
8. `orchestrators/CONTRACT.md`
9. `docs/ARCHITECTURE.md`
10. `AGENTS.md`
11. all three Run-1 return files
12. your one Run-2 agent file

Use the merged Run-1 code as implementation truth.

## 3. Laws that Run 2 must not weaken

### Sentence is law
Mapping may be a user/operator command, but it cannot silently replace a normal Ask goal. Do not add Ask compiler behavior in this run.

### Phone is source of truth
Mapping state, Atlas writes, place identity and phone mutation are Android-owned. Device Gateway forwards/validates. Glass displays/commands.

### Eyes beat the map
Atlas is a hint. Every screen-changing action must be based on a fresh observation and followed by a fresh observation/verification.

### One mutation per decision
No burst of saved taps. No precompiled 10-step replay. One screen-changing mutation, settle, re-observe, verify.

### Never-pay mapping
The autonomous mapper never confirms:
- payment / purchase / subscription;
- send-public / post;
- destructive account/device deletion;
- logout-all;
- permission grants that cross the existing GRANT approval boundary.

Mark danger, preserve the node/edge if safe to do so, and stop/route around. Existing GATE semantics remain authoritative.

### Secrets are a pause, never mapper context
If mapping reaches a credential wall:
- use Run-1 `needs-secret`;
- show/use the existing phone Secrets Card;
- no password/OTP/token enters mapper state, Atlas, logs or gateway;
- after verified fill, re-observe before continuing.

### Human control wins
If the operator takes control, companion owns input, or session/display authority changes, mapper mutation stops immediately with the existing authority error/state. Do not “finish one more click.”

### Dummy is not live
Autonomous/dummy mapping writes `persona=mapping`. Live-persona Atlas may be taught by explicit live Follow Me or an explicitly live operator mode, but never by accidentally relabeling dummy crawl output.

### Partial is honest
`unmapped | partial | mapped | stale | blocked` are distinct. Reaching the budget does not mean mapped. A non-empty graph can still be partial.

### No content memory in Atlas
Atlas stores structural rooms, doors, capabilities and fact-slot definitions. Ordinary person names, message subjects, emails, order text and content values are not durable Atlas labels/facts. People memory is later.

## 4. Run-2 shared operations

Run 2 implements the already-planned local operator surface:

```text
atlas.diff(placeId, since)
mapping.start
mapping.pause
mapping.stop
mapping.status
```

Exact argument spelling must follow the existing Gateway/session conventions and must be documented in CONTRACT.md in Agent 004's PR.

All mapping mutations require existing session/display authority. Remote MCP remains readonly by default and must not gain an implicit mapping start.

### Mapping status must distinguish at least

```text
idle
running
paused
needs-secret
human-control
completed
stopped
failed
```

A budget stop that leaves dark regions should be a successful stop/completion with Atlas status `partial`, not a fake failure and not `mapped`.

### Diff semantics

`atlas.diff` is structural, phone-owned and cursor/revision based.

- `since` is an opaque/monotonic phone-issued cursor.
- response identifies place + persona + resulting cursor;
- additions/updates/removals are structural safe data only;
- no secret values;
- no raw `dynamic_json`;
- no raw accessibility tree;
- no unredacted frame/path;
- a client can fall back to `atlas.get` if its cursor is too old.

Do not invent a PC-side diff authority.

## 5. Parallel ownership

Run-2 agents must not edit the same Kotlin package in parallel.

| ID | Owner | Primary paths |
|---|---|---|
| 004 | mapping session + protocol | new `apps/mobile/**/mapping/session/**`, Android `gateway/**`, `apps/device-gateway/**`, protocol/CONTRACT tests |
| 005 | safe mapper walker | new `apps/mobile/**/mapping/crawl/**`, mapper-only tests |
| 006 | canonical places + Chrome + Settings integration | new `apps/mobile/**/places/**`, bounded Chrome resolver hooks, one Settings root owner, place tests |

Agent 004 owns central mapping operation registration.  
Agent 005 must not register duplicate gateway ops.  
Agent 006 must not create a second PlaceCatalog or Atlas store.

## 6. Dependency / merge order

Coding begins in parallel immediately from the recorded RUN2_START_BASE_SHA, using narrow interfaces.

Final integration order:

```text
004 mapping-session-protocol
→ 006 place-catalog-chrome-settings
→ 005 mapper-walker-safety
```

Why 005 last: the real crawler must consume the final session state machine, canonical Place resolver and merged Atlas/Vault behavior before acceptance.

If a later agent needs a central file owned by an earlier one, expose an interface and rebase. Do not duplicate the state machine.

## 7. Mapping budget

The mapper must have bounded work. At minimum support:

- maximum new screens;
- maximum elapsed time;
- maximum consecutive non-progress decisions;
- maximum attempts per door/region.

Defaults belong in phone configuration, not model prose. Tests must use tiny deterministic budgets.

On budget exhaustion:
- persist learned structural progress;
- mark honest `partial`;
- expose remaining/dark work in status;
- stop mutation.

## 8. Place identity

Frozen identity stays:

```text
package:<android.package>
chrome:<scheme>://<host>[:port]
```

No URL path/query/fragment in a Chrome Place.

Chrome origin must come from observed/browser state, not from guessing that the Chrome package is Facebook.

## 9. Prohibited Run-2 work

Do not implement:

- Ask compiler;
- People memory;
- Louella/person resolution;
- Glass Maps board;
- encrypted PC secret entry;
- broad UI redesign;
- payment automation;
- generic shell/root;
- background full-device crawl;
- cross-fleet Atlas merge;
- release/version cut;
- physical-device claims without evidence.

## 10. Validation

Collectively Run 2 must cover:

- Android unit tests;
- lint;
- release assembly;
- Device Gateway/MCP contract tests where touched;
- one mutation per decision;
- session/display authority;
- HUMAN_HAS_CONTROL;
- never-pay / never-send-public / never-delete;
- needs-secret pause/resume;
- budget exhaustion → partial;
- mapping/live isolation;
- real `atlas.diff` cursor behavior;
- Chrome origin canonicalization;
- no secret/content values in Atlas/diffs/status/logs.

Physical Pixel status remains **UNVERIFIED** unless a named physical pass is actually run.

## 11. Return contract

Each agent writes one file under:

`Cyclone V5 plan/orchestrators/mobile/returns/`

- `RETURN-RUN2-004-mapping-session-protocol.md`
- `RETURN-RUN2-005-mapper-walker-safety.md`
- `RETURN-RUN2-006-place-catalog-chrome-settings.md`

Include branch, PR, base SHA, head SHA, files, exact tests/results, cross-agent seams, deferred hooks, and physical verification status.

## 12. Stop conditions

Stop and report instead of improvising if your solution would:

- expose secret values;
- let PC/Glass become mapper authority;
- bypass session/display/control ownership;
- reuse `AppGraphExecutor` as autonomous V5 mapping;
- merge mapping persona into live;
- turn partial coverage into mapped;
- persist ordinary user content as Atlas structure;
- pay/send/delete/permission-grant autonomously.
