# RUN 002 — SHARED AGENT BRIEF

**Product:** Cyclone V5 / Cyclone Glass 1.0  
**Team:** Glass  
**Run:** 002 — alpha.2 pipe (honest live replica)  
**Repository:** `premiumcentraal-boop/Cyclone`  
**Integration branch:** `v5/integration`  
**PR target:** `v5/integration`  
**Start SHA:** current `origin/v5/integration` at launch (record it in the return)

This file is shared by Glass Run 002 agents 007, 008, and 009.

---

## 1. Mission

Run 1 shipped the HUD **shape**: Ask / Maps / Vault nav, Mini-class board, typed `atlasClient`, wait-for-secret card. Maps currently mounts **mock Gmail** as if it were the phone. Vault lists **fixture** booleans. Ask never receives the focused phone’s version, so the update banner is un-wired.

Run 2 makes Glass a **replica**:

- Phone ≥ 5.0 → `atlas.places` / `atlas.get` / `secrets.slots` (booleans). Empty-valid is honest, not a crash.
- Phone < 5.0 or unknown version → **Update Cyclone on the phone.** No fake atlas.
- Mock graph is **explicit demo only**, labeled `(demo)`. Never a silent fallback for a V5 phone (`DEMO_ATLAS_DISABLED_WHEN_PHONE_V5`).

The operator should be able to open Maps on a 5.0 phone and see **that phone’s** house (or an honest empty/unmapped place). On 4.8 they must not see our demo Gmail presented as theirs.

---

## 2. Architecture law

> **The phone mutates. Glass commands and displays.**

Do **not**:

- add `PhoneToolExecutor` or crawl Android from TypeScript;
- invent ops. Gateway `V5_OPS` today is only  
  `atlas.places` · `atlas.get` · `secrets.slots` · `secrets.request`;
- implement `mapping.start` / `mapping.status` / `atlas.diff` / `ask.start`;
- rewrite `livePhoneController`;
- store secret values in `localStorage`, logs, MCP, inspector, fixtures;
- edit `apps/mobile/**` or protocol schema names;
- bump `release/version.toml` mobile / gateway / mcp.

`partial` stays `partial`. A non-empty graph is not automatically `mapped`.

`session_id` is required on every scoped atlas/secrets call. Named VD is never rewritten to display 0.

---

## 3. Required reading

1. `Cyclone V5 plan/README.md`
2. `03-glass-v1.md` G0–G4
3. `04-app-maps-canvas.md` G2.4, G2.8 (not G2.7)
4. `05-secrets-vault.md` (path 1 only)
5. `orchestrators/CONTRACT.md`
6. `orchestrators/glass/STATUS.md`
7. Run 1 returns 001–006
8. Your one Run 002 agent file

---

## 4. Frozen option contract (007 / 008 export; 009 mounts)

Do not bikeshed these names. 009 will call them even if it merges after you.

### Maps (`createMapsPage`)

```ts
createMapsPage({
  source?: MapsDataSource;                 // existing; keep
  loadSource?: () => Promise<MapsDataSource>;
  phoneVersion?: string | null;
  demo?: boolean;                          // explicit opt-in mock
  sessionId?: string;
})
```

Rules:

| Inputs | Board |
|---|---|
| `demo === true` | Mock atlas, visible **(demo)** banner. |
| `phoneVersion` missing / < 5.0 | Update-the-phone. **No mock.** |
| `phoneVersion` ≥ 5.0 and `loadSource` | Loading → live source or honest empty / unmapped / error. |
| neither demo nor version nor loadSource | Keep today’s mock default so 007 can merge before 009. |

Start mapping stays **disabled** (`phone alpha.3`).

### Vault (`createVaultPage`)

```ts
createVaultPage({
  devices?: DesktopDevice[];
  mobileVersion?: string | null;
  slots?: readonly GlassVaultSlot[];       // existing fixture override
  loadSlots?: () => Promise<readonly GlassVaultSlot[]>;
  previewSlots?: boolean;                  // explicit sample inventory
  onRequestSlot?: (slotId: string) => void;
})
```

Rules: same version gate. Fixture inventory only when `previewSlots === true` or when 009 has not wired `loadSlots` **and** no version was passed (007-style backward compatible default). Once `mobileVersion` is passed, never show fixture as live.

### Ask

Existing `mobileVersion?: string` on `createAskPage`. 009 **must pass it**. Do not enable Send. `ask.start` is not in `V5_OPS`.

---

## 5. GitHub

- Branch: `v5/glass/<slug>` off `v5/integration`
- PR into `v5/integration`, not `main`
- Two commits preferred: `feat(glass): …` then `docs(v5 glass): return Run 2 Agent 00N …`
- Return: `orchestrators/glass/returns/RETURN-00N-<slug>.md`
- Tests: `cd apps/pc-companion && npm test` must stay green
- Restore accidental `package-lock.json` churn unless you were assigned it

## 6. Success for the wave (orch, after all three)

4.8 phone: Maps is not a fake Gmail house.  
5.0 phone / empty atlas: Maps is empty-valid or unmapped, not a crash.  
Vault: booleans from `secrets.slots` or honest empty; no password field.  
Ask: version-aware banner. Send still off.  
No mapping cursor. No secret values.
