# RETURN-012 — glass-session-bind

**Agent:** Glass Run 3 Agent 012 (glass-session-bind)  
**Handoff:** `agents/HANDOFF-012-glass-session-bind.md`  
**Date:** 2026-09-22  
**Branch:** `v5/glass/glass-session-bind`  
**PR:** https://github.com/premiumcentraal-boop/Cyclone/pull/162  
**Starting integration SHA:** `3578e6c2667853d5cc79ba4ddb9801eeec2842a5`  
**Implementation SHA:** `c87cbb034d32d7df619560ec19649ec94f992777`  
**Head SHA:** this return commit on the same branch  
**Commits:**
- `c87cbb03` feat(glass): Run 3 Agent 012 — bind session plane into Ask/Maps
- this return file

## Done

Mount only. 010/011 own page internals. `app.ts` is the only place that knows which phone and which `session_id`.

- `CompanionState.focusedSessionId: string | null` (default `null`).
- Action `{ type: "focus_session"; sessionId: string | null }` — trim; empty → `null`. Named ids are **not** rewritten to `default-foreground` in the reducer (display default happens at `resolveGlassSessionId`).
- `navigate` to `ask` | `maps` | `vault` **keeps** `focusedDeviceId` and `focusedSessionId`.
- `navigate` to `home` | `fleet` | `automations` | `connections` | `chatgpt` | `settings` clears both (leave focused-phone).
- `back_to_fleet` and lost device clear both.
- `ASK_NEEDS_SECRET_FIXTURE.title` unchanged (`"Facebook needs a password"`). Vault fixture slots unchanged.
- `glassContext()`: `sessionId = resolveGlassSessionId(this.state.focusedSessionId)`. Named preserved. Empty/null → `default-foreground`.
- `sessionPlane`: `"session_kernel_vd"` when resolved id is not `default-foreground`; else `"foreground"`. No `layer2` on Maps/Ask.
- Ask constructor: `{ devices, mobileVersion, onOpenControl, sessionId, sessionPlane, previewSnapshots: demo }`. `previewSnapshots` is `true` only when `service.mode === "mock"`. Real backend does not auto-preview samples.
- Maps constructor: existing fields plus `sessionId`, `sessionPlane`, `onOpenControl: () => this.navigate("fleet")` (Take control → Phone live / Control).
- Vault: unchanged Run 2 mount (`devices`, `mobileVersion`, `loadSlots`, `onRequestSlot`, `previewSlots`). Dropped `as VaultPageOptions` (tsc clean). Ask/Maps still type-asserted because 010/011 option types are not on this worktree.
- Phone page wired: `createFocusedPhonePage(..., onSessionFocus?)`. Operator tile click fires the callback; `app.ts` dispatches `focus_session` **without** remounting JPEG / `livePhoneController`.
- Settings **Cyclone Glass atlas** card: one extra sentence — Ask/Maps declare Foreground (`default-foreground`) or the named Session Kernel VD; named ids are never rewritten to display 0. MCP tunnel card untouched.
- `sessionPlaneFromSessionId` helper in `glassRuntime.ts` (tests). Atlas HTTP and `secrets.request` body unchanged (still no `session_id` on the JSON body).

## GitHub evidence

| PR / commit | What it is |
|---|---|
| https://github.com/premiumcentraal-boop/Cyclone/pull/162 | Agent 012 PR targeting `v5/integration` |
| `3578e6c2667853d5cc79ba4ddb9801eeec2842a5` | Starting `v5/integration` SHA |
| `c87cbb034d32d7df619560ec19649ec94f992777` | Implementation |

## Paths touched

```text
apps/pc-companion/src/app.ts
apps/pc-companion/src/core/fleet.ts
apps/pc-companion/src/services/glassRuntime.ts
apps/pc-companion/src/pages/focusedPhonePage.ts
apps/pc-companion/src/pages/settingsPage.ts
apps/pc-companion/tests/fleet.test.mjs
apps/pc-companion/tests/glass-runtime.test.mjs
Cyclone V5 plan/orchestrators/glass/returns/RETURN-012-glass-session-bind.md
```

Did **not** edit `mapsPage.ts` internals, `askPage.ts` internals, `vaultPage.ts`, `appMapCanvas.ts`, `atlasClient.ts` implementation, `livePhoneController`, camera, ChatGPT Attach, `apps/mobile/**`, or `version.toml`.

## Tests

```text
cd apps/pc-companion && npm test
→ 196 pass, 0 fail

npx tsc -p tsconfig.json --noEmit → clean
```

Handoff coverage:

- `resolveGlassSessionId("vd-mail")` → `vd-mail`; empty → `default-foreground`
- `sessionPlaneFromSessionId("vd-mail")` → `session_kernel_vd`; blank / default-foreground → `foreground`; never `layer2`
- focus device then navigate to `maps` (also ask/vault) keeps `focusedDeviceId` and `focusedSessionId`
- navigate to `home` clears `focusedSessionId`
- ask navigate from home still has `focusedDeviceId` null
- `focus_session` trims; empty → null; named id not rewritten
- lost device / `back_to_fleet` clear both
- `app.ts` source contains `sessionPlane`, `onOpenControl`, `previewSnapshots`, `focusedSessionId`, `session_kernel_vd`, `focus_session`
- settings atlas card mentions Foreground / Session Kernel VD / never rewritten to display 0
- no `ask.start` / `mapping.start`; no secret values

`package-lock.json` was not changed. `npm install` only in `apps/pc-companion`.

## Contract

Did this change names/ops? n/a — no new ops. Frozen option names called as specified (`sessionId`, `sessionPlane`, `onOpenControl`, `previewSnapshots`). Named VD never rewritten to `default-foreground` / display 0.

## Not done / blocked

- Maps inspector, dark-doors filter, capability glyphs, Take control **button chrome** — Agent 010. 012 passes `sessionPlane` + `onOpenControl`; 010 must render them.
- Ask HUD `(sample)` labeling / empty HUD when version is passed without preview — Agent 011. 012 passes `sessionId`, `sessionPlane`, `previewSnapshots: demo`.
- Type assertions `as AskPageOptions` / `as MapsPageOptions` remain until 010/011 land (excess-property check). Remove after merge.
- `ask.start` / Send still off. Start mapping still disabled (`phone alpha.3`). Encrypted fill not this cut.
- Gateway `/v1/fleet` `appVersion` still absent — fail closed (unchanged).
- Merge **after** 010 and 011.

## Suggested next handoff

- Orch: merge 010 + 011 first, then 012. After all three: operator on Maps sees plane + Take control; operator on Ask sees plane and never mistakes a sample HUD for a live 5.0 run.
- Follow-up (not this PR): drop Ask/Maps type assertions once 010/011 option types exist on `v5/integration`.
