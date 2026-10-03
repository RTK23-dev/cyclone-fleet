# RUN 001 — AGENT 001 — SHELL / ASK / SECRET WAIT

**You are:** Glass implementation Agent 001  
**Shared brief:** `RUN-001-SHARED.md`  
**Branch:** `v5/glass/shell-ask-secret`  
**PR target:** `v5/integration`  
**Return:** `returns/RETURN-001-shell-ask-secret.md`

Read the shared brief first. This file contains your individual task only.

---

## Objective

Create the Glass shell surfaces for Run 001 without implementing the atlas/secrets transport.

Your job is to make Cyclone One's existing desktop shell visibly become **Cyclone Glass**, with first-class **Ask**, **Maps**, and **Vault** routes while preserving all existing live/control behavior.

---

## Owned paths

You may edit:

- `apps/pc-companion/src/app.ts` — shell/nav/page mounting only
- `apps/pc-companion/src/core/fleet.ts` — route/state type seam only
- `apps/pc-companion/src/pages/askPage.ts` — new
- `apps/pc-companion/src/pages/vaultPage.ts` — new
- `apps/pc-companion/src/ui/secretsCard.ts` — new waiting-state component
- companion CSS needed for these new shell/pages
- `apps/pc-companion/tests/fleet.test.mjs` — route coverage only
- focused new Ask/Vault UI tests where the existing harness supports them
- one minimal doctor/settings copy seam if needed to state that Maps/Ask atlas requires Mobile 5

Do not expand those permissions into transport work.

---

## Do not touch

Do not edit:

- Maps canvas implementation owned by Agent 002 except for a shell placeholder/mount seam
- `atlasClient.ts` or new atlas/secrets transport owned by Agent 003
- `livePhoneController`
- live stream internals
- ChatGPT Attach internals
- camera internals
- `apps/mobile/**`
- protocol schemas

---

## Deliverables

### A. Glass identity

Change visible top-level UI branding from **Cyclone One** to **Cyclone Glass**.

Do not rename installer paths in this task.

Existing install/MCP path compatibility must remain intact.

### B. Primary navigation

The Glass shell should expose the V5 product shape:

- **Phone** — existing focused live/control entry
- **Ask**
- **Maps**
- **Vault**
- **Tasks**
- **Connections**
- **Settings**

Preserve existing required routes even if their visual grouping changes.

Maps may mount a clear placeholder supplied by this task only if Agent 002 is not yet merged. Do not build the actual board.

### C. Ask page

Build the desktop Ask presentation surface.

It must support:

- goal composer;
- run/HUD presentation area;
- a fixture or existing compatible presentation snapshot;
- explicit `needs-secret` rendering;
- a human-readable wait state such as:
  **Needs you — Gmail password**
- waiting copy that makes clear entry occurs on the phone;
- existing Take control/handoff semantics where already available.

If the exact V5 `ask.start` transport is not yet present, keep the composer honestly disabled or fixture-backed. Do **not** invent an operation name or client layer.

The page must distinguish a secret wait from a failed run.

### D. Secret wait card

Create a reusable **waiting** component for phone-side secret entry.

Run 001 must not contain a desktop password field.

The component may display:

- slot display label;
- missing/set/waiting state;
- instruction to continue on phone.

It may not accept, persist, log, or render the secret value.

### E. Vault page stub

Create a real Vault route/page, but only as slot inventory UI.

Examples of acceptable rows:

- Gmail password — Set
- Facebook password — Not set

Until Agent 003 wires real `secrets.slots`, use empty/fixture state that is clearly non-live.

There must be no password input in the Vault DOM.

### F. Compatibility state

For phones that do not satisfy the V5 capability/version requirement, Ask/Maps/Vault should show an honest message equivalent to:

> Update Cyclone Mobile to 5.0 to use Ask atlas, Maps, and Vault on Glass.

Do not show fake live atlas data.

---

## Tests required

Add/adjust focused tests for:

1. new route types and navigation;
2. Ask page rendering a `needs-secret` fixture as a wait state;
3. Vault page containing no password input;
4. existing focused-live preservation tests remaining green.

Run the companion's normal test/typecheck commands available in the repository.

---

## Acceptance check

Your PR is complete when:

- the user can navigate to Ask, Maps, and Vault from Glass;
- Glass branding is visible;
- Ask has a convincing `needs-secret` wait presentation;
- Vault is slot-state-only;
- no new transport vocabulary was invented;
- existing Phone/live behavior remains intact.

Do not wait for Agent 002 or 003 to finish before opening your PR if your own scope is complete.
