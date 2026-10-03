# RUN 001 — SHARED AGENT BRIEF

**Product:** Cyclone V5 / Cyclone Glass 1.0  
**Team:** Glass  
**Run:** 001 — alpha.1 HUD + alpha.2 Maps foundation  
**Repository:** `premiumcentraal-boop/Cyclone`  
**Integration branch:** `v5/integration`  
**PR target:** `v5/integration`

This file is shared by all three Run 001 Glass implementation agents.

Each agent receives exactly:
1. this shared brief; and
2. one agent-specific Run 001 brief.

Your specific brief is authoritative for your code paths and deliverables. Do not take work from another agent because it looks convenient.

---

## 1. Mission

Cyclone One 1.5.5 is already a working operator console: fleet, JPEG live view, human/AI handoff, sessions, MCP, ChatGPT Attach, camera, pairing, and doctor.

Cyclone Glass 1.0 adds the V5 human HUD:

- **Ask** — launch and observe the same Mobile run from the desktop.
- **Maps** — a Minitap-class visual board of the phone-owned app atlas.
- **Vault** — slot presence only; never secret values.
- Existing Phone/live/control features remain intact.

Glass is an additive product surface over the current companion. It is **not** a second execution engine.

---

## 2. Architecture law

> **The phone mutates. Glass commands and displays.**

Mobile 5.0 owns:

- atlas truth and persistence;
- vault and Android Keystore;
- Ask execution/compiler;
- GATE and dangerous-action policy;
- mapping crawler;
- device mutation;
- Android gateway operation handling.

Glass owns:

- operator-facing Ask UI;
- Maps canvas and inspector;
- Vault slot inventory UI;
- typed client consumption of Mobile/Gateway contracts;
- session-scoped command/display surfaces.

### Forbidden architecture drift

Do **not**:

- add or recreate `PhoneToolExecutor` on the PC;
- crawl Android UI from TypeScript to build the atlas;
- make Glass infer a second atlas independently;
- change protocol names locally because Mobile is not ready;
- silently fall back from a named workspace/session to display 0;
- rewrite `livePhoneController`;
- regress JPEG-first live view or human/AI handoff;
- edit `apps/mobile/**` from a Glass branch.

If a required Mobile contract is missing, stop that integration portion honestly and document the dependency. Do not invent an alternate protocol.

---

## 3. Required reading before code

Read these from the repository before editing:

1. `Cyclone V5 plan/README.md`
2. `Cyclone V5 plan/03-glass-v1.md`
3. `Cyclone V5 plan/04-app-maps-canvas.md`
4. `Cyclone V5 plan/orchestrators/CONTRACT.md`
5. `docs/V4_STAGE4_ONE_GLASS.md`
6. `apps/pc-companion/README.md`
7. existing companion code on your branch, especially `apps/pc-companion/src/app.ts`

Also read your one specific Run 001 MD completely.

The code baseline comes from One 1.5.5 / Mobile 4.8.0, but your branch must start from the **current** `v5/integration`, not stale `main`.

---

## 4. Branch discipline

Before creating or updating your implementation branch:

1. fetch the latest `v5/integration`;
2. record its starting SHA in your return;
3. branch using the exact name from your specific brief;
4. stay inside your allowed paths;
5. open a PR back into `v5/integration`.

Never create another integration branch.

If `v5/integration` advances while you work, rebase when your task-specific dependency requires it. Do not merge another agent's unfinished branch into yours casually.

---

## 5. Shared contract rules

The shared pipe is:

`Cyclone V5 plan/orchestrators/CONTRACT.md`

Frozen concepts include:

- Place = package or `chrome|origin`
- Persona = `live` or `mapping`
- `needs-secret` is a run state, not a failure
- `session_id` is required on scoped observe/act/ask/mapping operations
- Vault slot data on Glass is boolean presence only
- Mobile is source of truth for atlas objects

Run 001 expected ops are:

- `atlas.places`
- `atlas.get(placeId, persona)`
- `atlas.diff(placeId, since)` where available
- `ask.start`
- `ask.status`
- `secrets.slots`
- `secrets.request`
- mapping status may exist, but live `mapping.start` is not part of this Glass run

Protocol schemas are Mobile-owned. Glass consumes them.

---

## 6. Session and authority rules

`session_id` is not decorative metadata.

Preserve existing V4 authority semantics:

- missing session identity on a scoped operation → fail closed;
- named workspaces never silently become display 0;
- `HUMAN_HAS_CONTROL` remains authoritative;
- human takeover must continue to work;
- Mobile < 5.0 gets an honest compatibility/update state for V5-only Ask/Maps/Vault functionality.

Do not conceal these failures behind generic “offline” UX.

---

## 7. Secret boundary

No secret value may be persisted or exposed by Glass in Run 001.

Forbidden locations include:

- files;
- `localStorage`;
- fixtures;
- logs;
- console output;
- inspector/debug payloads;
- MCP traces;
- screenshots committed to the repo;
- test snapshots.

Allowed Glass knowledge is presence/state only, e.g.:

- “Gmail password: set”
- “Facebook password: missing”

Run 001 uses the **phone card** path for entering secrets. A desktop password input is out of scope.

If a payload unexpectedly contains raw secret-looking values, fail closed rather than forwarding, persisting, or rendering them.

---

## 8. Maps product bar

Maps is a primary navigation surface, not a Settings panel.

It must eventually let an operator understand an app as a house of **screens and doors**.

The Run 001 visual implementation should support the product direction:

- places catalog;
- dotted board;
- pan / zoom / fit;
- screen cards;
- meaningful door edges;
- region grouping;
- inspector;
- redacted-frame presentation;
- masked fact slots;
- Live / Dummy or live/mapping persona distinction as specified;
- coverage / mapped-vs-dark understanding;
- honest empty and stale states.

A package-name list is not Maps.

The alpha.2 mental test is:

> Can an operator look at the Gmail board and explain its major rooms and how they connect?

---

## 9. Existing One behavior is protected

Do not regress:

- focused JPEG live;
- fleet discovery;
- session tiles;
- human ↔ AI handoff;
- pairing;
- camera;
- ChatGPT Attach;
- MCP connection surfaces;
- doctor;
- current stream recovery behavior.

Avoid broad refactors unrelated to your task.

---

## 10. Verification expectations

Run the relevant existing companion tests plus the focused tests required in your specific brief.

At minimum:

- no TypeScript compile regression;
- no existing focused-live/session test regression;
- no secret strings introduced into fixtures/logs;
- compatibility/fail-closed behavior stays explicit;
- your PR contains only your owned scope.

If a test cannot be run in the available environment, state exactly which command was not run and why. Do not claim green evidence you do not have.

---

## 11. Required GitHub return

When done, you must:

1. push your implementation branch;
2. open a PR into `v5/integration`;
3. write the return MD named in your specific brief under:
   `Cyclone V5 plan/orchestrators/glass/returns/`;
4. include:
   - starting integration SHA;
   - final branch SHA;
   - PR URL;
   - changed files;
   - tests run and results;
   - screenshots if appropriate;
   - unresolved dependencies;
   - any CONTRACT mismatch discovered;
   - explicit confirmation that no secret values were added.

Do not self-merge unless your specific brief explicitly says otherwise.

---

## 12. Stop conditions

Stop and report instead of improvising if:

- your specific task requires editing another agent's owned implementation paths;
- a required Mobile schema/op does not exist;
- the CONTRACT and schema disagree;
- implementing the requested behavior would require secret persistence on PC;
- you would need to rewrite live video/handoff architecture;
- the only way forward is to invent a new protocol name.

Small compile/test seams are allowed only where your specific brief explicitly grants them.

---

## 13. Definition of Run 001 success

Across all three agents, the finished wave should make this true:

- Glass shell exposes Ask, Maps, Vault as first-class surfaces.
- Ask presents `needs-secret` as a human wait state.
- Vault exposes slot presence only.
- Maps can pan/zoom/fit a convincing Gmail-shaped house.
- Maps renderer consumes one stable `AtlasViewModel`.
- Real Mobile atlas data can replace the mock through the client lane.
- `session_id` semantics remain intact.
- One 1.5.5 live/control behavior still works.
- No secret values appear on the PC.
