# Session Contract (Mobile 4.1 / One 1.1)

Frozen for Mobile B2 + One A2/A3. Do not invent a fourth plane without a plan revision.

Physical Pixel 8 = **UNVERIFIED**.

## A3 glass

One draws named Session Kernel VD tiles (`session.added` / `session.removed`) with `session_id`, `displayId`, owner HUMAN/AI, and per-session JPEG focus. Named VD is never rewritten to display 0. Pause / Take control / Give to AI stay per tile; `PHONE_LOCKED` / `HUMAN_HAS_CONTROL` remain fail-closed. Foreground vs Session Kernel VD vs Layer 2 workspace copy stays distinct; the A2 Layer 2 strip is not a VD tile. Physical Pixel 8 = **UNVERIFIED**.

## A4 operator pack

Operator browse is typed MCP `phone_status` → `phone_observe` → `phone_locate` → `phone.home` → `phone.open_app` (`params.package`, Chrome = `com.android.chrome`) on `session_id=default-foreground` / display 0. One UI and doctor name that session id. This is not a fourth plane. Physical Pixel 8 = **UNVERIFIED**.

## Planes

| Plane | Identity | Display | Mutation model | Typical MCP | `plane.kind` / `label` |
|---|---|---|---|---|---|
| Foreground human | `session_id=default-foreground` | `displayId=0` | Direct; human may hold input | `phone_observe/act` + session_id | `foreground` / Foreground |
| Session Kernel VD | named `session_id` | `displayId>0` required | Isolated VD inject; never display 0 | same + `display_id` | `session_kernel_vd` / Session Kernel VD |
| Layer 2 workspace | `workspaceId` + `workspaceGeneration` | stays display 0 | Time-sliced global mutate lock; switch verifies package/user | `phone_workspace` then `phone_act.params` carry ids | `layer2_workspace` / Layer 2 workspace |

Classifier mix:

- omitted session on Android `classify` → internal default-foreground / display 0
- named + `displayId>0` and no workspace ids → Session Kernel VD
- `workspaceId` + `workspaceGeneration` + default-foreground / display 0 → Layer 2
- product hot-gate for named VD Ask remains **1**; types/APIs hold N≥2

## Hard rules

1. MCP UI observe/act missing `session_id` → `SESSION_REQUIRED` (no silent default). Android gateway omitted identity still binds default-foreground for One 1.0.0 pairing; it never invents a named VD or rewrites a named session onto display 0.
2. Named VD missing/`0` display → `SESSION_DISPLAY_MISMATCH`.
3. Layer 2 mutate without matching generation → fail closed (`MUTATE_LOCK` / `WORKSPACE_GENERATION_STALE`).
4. GATE blocks switch and queue; never synthesize approval.
5. PC is glass only; `PhoneToolExecutor` on Android is the only mutator.
6. One UI must never draw Layer 2 as a VD tile or vice versa.
7. Mixing named VD `session_id` with `workspaceId` (or Layer 2 ids with `displayId>0`) → `PLANE_MISMATCH`.
8. `workspaceId` XOR `workspaceGeneration` → `WORKSPACE_GENERATION_REQUIRED`.
9. Product hot-gate for named VD Ask remains 1. Types/APIs hold N≥2. Do not claim 20.

## Plane metadata

Responses include `plane: {kind, sessionId, displayId, workspaceId, workspaceGeneration, label}` so One glass can label without guessing.

`SessionPlane.toJson()` uses those keys. `kind` is the wire value (`foreground` | `session_kernel_vd` | `layer2_workspace`).

## Error classes

| Class | When |
|---|---|
| `SESSION_REQUIRED` | MCP UI observe/act (`requireUi`) missing `session_id` / `sessionId` |
| `SESSION_DISPLAY_MISMATCH` | Named VD missing or `0` display; default-foreground with `displayId != 0`; conflicting `sessionId` / `session_id` aliases |
| `PLANE_MISMATCH` | Named VD identity mixed with `workspaceId`, or Layer 2 ids mixed with `displayId>0` |
| `WORKSPACE_GENERATION_REQUIRED` | Exactly one of `workspaceId` / `workspaceGeneration` present |
| `WORKSPACE_GENERATION_STALE` | Layer 2 mutate generation does not match the current lease |
| `MUTATE_LOCK` | Layer 2 mutate without the matching generation / lock |

Default `SessionIdentityException.errorClass` is `SESSION_DISPLAY_MISMATCH`.

Physical Pixel 8 = **UNVERIFIED**.
