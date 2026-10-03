# RETURN-011 — ask-hud-honesty

**Agent:** Glass Run 3 Agent 011 (ask-hud-honesty)  
**Handoff:** `agents/HANDOFF-011-ask-hud-honesty.md`  
**Date:** 2026-09-22  
**Branch:** `v5/glass/ask-hud-honesty`  
**PR:** https://github.com/premiumcentraal-boop/Cyclone/pull/163  
**Starting integration SHA:** `3578e6c2667853d5cc79ba4ddb9801eeec2842a5`  
**Implementation SHA:** `e8754932cb5e22fbc738595dae2353159cc5cdba`  
**Head SHA:** this return commit on the same branch  
**Commits:**
- `e8754932` feat(glass): Run 3 Agent 011 — honest Ask HUD and session plane
- this return file — docs(v5 glass): return Run 3 Agent 011 ask-hud-honesty

## Done

Ask is an honest replica HUD. Sample snapshots no longer look like a live 5.0 phone run once a version is passed. Send stays off. No `ask.start`.

Frozen `createAskPage` options (keep Run 2 names; 012 will call these):

```ts
createAskPage({
  devices?: DesktopDevice[];
  mobileVersion?: string;
  previewNeedsSecret?: boolean;
  previewSnapshots?: boolean;
  sessionId?: string;
  sessionPlane?: "foreground" | "session_kernel_vd";
  onOpenControl?: () => void;
})
```

Honesty (`Object.prototype.hasOwnProperty.call(options, "mobileVersion")` or `"mobileVersion" in options`):

| Inputs | HUD |
|---|---|
| `previewSnapshots === true` or `previewNeedsSecret === true` | Today's sample dropdown, labeled **(sample)** / “Sample snapshot — not a live phone run.” |
| `"mobileVersion" in options` and not preview | No sample dropdown as this phone. Empty HUD. 4.8.0 still shows update-phone banner. 5.x: “No Ask run yet” / Send stays off until the phone Ask transport is connected. |
| version omitted, no preview flag | Today's sample dropdown (merge-safe default so 011 can land before 012). |

Session plane on the Ask header: `session_kernel_vd` → **Session Kernel VD**, else **Foreground**. Empty/omitted `sessionId` → `default-foreground`. Named id `vd-mail` is displayed unchanged (never rewritten).

**Download HUD log** appears when a snapshot is showing. Browser path: `Blob` + object URL + `<a download="ask-hud-log.txt">`. `formatAskHudLog(snapshot)` is exported from `askHudLog.ts` (re-exported from `askPage.ts`) so Node tests assert the string. Log contains state, title, milestones, `session_id`. Password/otp/cookie/token **values** are stripped. Slot labels such as “Facebook password” stay. Downloaded text does not contain `hunter2` / `otp=` / `cookie=`.

CSS: static `import "../ask.css"` switched to the same dynamic `<link>` pattern as Maps/Vault so Node tests can import `createAskPage`. No `type=password` inputs.

`ASK_NEEDS_SECRET_FIXTURE.title` remains `"Facebook needs a password"`. `fleet.ts` was not edited.

## GitHub evidence

| PR / commit | What it is |
|---|---|
| https://github.com/premiumcentraal-boop/Cyclone/pull/163 | Agent 011 PR targeting `v5/integration` |
| `3578e6c2667853d5cc79ba4ddb9801eeec2842a5` | Starting `v5/integration` SHA |
| `e8754932cb5e22fbc738595dae2353159cc5cdba` | Implementation commit |
| this return commit | Return MD on the same branch |

## Paths touched

```text
apps/pc-companion/src/pages/askPage.ts
apps/pc-companion/src/pages/askHudLog.ts
apps/pc-companion/src/ask.css
apps/pc-companion/tsconfig.test.json
apps/pc-companion/tests/ask-hud.test.mjs
apps/pc-companion/tests/ask-vault.test.mjs
Cyclone V5 plan/orchestrators/glass/returns/RETURN-011-ask-hud-honesty.md
```

Did **not** edit `app.ts`, `mapsPage.ts`, `vaultPage.ts`, `fleet.ts`, `glassRuntime.ts`, `livePhoneController`, `apps/mobile/**`, `version.toml`, or `package-lock.json`.

## Tests

```text
cd apps/pc-companion && npm test
→ tsc -p tsconfig.test.json && node --test tests/*.test.mjs
→ 201 pass / 0 fail / 0 skipped

npx tsc -p tsconfig.json --noEmit → clean
```

Focused ask-hud coverage:

- omitted version → sample dropdown still exists
- `{ mobileVersion: "5.0.0-alpha.1" }` without preview → no “Sample snapshot (no phone required)” live-looking bar; Opening Facebook / Facebook needs a password not presented as current run; “No Ask run yet”
- `{ mobileVersion: "4.8.0" }` → update-phone copy; samples not live
- `previewSnapshots: true` with 5.x → samples + **(sample)** wording
- `sessionId: "vd-mail"` appears; not rewritten to default-foreground
- `formatAskHudLog` on needs-secret fixture contains the title, `needs-secret`, `default-foreground` (or provided session); does not match `hunter2` / `otp=` / `cookie=`
- Send still disabled; form submit does not execute on the PC
- `ASK_NEEDS_SECRET_FIXTURE.title` remains `"Facebook needs a password"`
- existing ask-vault tests still pass

## Contract

Did this change names/ops? **no**. Consumed frozen Ask option names from RUN-003-SHARED. No `ask.start`. No new gateway ops.

## Not done / blocked

- `app.ts` does not pass `previewSnapshots` / `sessionId` / `sessionPlane` — Agent 012 mounts. Until 012, 009 already passes `mobileVersion`, so a real/versioned Ask page is the empty honest HUD (correct). Mock samples return when 012 passes `previewSnapshots: demo`.
- `ask.start` / Send remain off.
- Encrypted Glass fill (G3 path 2) — out of scope.
- Maps operator bar (010) and session bind (012) are parallel; merge **010 + 011 first**, then 012.

## Suggested next handoff

Agent 012: `createAskPage({ devices, mobileVersion, onOpenControl, sessionId, sessionPlane, previewSnapshots: demo })`. Real backend must **not** auto-preview samples. Named `session_id` is never rewritten to `default-foreground`.
