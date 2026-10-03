# RUN 003 — SHARED AGENT BRIEF

**Product:** Cyclone V5 / Cyclone Glass 1.0  
**Team:** Glass  
**Run:** 003 — alpha.2 operator table (session plane + Maps inspector/bar + honest Ask HUD)  
**Repository:** `premiumcentraal-boop/Cyclone`  
**Integration branch:** `v5/integration`  
**PR target:** `v5/integration`  
**Start SHA:** current `origin/v5/integration` at launch (record it in the return)

This file is shared by Glass Run 003 agents **010**, **011**, and **012**.

---

## 1. Mission

Run 1 shipped the HUD **shape**. Run 2 shipped the **honest pipe** (4.8 sees update-the-phone; 5.0 empty atlas is empty-valid; Vault booleans; mock only when `demo === true`). Combined companion tests: 190 pass.

Run 3 makes Glass an **operator table** on that pipe:

- Maps and Ask **declare which session plane** they are talking to (Foreground · named Session Kernel VD). `session_id` is displayed, not guessed.
- Maps bar grows **Take control** (existing Phone live handoff) and an **edge inspector**. Dark-doors filter. Capability glyphs on cards.
- Ask sample snapshots are labeled **(sample)** and never look like a live phone run once a version is passed.

Still **not** this cut: `mapping.start` / cursor / `atlas.diff` / `ask.start` / Send / encrypted fill / Mobile #146 graph quality.

---

## 2. Architecture law

> **The phone mutates. Glass commands and displays.**

Do **not**:

- add `PhoneToolExecutor` or crawl Android from TypeScript;
- invent ops. Gateway `V5_OPS` today is only  
  `atlas.places` · `atlas.get` · `secrets.slots` · `secrets.request`;
- implement `mapping.start` / `mapping.status` / `atlas.diff` / `ask.start`;
- rewrite `livePhoneController`;
- store secret values in `localStorage`, logs, MCP, inspector, fixtures, HUD downloads;
- edit `apps/mobile/**` or protocol schema names;
- bump `release/version.toml` mobile / gateway / mcp;
- bump Glass identity (`1.6.0-alpha.1`) — orch cuts the tag later.

`partial` stays `partial`. Named VD `session_id` is never rewritten to `default-foreground` / display 0.

---

## 3. Required reading

1. `Cyclone V5 plan/README.md`
2. `03-glass-v1.md` G0, G1, G2 summary, G4
3. `04-app-maps-canvas.md` G2.2, G2.5, G2.6 (not G2.7)
4. `orchestrators/CONTRACT.md`
5. `orchestrators/glass/STATUS.md`
6. Run 2 returns 007–009
7. `apps/pc-companion/src/core/sessionTiles.ts`
8. Your one Run 003 agent file

---

## 4. Frozen option contract (010 / 011 export; 012 mounts)

Do not bikeshed these names. 012 will call them even if it merges after you.

### Maps (`createMapsPage`) — Agent 010 adds; keep Run 2 options

```ts
createMapsPage({
  source?: MapsDataSource;
  loadSource?: () => Promise<MapsDataSource>;
  phoneVersion?: string | null;
  demo?: boolean;
  sessionId?: string;
  sessionPlane?: "foreground" | "session_kernel_vd";
  onOpenControl?: () => void;
})
```

| New field | Behavior |
|---|---|
| `sessionId` | Already accepted. **Now display it** in the top bar (never invent a second id). Empty/omitted → show `default-foreground`. |
| `sessionPlane` | `foreground` (default) or `session_kernel_vd`. Label: **Foreground** / **Session Kernel VD**. Never rewrite a named id to display 0. |
| `onOpenControl` | Enables **Take control** on the Maps top bar. Same Phone live handoff as Ask. Missing callback → button disabled, not a mapping pause. |

Start mapping stays **disabled** (`phone alpha.3`).

### Ask (`createAskPage`) — Agent 011 adds; keep existing options

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

| Inputs | HUD |
|---|---|
| `previewSnapshots === true` or `previewNeedsSecret === true` | Today's sample dropdown, labeled **(sample)**. |
| `"mobileVersion" in options` and not preview | **No sample dropdown as a live run.** Empty HUD until a real snapshot exists (`ask.start` is still off). |
| version omitted, no preview flag | Keep today's sample dropdown so 011 can merge before 012 (merge-safe default). |

Send stays **disabled**. No `ask.start`.

### Vault

No new options this run. 012 still mounts Run 2 names. Do not mix Live/Dummy inventories.

---

## 5. GitHub

- Branch: `v5/glass/<slug>` off `v5/integration`
- PR into `v5/integration`, not `main`
- Two commits preferred: `feat(glass): …` then `docs(v5 glass): return Run 3 Agent 00N …`
- Return: `orchestrators/glass/returns/RETURN-00N-<slug>.md`
- Tests: `cd apps/pc-companion && npm test` must stay green
- Restore accidental `package-lock.json` churn unless you were assigned it
- Dynamic CSS (`<link>`), not static `import "*.css"`, if Node tests import the page

## 6. Merge order (orch)

**010 + 011 first**, then **012**. 012 type-asserts new options only if 010/011 are not on its worktree.

## 7. Success for the wave (orch, after all three)

Operator on Maps can: see which plane, Take control to Phone, click a door and read English in the inspector, filter dark doors, still not Start mapping.  
Operator on Ask can: see which plane, never mistake a sample HUD for a live 5.0 run, download a redacted HUD log, still not Send.  
`session_id` on Ask/Maps is the focused named VD when one is selected, else `default-foreground`. Never rewritten.
