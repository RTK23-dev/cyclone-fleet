# RUN 2 — AGENT 004 — MAPPING SESSION + PROTOCOL

**Read first:** `RUN-2-SHARED.md`  
**Branch (issued now):** `v5/mobile/mapping-session-protocol`  
**PR target:** `v5/integration`

You own the Run-2 **mapping control plane**. You do not own the crawler policy.

## Mission

Create one authoritative Android mapping-session state machine and expose the planned local-operator pipe:

```text
atlas.diff
mapping.start
mapping.pause
mapping.stop
mapping.status
```

The phone owns the state. Gateway/Glass only command and mirror it.

## Owned paths

- new `apps/mobile/**/mapping/session/**`
- Android `apps/mobile/**/gateway/**` registration/dispatch for Run-2 ops
- `apps/device-gateway/**` forwarding/validation
- protocol/contract material necessary for exact Run-2 shapes
- mapping-session / gateway tests
- MCP regression proving readonly mode cannot start mapping

Do not implement crawler exploration in `mapping/crawl`.

## Required behavior

### 1. Single authoritative session

A mapping job must bind:

- mapping job id;
- Place;
- persona;
- existing phone session id;
- display id / plane;
- control revision/generation as required by the existing runtime;
- budget;
- state;
- current Atlas node if known;
- safe progress counters;
- start/update timestamps.

No model prompt text or secret value belongs in that state.

Only one mutation-owning mapping job may control a given plane at a time.

### 2. Authority

`mapping.start`, resume/unpause and every mapper mutation must fail closed when:

- session missing;
- named workspace/display mismatch;
- human/companion owns input;
- phone plane changed;
- stale control revision/generation.

Reuse existing authority primitives. Do not invent a parallel lock.

### 3. State machine

Provide narrow APIs Agent 005 can call:

- acquire/start;
- report current node/progress;
- pause for secret;
- pause for human control;
- mark danger/boundary;
- record verified progress;
- complete partial/mapped;
- stop/fail.

`needs-secret` is nonterminal.

### 4. atlas.diff

Implement a durable or restart-safe-enough phone cursor/journal tied to Atlas structural changes, or an Atlas-revision mechanism that can produce equivalent safe diffs.

Requirements:

- cursor monotonically advances for one place/persona stream;
- stale/unknown cursor gets an explicit resync-required result rather than fabricated changes;
- no secret/user-content payloads;
- layout/status/screen/edge changes can be represented;
- full `atlas.get` remains recovery truth.

Do not create PC-side Atlas history.

### 5. Gateway

Android registers/dispatches the five Run-2 ops. Python forwards/validates only.

Mapping start is a local operator act. Do not expose it through readonly remote MCP.

Update `orchestrators/CONTRACT.md` with exact request/response field names if the existing contract is not exact enough.

### 6. Tests

Required:

1. missing session → SESSION_REQUIRED-equivalent;
2. display mismatch rejected;
3. HUMAN_HAS_CONTROL rejected;
4. start→pause→resume→stop state transitions;
5. needs-secret pause is nonterminal;
6. concurrent controller cannot steal same plane;
7. diff cursor advances after structural change;
8. stale cursor requests resync;
9. diff contains no secret/content fixture;
10. Python results are Android-forwarded, not PC truth;
11. readonly MCP cannot start mapping.

## Return

`RETURN-RUN2-004-mapping-session-protocol.md`

Document exact JSON shapes and the Kotlin session interface Agent 005 must consume.
