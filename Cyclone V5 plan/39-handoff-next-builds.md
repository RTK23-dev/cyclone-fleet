# 39 — Handoff: picking up the remaining builds

**For:** the next agent on this branch. **Written:** 2026-09-29, at **5.0.0-alpha.70.dev1** (version code 215,
Glass 1.0.0-alpha.40).
**Branch:** `claude/cyclone-v5-handoff-review-9qrs40`, the only branch to push to. Another agent works on it too:
always `git fetch` and fast-forward first.

This is the working brief for every build left on the roadmap (plan 35): what each one is, where its code lives, what
already exists to build on, how to test it, and the traps. Plan 35 stays the list; this file says how to build it.

## 0. Read first (in this order)

1. `AGENTS.md`: product laws, ownership, versioning, the fast release lane, validation commands.
2. `Cyclone V5 plan/35-roadmap-to-5.0.md`: the build list and the owner's tests.
3. The plan named in the build's row below, especially its **As built** sections. Those say what the code really does.
4. The last three `docs/RELEASE_5.0.0-alpha.*.md`: the format to copy, and the physical checks still owed.

## 1. Where things stand

- **alpha.66:** the mission workspace, plan 37 W1+W2, behind a setting (off by default).
- **alpha.67:** memory v2 (plan 37 W3).
- **alpha.68:** steer, queue, parallel and plan diversions (plan 38).
- **alpha.69–70 (the other agent):** the AI screen redesign and its official scene. They took the release numbers
  plan 35 had given to recipes and fleet health, so **every planned build below is two numbers later than plan 35
  says**. Use the next free number; don't try to match plan 35's.
- **alpha.71:** Drive, polished (the intro film, the button dragged anywhere, "Listening" once).
- **alpha.72 (the other agent):** the app redesign (R5, `docs/design/redesign/rounds/R5-app.md`) with Home (R4),
  Visual quality (Auto / Full / Lite), and Drive's microphone fix with Android's recognizer as fallback. The builds
  below shift by two more.
- **alpha.73 (the other agent):** R6, calm and findable (`docs/design/redesign/rounds/R6-calm.md`): the calm blue on
  every page but AI, the smart search and Home's profile slider.
- **alpha.74 (the other agent):** Home fixes (the + drawer above the Ask bar, unclipped labels) and plan 40
  (profiles as a workhorse).
- **alpha.75 (the other agent):** plan 40 P1 and P2: profile removal with a 7-day Recently deleted, deletion only
  after an automatic backup, and Cyclone Carry (memory, skills and settings follow you on every switch).
- **alpha.76 (the other agent):** plan 41's parallel Pilot, Fast mode in Settings (off by default).
- **alpha.77 (the other agent):** plan 42's Cyclone Live: the modes router, Instant and Live voice.
- **alpha.78:** the voice-mode fixes and decisions via JEV.
- **alpha.80:** plan 43 T2, cross-referencing (relations, rollups, formulas, timeline, calendar).
- **alpha.81:** plan 43 T5 + T6, Accounts rebuilt with sign-up mapping (no profiles).
- **alpha.82:** plan 43 T7, Create accounts (the owner asked for no pre-send checks, app block list or caps for now).
- **alpha.83:** plan 43 T3, action buttons.
- **alpha.84:** plan 43 T4, profiles from the PC (list, switch, each profile's apps).
- **alpha.85:** fix, sign-up mapping you can see and stop (cause, Cancel mapping, last try; sign-up runs only in front).
- **alpha.86:** fix, the Lab tools on the MCP server the Windows package runs (`cyclone_phone_mcp`).
- **alpha.87:** reliability builds 1, 2 and 6 (phone care: update the phone from Glass, busy is not disconnected, why Cyclone stopped), plus MRZ Studio discovery (PR 197).
- **alpha.88:** reliability builds 3, 4 and 5 (one truth for connection health, the broken link named with one action, one-tap trust and silent resume); all six builds now shipped.
- **alpha.89:** instant decisions (JEV decides with a 2.5 s deadline and Auto as the default; the lesson store; phone model v1 that acts only on earned actions, audited 1 in 10; decision numbers in Glass Details).
- **alpha.90:** plan 44 run 1, cloud phones (VMOS Cloud, DuoPlus, remote ADB) kept connected: 7-day VMOS ADB renewed, SSH tunnel kept, adb reconnected, Cyclone installed; Glass Devices → Cloud phones.
- **alpha.91:** fixes from the alpha.90 stress test (self-pause, Accessibility repair, Instant for everyday phrases, chatter, dialogs, Settings toggles, ask ids/cancel + MCP phone_ask, Lab scoring, two main-thread stalls).
- **alpha.92:** fewer turns for simple goals (settings success check, tap_sequence, find scrolls, reusable screenshot, small-change detection, approval for force stop / clear data).
- **alpha.93:** Grok voice first, one-tap Accessibility Repair, home-screen safety zone, cache-friendly compaction (100k ceiling), typing read-back settle.
- **alpha.94:** the new Cyclone logo everywhere (launcher icon + themed layer, in-app mark, notifications/tiles/overlay, Glass switcher + favicon, Windows exe/setup icon).
- **alpha.95:** the Drive orb is always there while Driver mode is on (own attach, surviving settings watcher, OrbKeeper repairs every 2 s).
- **alpha.96:** Cyclone Ports in Glass (plan 48 run 1): Port Hub in the gateway, Ports page, Add plugin sheet, plugin drawer; Glass 1.0.0-alpha.53.
- **alpha.97:** the Port map (plan 48 run 2): bindings per port everywhere/routine/app, conflicts, unavailable; Glass 1.0.0-alpha.54.
- **alpha.98:** Ports carry real traffic (plan 48 run 3): out/in traffic, deliver endpoint, waits across restarts, Activity and test runs; Glass 1.0.0-alpha.55.
- **alpha.79:** plan 43 T1, Cyclone Tables. The owner chose plan 43's tables and buttons (T1–T3) before B1, so B1
  comes after T3.
- **Test counts at alpha.68:**
  - phone: 2096 tests, 0 failures;
  - gateway suite: passes;
  - MCP: 188 tests;
  - CI guards: 222.
- **Physical acceptance:** UNVERIFIED for everything since the web-only PC. The owner's own tests (plan 35) gate
  "done". Never say something works on the phone unless the owner reports it.

## 2. How every build ships (the checklist)

1. **Start fresh:**
   - `git fetch origin claude/cyclone-v5-handoff-review-9qrs40`;
   - fast-forward;
   - check that nothing is uncommitted.
2. **Build** in the owning paths (AGENTS.md §Ownership). Stay off paths the other agent is changing: look at
   `git log -5 --stat` first. The AI screen (`ui/v32/CycloneV39AiChatPage.kt`, the rain and scene files) is theirs
   right now; touch it only for a small wiring change, and say so in the commit.
3. **Tests and guards:**
   - unit tests next to the code;
   - a guard in `scripts/ci/tests/test_<topic>_guard.py` for every law the build must never break;
   - Lab missions in `apps/device-gateway/cyclone_device_gateway/lab/missions.py` when behaviour on the phone changes.
4. **Validate:**
   - phone: `cd apps/mobile && ./gradlew :app:testDebugUnitTest`;
   - gateway: `python -m pytest apps/device-gateway/tests -q`;
   - MCP: `python -m unittest discover -s tools/codex-phone-mcp/tests`;
   - guards: `python -m pytest scripts/ci/tests -q`;
   - Glass (if touched): `cd apps/glass && npm ci && npm test && npm run build`, then `python scripts/ci/glass_guard.py`.
5. **Versions** (all must agree; `python scripts/ci/release_versions.py --check` proves it):
   - `release/version.toml`: `product_version`, `components.mobile`, `android_version_code` +1, `python_version`,
     `components.device_gateway`, `components.mcp`;
   - the header comment and `candidate_generation`;
   - `apps/mobile/app/build.gradle.kts`: `versionCode` and `versionName`;
   - the three `pyproject.toml` files: `apps/device-gateway`, `tools/codex-phone-mcp`, `tools/cyclone-agent-mcp`;
   - `components.glass` and `apps/glass/package.json` only when `apps/glass` changed.

   Then run `python scripts/ci/mobile_product_guard.py`.
6. **Docs:**
   - `docs/RELEASE_<mobile>.md`: what changed, safety and privacy, validation and limits, and the physical checks
     owed;
   - an **As built** section in the plan;
   - the plan 35 row marked built;
   - the plan index row in `Cyclone V5 plan/README.md`.
7. **Commit:**
   - end the message with the session's trailers;
   - no model names in commits, docs or code.
8. **Before pushing,** list workflow runs on the branch. **Never push while a `v5-publish` or Mobile CI run is in
   progress:** Mobile CI cancels in-progress runs per branch and the publish waits on it.
9. **Push** with `git push -u origin claude/cyclone-v5-handoff-review-9qrs40`.
10. **After the publish,** verify:
    - the release is `v<product_version>`;
    - `release-manifest.json` has `source_sha` equal to your commit;
    - every file's SHA-256 matches;
    - `apksigner verify --print-certs` shows signer
      `e78c6e0b32d66da05839243c65e9987ba54722d402543f00408b57b1585dbf60` (never make a new key);
    - `aapt2 dump badging` shows the new version code.

    Tools are in `/opt/android-sdk/build-tools/34.0.0/`.

## 3. Laws no build may break

- **Phone actions:** `PhoneToolExecutor` is the only thing that changes the phone.
- **Task buttons:** every task button on every surface goes through Task Kit (`TaskCommands.send`). Guarded by
  `test_mobile_task_kit.py` and `test_steer_divert_guard.py`.
- **Approvals** stay for pay, send, delete, grant, sign-in and post.
  - A new feature may **add words** to an approval (as plan 38's "Changed course" note does).
  - It never skips one, auto-approves one, or batches approvals of different kinds.
- **No secrets anywhere:** no passwords, codes, keys, card numbers or typed secret values in memory, learning stores,
  recipes, traps, diagnostics, logs, the Lab or the model.
  - Use `MindMemory.looksSecret` and `MindRedaction` on anything learned.
  - The vault stays zero-knowledge: the runtime only ever sees ciphertext.
- **The app's own words only, never your content:** anything learned from screens (recipes, traps, manuals) keeps
  only the app's own words. It passes the dictionary's chrome filter, and the plan 36 canary guard covers it.
- **No generic shell or root for the model or the coordinator.** Local MCP servers run only after the owner approves
  an exact, pinned command.
- **Accounts:** only accounts the owner owns or manages. CAPTCHAs, ID checks and human verification always go to the
  owner.
- **Stay put:**
  - Shizuku stays; never rebuild a self-granting privileged engine.
  - JEV stays parked until the car test earns it.
- **Owner's style:** push back on AI ideas that don't pay off. The model decides most things itself (plan 38 D3);
  the owner is asked only when a mistake can't be undone.

## 4. The builds, in the recommended order

Each brief gives the goal, the code to start from, what to build, how to prove it, and the traps.

### B1. Recipes, traps, speed; workspace promotion (plan 37 W4) — next free number (alpha.71)

**Goal:** past missions make the next one faster. When the Lab shows the workspace wins, it becomes the default.

**Start from:**
- **Workspace (the phone's per-mission context):** `mind/workspace/` (`MissionWorkspace`, `ExpectCheck`,
  `DoneCheck`, `ScreenDelta`, `Surfaces`), with its modules 3 and 7 in `MissionWorkspace`.
- **Existing learning:** `mind/learn/` (`MissionTrail`, `MissionLearner`, `LearnedHints`). Reuse the trail, don't
  write a second recorder.
- **Scorer:** `AbilityIndex` (plan 36) scores goal similarity.
- **Word filter:** the dictionary's chrome filter keeps only the app's own words.
- **Memory:** `MindMemory` and `KeystoreMemorySealer` show the tidy-store and sealing patterns to copy.

**Build (plan 37 §9):**
1. `mind/learn/MissionRecipes.kt`:
   - **Stored when:** a mission ended COMPLETED with its done checks met.
   - **Contents:** the goal shape (verbs and app names only), the apps in order, and the key moves as app words.
   - **Retrieved:** 1–3 recipes by goal similarity into module 3, as "Last time a mission like this worked like: …".
   - **Stored where:** sealed on the phone, like memory.
2. `mind/learn/AppTraps.kt`:
   - **Becomes a trap:** the same surprise kind on the same screen in two missions.
   - **Shown:** as a line in module 7 for that app.
   - **Cleared:** the owner can clear them per app in Settings.
3. **Prefetch:** when a plan step is done and the next one names another app, build that app's module 7 on a
   background thread.
4. **Prompt caching:** breakpoints already exist (`MindModel.withCacheBreakpoints`, only for `anthropic/` models:
   one on the system prompt, one on the newest user message). What's missing:
   - check that the workspace's live-state tail (rebuilt every turn, never stored) sits **after** the last
     breakpoint, so it never breaks the cached prefix;
   - `MindUsage` has no cached-token field yet: read the provider's cached or cache-read tokens from the usage block,
     add `cachedTokens`, and report it in the Lab metrics and `lab/stats.py`.
5. **Promotion (plan 37 §11):**
   - **The run:** a Lab experiment with ≥30 trials per arm (`context: classic` vs `workspace`) on `smoke`, `core`,
     `map`, `hands`, `planes`, `multiapp`, `long` and `divert`.
   - **If every rule holds:** make the workspace the default for owner missions (the `mind_workspace_enabled`
     default in `MindMissions`), keeping the setting as an off switch.
   - **The Lab runs on the owner's phone and needs them:** build the switch, write the experiment into the release
     notes, and don't flip the default without results.

**Tests and guards:**
- **Unit tests:**
  - recipe extraction keeps only app words;
  - a canary string on screen never reaches a recipe;
  - retrieval ranks correctly;
  - a trap only after two sightings;
  - clearing works.
- **Guard `test_recipes_guard.py`:**
  - recipes and traps pass the chrome filter and `looksSecret`;
  - Lab missions never write recipes (as memory does);
  - recipes and traps are sealed at rest.

**Traps to avoid:**
- A recipe must never carry a recipient, a message text or an amount. Keep verbs and app words, never values.
- `Did` lines in stay journals can hold typed text. Filter them.
- Don't let recipes turn into scripts: they are hints in module 3, and the model still decides.

### B2. Fleet health and alerts (plan 33 §10–11) — alpha.72

**Goal:** the owner sees which phones are healthy, and hears when something goes wrong.

**Start from:**
- **Heartbeat:** `desktop_runtime/fleet.py`. There is already a `bridge.status` heartbeat and `last_heartbeat_ms`;
  extend it, don't add a second channel.
- **Phone ops:** the phone's gateway adapters in `apps/mobile/.../gateway/`.
- **Command Center store:** `command/center.py` (SQLite, the scheduler, account locks) and `command/api.py` (the
  `/v1/cc` routes).
- **Dashboard:** Glass pages in `apps/glass/src/pages` (`devicesPage.ts`, `commandPage.ts`).

**Build:**
1. **Heartbeat payload:**
   - every 30 s;
   - battery, temperature, storage, network, Cyclone and app versions, background capability.
   - Numbers and versions only; nothing from screens.
2. **Quarantine:** after 3 infrastructure failures in a row (offline, lease expired). Only the owner releases it. The
   dispatcher skips quarantined phones.
3. **"Behind on updates":** compare each phone's version with the latest release.
4. **Metrics:** success rate per recipe, account, phone and app version; time to pick up; approval wait; connection
   spend.
5. **Alerts:**
   - phone offline over 10 minutes;
   - a recipe's success under 80%;
   - a login failing twice;
   - a cap reached.

   Delivered as a notification on the owner's phone (through the existing notification path), and by email only if
   the owner sets it up (a connector, never stored SMTP passwords in plain text).

**Tests:**
- gateway tests for the heartbeat schema (reject extra fields), quarantine counting and release, metric rollups and
  alert rules;
- Glass tests for the devices page.

**Traps to avoid:**
- Don't count task failures (the app was blocked, wrong room) toward quarantine; only infrastructure causes count
  (plan 33 §5).
- Alerts must not repeat every 30 s. Fire once, then clear.

### B3. Coordinator, the rest (plan 33 §7–8) — alpha.73

**Goal:** the AI project manager (built in alpha.62) gets budgets, reports, an evaluation suite, and an MCP door.

**Start from:**
- **The coordinator:** `command/ai.py` (`AiStore`, fixed tool schemas, caps).
- **Pages:** `command/pages.py` and `pagetext.py`.
- **Agent adapter:** `tools/cyclone-agent-mcp/`.

**Build:**
1. **Budgets:** tasks per day and connection credits per day, set by the owner. The coordinator stops and asks when
   either is reached.
2. **`reports.write`:** a daily or weekly page (what ran, what failed and why, what's next), exportable as PDF or CSV.
3. **Lab coordinator suite:** given a goal, it must create the right tasks and schedules, stay within budget, and
   refuse accounts it doesn't own. It runs on every coordinator prompt change.
4. **Cyclone as an MCP server:**
   - Claude or Codex can create tasks through `tools/cyclone-agent-mcp`;
   - the same approvals apply;
   - no vault access;
   - no approving.

**Traps to avoid:**
- The coordinator must never read vault values, approve, change members, enrol phones or add connections.
- Run text reaches it as quoted, untrusted data.
- Extend the existing guard for this rather than weakening it.

### B4. Smarter routines (plan 33 §5–6) — alpha.74

**Goal:** routines that match real life, with approvals that don't flood the owner.

**Start from:**
- **Schedules:** `command/schedule.py` has only `daily` (with weekdays) and `every` (N minutes), and states that
  missed runs are never replayed. Keep that rule.
- **Approvals inbox:** in `command/center.py`.

**Build:**
1. **New schedule kinds:** `monthly` (a day of the month) and `nth` ("first Monday", "last Friday").
   - Stay small and checkable; don't pull in full RRULE unless it's needed.
   - Local time, with DST covered by tests.
2. **Triggers:**
   - a notification (matched on the phone by app and a phrase in the app's words);
   - an email (through a connected mailbox);
   - a webhook (a per-routine secret URL on the runtime);
   - another task's result.
3. **Batched approvals** ("Approve all 3 posts"): only for identical kinds, each payload shown.
4. **Timeouts per approval kind** (defaults: 1 hour for sends, 24 hours for sign-ups). When one times out, the run
   pauses; it never auto-approves.

**Traps to avoid:**
- A notification trigger must not become a way for any app to start tasks. Match only the owner's configured
  app + phrase, and treat the content as data.
- Webhook bodies are untrusted.

### B5. Vault, complete (plan 33 §4.3–4.5) — alpha.75

**Goal:** the vault covers the whole account life cycle.

**Start from:**
- **Gateway:** `command/vault.py` and `command/delivery.py` (ciphertext only, leases).
- **Glass:** `apps/glass/src/pages/vaultView.ts` and the WebCrypto core.
- **Phone:** the Hpke open, the Keystore device key and sealed delivery (alpha.55).

**Build:**
1. **Passkey unlock:** WebAuthn in Glass, which is served at `localhost`, so WebAuthn allows it.
2. **"Remember on this phone":**
   - the item stays sealed with the phone's Keystore key;
   - the owner can revoke it remotely.
   - Plan 33 §13.2 is an open owner decision; ask before making it the default.
3. **SMS and email codes:**
   - read on the phone;
   - filled;
   - never stored, reported or sent to the model;
   - only for accounts the owner allowed.
4. **Rotation as a recipe:**
   1. make the new value;
   2. change it in the app;
   3. log in again to verify;
   4. commit;
   5. keep the old version 7 days for rollback.
5. **Sign-up for accounts the owner owns:**
   - a `grant` approval;
   - the phone generates the password;
   - it seals the password back to the vault's public key;
   - CAPTCHAs, SMS and ID checks always go to the owner.
6. **Encrypted export:** Argon2id.

**Traps to avoid:**
- This build has the most secret surface. Before starting, read §4.6 ("Where secrets must never appear") and extend
  its guards first.
- Codes read from SMS never enter the Mind's conversation.

### B6. Mission desk and Drive finish (plans 21, 32) — alpha.76

**Goal:** long text is written once and delivered exactly, and Drive gets its Lab suite.

**Start from:**
- **Hands:** plan 21, built in alpha.41: the delivery ladder and focused typing in `PhoneMindToolbox`.
- **Drive:** plan 32; the voice pipeline is under `apps/mobile/.../voice/`, the AI mode overlay in
  `ui/overlay/AiModeOverlay.kt`, and its settings in `ui/v32/DriveSettings.kt`.
- **Lab:** the Lab `hands` suite.

**Build:**
1. **The mission desk:** a scratchpad where the model writes a long text once. Then:
   - delivery is checked character for character;
   - a retry reuses the same text.
2. **The Drive Lab voice suite.**
3. **JEV promotion** only if the owner's car test earned it. Otherwise JEV stays parked. Ask the owner.

### B7–B8. Hosted, multi-site (C6, plan 33 §12) — two runs

**Blocked on owner decisions:** whether it is wanted at all, where it runs, who pays, and which SSO (plan 33 §13.3,
plan 35 "Decisions still open"). Don't start until the owner answers.

**Shape if yes:**
- a cloud Command Center (Postgres, a durable job engine) with the PC as an outbound-only edge;
- members and roles, SSO;
- the vault stays zero-knowledge.

### B9–B10. 5.0.0-rc.1 → 5.0.0 / Glass 1.0 (plan 9)

- **Hardening:** plan 9's exit criteria:
  - a secrets fill;
  - a Gmail map;
  - a Chrome-host map;
  - an Ask using a live slot;
  - overlay yield;
  - the pay-block GATE.
- **Release channels:**
  - a signed Windows installer (needs the owner's code-signing certificate; today `CI_UNSIGNED`);
  - a stable channel next to development.
- **The last physical pass** on the Pixel and Windows, done by the owner.

## 5. Smaller leftovers (fold into a nearby build)

| From | Leftover | Fits with |
|---|---|---|
| alpha.68 | The branch in the Glass run inspector (`runPage.ts`, from the record's `metrics.divert.versions`) and Glass charts for the `divert` Lab metrics (`lab/stats.py` already returns them) | B1 (it touches the Lab and Glass anyway) |
| alpha.68 | Re-derive done checks from a steered goal (`PlanVersions` + `DoneCheck`) | B1 |
| alpha.68 | A red "Tap again to stop" state on the overlay stop (`ComposerStopGesture`, `OverlayRequestAction`) | Any phone build |
| alpha.67 | Editing a memory in place; a memory view in Glass; the `people` Lab suite | B1 |
| alpha.65 | The Lab concurrency suite; a plane pill per task | B2 |
| alpha.59 | The screenshots switch (plan 36) | Any phone build |
| Command Center asks | Links across pages, synced blocks, page properties; Button blocks and board automations; Notion polish | Not scheduled: ask the owner where they go |

## 6. Housekeeping owed now (a small docs-only commit)

- **Plan 35:**
  - "Where we are" still calls the mission workspace unbuilt.
  - Its release numbers predate alpha.69–70.
  - Renumber B1–B10 from alpha.71.
- **Plan index (README):**
  - row 36 still says "the rest planned", but M1–M3 are built;
  - row 37 says "W4 planned" (correct).
- **Docs only:** a docs-only push doesn't bump versions and doesn't start a publish. The publish runs only on
  `release/version.toml` changes, but Mobile CI still runs, so check for runs first.

## 7. Where the owner's decisions are needed

1. Whether to promote the workspace to the default, after the Lab run (B1).
2. Email alerts: which mailbox or connector (B2).
3. "Remember on this phone" as the default (B5; plan 33 §13.2).
4. JEV promotion after the car test (B6).
5. Hosted: yes or no, where, who pays, which SSO (B7–B8).
6. A Windows code-signing certificate, and when the stable channel starts (B9–B10).
7. Where the unscheduled Command Center asks go.

Ask these with a recommendation, not an open question. Everything else the agent decides and reports.
