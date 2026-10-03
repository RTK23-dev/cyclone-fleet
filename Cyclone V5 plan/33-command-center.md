# 33 — Cyclone Command Center: the final plan

**Status:** final plan, 2026-09-27. **C0 built in alpha.51** (§12.1), **C1 in alpha.54** (§12.2), **C2 in alpha.55** (§12.3), **C3 with pre-authorised leases in alpha.56**
(§12.4), **C5 Pages with the workspace redesign in alpha.61** (§12.5), **C4's AI project manager in alpha.62** (§12.6); the rest of C4
and C6 not started. Physical acceptance of C0–C3 is UNVERIFIED.

**The owner's ask:**
> "Credentials can be safely sent to the phone in tasks, and managed and saved in the dashboard. It should hold all
> this data safely and manage my accounts, new tasks and routine automations in one dashboard. AI should be able to
> manage the fleet of phone agents doing legitimate work on the phones."

**In one sentence:** Glass grows into a Command Center with Accounts, Tasks, Routines, Phones, Results, an Approvals
inbox, Connections and an AI coordinator. It sits on:
- a zero-knowledge vault;
- a durable job engine;
- a **sealed delivery** path that sends a secret to one phone for one task.

The gateway, the dashboard's server and every model only ever see ciphertext or slot names.

---

## 0. Decisions (locked unless the owner changes them)

| # | Decision | Why |
|---|---|---|
| D1 | **Local-first.** The Command Center runs in the Cyclone PC runtime you already install (`cyclone`, plan 31), with its data on your PC. A hosted version reuses the same code later (C6). | Your passwords never leave hardware you own; no cloud needed to start; one codebase. |
| D2 | **Zero-knowledge vault.** Items are encrypted with keys only you (and phones you enrolled) can unlock; the runtime stores ciphertext. | A stolen PC disk or database leaks nothing usable. |
| D3 | **Sealed delivery (new contract).** A phone receives a secret only as an HPKE-sealed envelope (RFC 9180) addressed to its hardware-bound device key, for one task, once. The gateway relays bytes it cannot open. | Keeps plan 05's rule "the gateway cannot fetch, accept or forward a secret *value*" while letting the dashboard hand phones credentials. |
| D4 | **Models never see values.** The Mind and the AI coordinator work with vault references (`vault:ig-brand/password`) only; filling happens in the phone's vault layer (`useOnce` → `SecretFillExecutor`). | The prompt-injection and log-leak surface stays at zero. |
| D5 | **Approvals stay human.** GATE (pay, send, delete, grant) and new-login approvals go to one Approvals inbox; no AI can approve. | The existing safety boundary, now fleet-wide. |
| D6 | **Owned accounts only.** Each account records its owner and its basis ("mine", "my company", "client under contract"). Automations run only on accounts in the vault. | Keeps the product the legitimate kind; sets up audit. |
| D7 | **Glass stays intelligence-free.** The AI coordinator is a runtime service with a fixed toolset; Glass only renders and commands (the Glass guard stays). | The architecture law since plan 03. |

## 1. What the owner gets

- **Accounts:** every account you own, its vault items, which phones may use it, its 2FA method, its health (weak,
  reused, old, login failing) and its history.
- **Tasks:** "Post this video to @mybrand at 18:00 with this caption", with a target (a phone, or any phone with the
  app), the accounts allowed, the connections allowed and a due time. The status is live.
- **Routines:** tasks on a schedule or trigger (for example every weekday 07:30, or when an email arrives), with pause
  and run-now.
- **Phones:** live tiles showing online, battery, storage, Android and Cyclone versions, apps, and the current task.
  A live screen is one click away (scrcpy, as today).
- **Results:** every run with its outcome, steps, masked screenshots, files produced, cost and cause of death, linked
  to the run inspector.
- **Approvals:** one inbox for every phone's questions, sends, logins and codes, answerable from the dashboard, the
  phone or a notification.
- **Connections:** outside tools over MCP (Higgsfield first), each with OAuth, a spending cap and an approval rule.
- **Coordinator:** "Every Monday make a 10-second product video, post it to both brand accounts and send me a
  report." It plans, schedules, watches and writes the report. You approve what GATE requires.

## 2. Architecture

```
PC (your Cyclone runtime: `cyclone`)                             Phones (Cyclone Mobile)
┌──────────────────────────────────────────────────────────┐    ┌───────────────────────────┐
│ Glass (web UI, no intelligence)                           │    │ Mind + PhoneToolExecutor   │
│   pages · databases · approvals · live screens            │    │ GATE · Owner Moments        │
│ Command API (/v1/cc/*, bearer + passkey session)          │◄──►│ Vault layer (Keystore):     │
│ Job engine (durable, SQLite; Postgres in hosted mode)     │    │   device key (HPKE recv)    │
│ Vault service (ciphertext store, leases, audit chain)     │    │   useOnce → fill → revoke   │
│ Coordinator service (model + fixed tools, no secrets)     │    │ Job agent: inbox, lease,    │
│ MCP gateway (OAuth grants, caps, artifacts)               │    │   heartbeat, run reports    │
│ Artifact store (content-addressed files)                  │    └───────────────────────────┘
└──────────────────────────────────────────────────────────┘      ▲ USB / Wi-Fi / cloud-phone link
                          ▲ (C6) outbound mTLS to a hosted Command Center for multi-site
```

**Rules:**
- **Phones do the work and decide how; the Command Center assigns and records.** A phone that loses its link finishes
  its task and queues the report.
- **Every command is idempotent** (a request id). "Done" means verified by the phone (transport success is not task
  success).
- **One store per kind of data:**
  - secrets in the vault;
  - schedules in the job engine;
  - results in the run store;
  - pages link to them and never copy them.
- **Hosted mode (C6)** adds a cloud Command Center. The PC becomes an **edge** that dials out; the vault stays
  zero-knowledge, so the cloud holds ciphertext only.

## 3. Data model

| Table | Key fields | Notes |
|---|---|---|
| `workspace` | id, name | One for you; more for teams or clients in hosted mode |
| `member` | id, workspace, role (`owner`, `admin`, `operator`, `viewer`), passkeys | Only owner and admin may see vault values, and only in the UI after a passkey step-up |
| `device` | id, name, model, android, cyclone_version, device_pubkey (HPKE), enrolled_at, status, labels, last_heartbeat | The public key comes from Keystore at enrolment |
| `account` | id, service (app package + web domain), handle, owner_basis, twofa (`none`/`totp`/`passkey`/`sms`/`email`/`app`), allowed_devices, status, health, notes | No secret values |
| `vault_item` | id, account, kind (`password`/`totp_seed`/`recovery_codes`/`note`), ciphertext, item_key_wrapped, version, created_by, rotated_at | Encrypted client-side; `created_by` records provenance (you, a task, an import) |
| `connection` | id, kind (`mcp`), url, oauth_grant (encrypted), allowed_recipes, daily_cap, approval (`always`/`over_cap`/`never`) | Higgsfield = `https://mcp.higgsfield.ai/mcp` |
| `recipe` | id, version, goal template, inputs, allowed tools, required apps, success check | Existing recipes (plan 19), versioned and immutable per version |
| `task` | id, recipe@version, inputs, target (device or selector), accounts, connections, due, status, idempotency_key | |
| `routine` | id, task template, schedule (RRULE) or trigger, paused, next_run | |
| `run` | id, task, device, attempt, status, started/ended, cause, cost, step count | Linked to the phone's run record |
| `artifact` | sha256, kind, size, source (run, step or connection), storage path | Content-addressed |
| `approval` | id, run, kind (`question`/`values`/`send`/`pay`/`delete`/`grant`/`secret`/`login`), payload (exact text), state, answered_by, answered_at | One row per Owner Moment |
| `lease` | id, vault_item, device, run, expires_at, used_at, state | One-use |
| `audit` | seq, at, actor, action, object, prev_hash, hash | Append-only hash chain |

## 4. The vault and sealed delivery (the core)

### 4.1 Keys

- **The vault key (VK),** 256-bit.
  - It is wrapped by a key derived from your **passkey** (WebAuthn PRF extension) on supported browsers, or from a
    master passphrase with Argon2id (m=64 MiB, t=3, p=1) as a fallback.
  - **A recovery kit** (a printed or saved 24-word phrase) is a second wrap of VK, created at setup.
  - VK only exists unwrapped in the browser tab's memory while the vault is unlocked (auto-lock after 10 minutes).
- **Item keys (IK):** one per vault item, wrapped by VK. Items are encrypted with XChaCha20-Poly1305 using the item id
  and version as associated data, so items cannot be swapped between accounts.
- **Device keys (DK):**
  - each phone creates an X25519 or P-256 key pair in Android Keystore (StrongBox when available) at enrolment;
  - the private key never leaves the chip;
  - the public key is registered and shown as a fingerprint that you confirm on both screens (as with pairing today).
- **The runtime holds only ciphertext and wrapped keys.** It cannot decrypt.

### 4.2 Sealed delivery (secret → phone, one task, one use)

1. **The lease is prepared in your browser.** When a task that uses `vault:ig-brand/password` is started, and the vault
   is unlocked in your browser, the browser:
   - decrypts the item (VK → IK → value);
   - seals it with HPKE to the target phone's DK, binding the associated data `{run_id, lease_id, device_id, slot,
     expires_at}`;
   - wipes the plaintext.
2. **A routine can run while you're away** by choosing, per routine, **pre-authorised leases.** At schedule time the
   browser seals the next N leases in advance; each is valid only for that run's time window. If none is prepared, the
   run waits with a `login` approval.
3. **The runtime** stores the sealed envelope with the lease, and relays it to the phone with the job over the existing
   gateway channel. The payload schema is fixed: `{lease_id, enc, ct, aad}`, base64 only.
4. **The phone:**
   - opens the envelope with DK inside Cyclone's vault layer;
   - checks the associated data matches its current run, its device id and the time;
   - stores the value as a **transient slot** (`SealedSecret`, the same record type as today);
   - fills through the existing `useOnce` → `OneShotSecretLease` → `SecretFillExecutor` path, confirmed by reading the
     field back;
   - revokes the lease;
   - reports `lease.used`.
5. **Replay is impossible:** the lease id is single-use on both sides, and the time window is enforced by both.

**Optional "remember on this phone":** you can let the phone keep the secret in its own Keystore vault as a normal
slot. It is then used offline like today's vault slots, and still revocable from the dashboard (the next heartbeat
deletes it).

### 4.3 Codes and two-factor

- **TOTP (only if you store the seed):** the seed travels sealed like a password. The phone computes the code at fill
  time, and the code is never sent or stored.
- **Passkeys and app approvals:** the run raises a `login` approval ("Approve on your device").
- **SMS or email codes:** a `values` approval asks you. Alternatively, if you connected that mailbox or allowed
  notification reading for that account, the phone reads the code locally, fills it, and never stores or reports it.

### 4.4 New accounts you sign up for

1. The task raises a `grant` approval: "Sign up to service X as handle Y?"
2. On yes, the phone generates the password inside the vault layer (a 20-character random password, adjusted to the
   site's rules).
3. It fills the form and seals the new value **back** to the browser. The phone encrypts to your vault's public
   enrolment key, so the runtime still sees only ciphertext.
4. The new `account` and `vault_item` rows appear with `created_by: task`.
5. Verification steps (CAPTCHA, ID, SMS) always go to you as approvals; Cyclone does not try to get around them.

### 4.5 Viewing, rotating, exporting

- **Viewing** a value in Glass needs a passkey step-up, is shown for 30 seconds and is audited.
- **Rotation** is a recipe:
  1. make the new value;
  2. the phone changes the password in the app;
  3. the phone verifies it by logging in again;
  4. the vault commits the new version;
  5. the old version is kept 7 days for rollback.
- **Export** is an encrypted file (age or JSON with Argon2id), and import supports Bitwarden or 1Password CSV
  (converted in the browser, then encrypted).

### 4.6 Where secrets must never appear (CI-guarded)

**The places:**
- run records;
- Brain or learning stores;
- diagnostics;
- screenshots (password fields are masked on the phone before capture leaves it);
- model prompts;
- MCP arguments;
- gateway access logs;
- Glass `localStorage` or `sessionStorage`;
- crash reports.

**The guards:**
- the existing Glass guard;
- plan 05's forbidden list;
- a new `test_cc_vault_boundaries.py`, which checks that the runtime vault code has no decrypt function and that the
  sealed-envelope schema is the only secret-bearing message;
- an end-to-end test that injects a canary password and greps every output (logs, DB, artifacts, prompts) for it.

### 4.7 Threat model

| Threat | Mitigation |
|---|---|
| PC stolen or database copied | Ciphertext only; VK never at rest unwrapped |
| Runtime compromised | It can relay and delay but not decrypt; leases are bound to device and run; the audit chain shows tampering |
| Phone stolen | Transient secrets are gone after use; remembered slots are Keystore-bound behind the screen lock; remote revoke on the next heartbeat; enrolment can be revoked |
| Prompt injection from app content | Models never hold values; tools can't read the vault; GATE approvals are human |
| Rogue AI plan | The coordinator's tools can't approve, can't change account permissions and can't read secrets; there are spending caps; everything is audited |
| MCP server misuse | Per-connection allowlist, caps and approval rules; OAuth tokens held by the gateway only; no secrets in arguments |
| Lost passkey | Recovery kit; a second passkey encouraged at setup |

## 5. Tasks, routines and the job engine

**Local mode:** a durable queue in SQLite (the transactional outbox pattern) inside the runtime. **Hosted mode (C6):**
Temporal. The same task and run states apply to both.

**Task states:** `draft` → `scheduled` → `waiting_device` → `running` → (`needs_you`) → `succeeded` | `failed` |
`cancelled`.

**Assignment:**
1. Filter phones that are online, have the required apps, are allowed for the task's accounts, are healthy, and are
   not busy (or have a free background session once parallel sessions exist).
2. Take the **account lock**: one phone per account at a time.
3. Prefer the phone that last succeeded with that account.

**Retries:**
- the job engine retries only on infrastructure causes (the phone went offline, or a lease expired before use);
- task causes (for example `wrong-room` or `app-blocked`) go to Results with the fix suggested by the inspector;
- the coordinator may retry once.

**Routines:**
- RRULE schedules in local time, and triggers (a notification, an email, a webhook, another task's result);
- **missed runs are not replayed**; the next run is shown instead;
- pause-all is one switch.

**The phone side:** the job agent in Cyclone Mobile receives the task, starts a Mind mission with the recipe's goal
and inputs, streams step events, and reports the run record at the end. It uses the existing mission journal, so it
survives restarts.

## 6. The Approvals inbox

- **Source:** every Owner Moment from every phone, plus `login`, `grant` and connection-spend approvals, lands as an
  `approval` row with its **exact payload** (plan 32's readback rule: what you approve is exactly what is sent).
- **Answering:** Glass, the phone's own card or notification, or Drive by voice (sends only).
- **The same Task Kit command** reaches the owning mission (`TaskCommands.send`); the first answer wins.
- **Timeouts:** per kind, with a default of 1 hour for sends and 24 hours for sign-ups. When it times out the run
  pauses; it never auto-approves.
- **Batching:** "Approve all 3 posts" is allowed only for identical kinds, with each payload shown.

## 7. The AI coordinator

- **Where it runs:** a runtime service using your OpenRouter key and model choice (the existing catalogue), never in
  Glass.
- **Its tools** (fixed JSON schemas, audited):
  - `accounts.list` (names and health only);
  - `devices.list/status`;
  - `recipes.list/get`;
  - `tasks.create/update/cancel`;
  - `routines.create/pause/resume`;
  - `runs.list/get` (masked);
  - `artifacts.list`;
  - `connections.list`;
  - `reports.write` (a page);
  - `approvals.list` (read only).
- **It cannot:** read vault values, approve anything, change account permissions or members, enrol phones, add
  connections, or run any shell.
- **Budgets:** tasks per day, and connection credits per day, both set by you. It stops and asks when either is
  reached.
- **Untrusted content:** run text and app content are passed as quoted data with the existing untrusted-content framing.
- **Evaluation:** a coordinator suite in Lab. Given a goal, it must produce the right tasks and schedules; must not
  exceed budgets; and must refuse to act outside the owned accounts. It runs on every coordinator prompt change.

## 8. Connections (MCP), with Higgsfield first

- **The MCP gateway** in the runtime, starting with the existing `/v1/pc` patterns:
  - it registers servers (URL, transport);
  - it runs the OAuth 2.1 flow in your browser and stores the grant encrypted (a vault item of kind `oauth`);
  - it proxies tool calls, enforcing allowlists, caps and approval rules;
  - it saves outputs as artifacts.
- **Higgsfield:**
  - add `https://mcp.higgsfield.ai/mcp` and sign in with OAuth (it uses your plan's credits);
  - the recipe step `generate_video{prompt, model, duration, aspect}` produces an artifact (the file plus its prompt);
  - a later phone step `post_video{artifact, account, caption}` pushes the file to the phone (media store) and posts
    it through the app, with a `send` approval.
- **Open-source gateway choice:** start with the runtime's own proxy (small, fits the no-generic-shell rule). Evaluate
  IBM ContextForge or Obot for hosted mode when many connections and teams arrive.
- **The other direction:** the Command Center exposes itself as an MCP server (the same tools as the coordinator,
  behind the same approvals), so Claude or Codex can create tasks.

## 9. Dashboard UX (Glass)

- **New sections:** Accounts, Tasks, Routines, Results and Approvals, next to Phones (today's Devices), Connections
  (today's Remote MCP and Marketplace connections) and Knowledge.
- **Databases:** table, board, calendar and gallery views; filters saved per view; bulk actions (pause and resume
  only; never bulk approve different kinds).
- **Pages (C5):** a block editor (BlockNote on Yjs) with live blocks for a task, routine, run list, phone tile,
  approval queue or artifact gallery. Templates: "Weekly content", "Daily app check", "Password health".
- **Vault UI:** unlock (passkey), an item list without values, reveal-with-step-up, a generator, a health view,
  import and export.
- **Design:** Glass's web design system (Teal Matrix, plan 03). Tilt Glass stays on the phone's surfaces.

## 10. Phones in the fleet

- **Enrolment:** the existing pairing (a code plus Allow on the phone), extended to:
  - create the DK in Keystore and show its fingerprint on both screens;
  - install the job agent's permissions through the setup cards (plan 30).
- **For company-owned phones:** optional Android Management API or Headwind MDM enrolment for silent installs and
  policies.
- **Health:** a heartbeat every 30 s (battery, temperature, storage, network, app versions, background capability).
  **Quarantine** after 3 infrastructure failures in a row, released by you.
- **Updates:** Cyclone app updates through the existing release lane; the Command Center shows which phones are behind.
- **Cloud phones:** the existing Attach flow (plan 31) registers them as devices with the same DK enrolment.

## 11. Observability and reports

- **Per run:** the phone's run record (steps, decisions, cause) and masked screenshots, linked from Results.
- **Metrics:**
  - success rate per recipe, account, phone and app version;
  - time to pick up;
  - approval wait time;
  - connection spend.
- **Reports:** the coordinator writes a daily or weekly page (what ran, what failed and why, what's next), and you can
  export it as PDF or CSV.
- **Alerts:** a phone offline for more than 10 minutes, a recipe success rate below 80%, a login failing twice, a spend
  cap reached. Delivered by notification on your phone, or by email.

## 12. Releases

Each step ships through the fast lane with release notes, honest limits and new CI guards. Physical acceptance is
stated as owed until you test it.

| Release | Contents | Exit criteria |
|---|---|---|
| **C0: Command Center shell** | Glass sections Accounts (metadata only), Tasks, Routines, Results, Approvals across all connected phones; tasks start existing recipes on a chosen phone; one Approvals inbox fed by Owner Moments; SQLite job engine with account locks | One routine runs on two phones on schedule for 3 days, results listed, approvals answered from Glass |
| **C1: Vault** | Zero-knowledge vault in the browser (passkey PRF or passphrase), items, generator, health, import/export, audit chain; no delivery yet (phones still use their own slots) | Canary test: no plaintext in DB, logs or runtime memory dumps; a restore from the recovery kit works |
| **C2: Sealed delivery** | Device keys at enrolment, HPKE leases, phone job agent opens → `useOnce` → revoke, pre-authorised routine leases, TOTP at fill time, "remember on this phone", remote revoke | A task logs in on a phone that never had the password; canary absent everywhere; replayed envelope refused; expired lease refused |
| **C3: Connections** | MCP gateway with OAuth, caps, approvals, artifacts; Higgsfield recipe (generate, then post with approval) | The "make and post a video" routine runs end to end with the approval shown; the cap stops a runaway loop |
| **C4: Coordinator** | The coordinator service with fixed tools, budgets, daily report page, the Lab coordinator suite | The suite passes; a week of reports matches the Results data |
| **C5: Pages** | Block editor with live blocks, templates, saved views | Owner builds a "Weekly content" page from a template |
| **C6: Hosted and multi-site** | Cloud Command Center (Postgres, Temporal), PC as an outbound edge, members and roles, SSO | A second site's phones run tasks from the cloud; the vault is still zero-knowledge (the cloud DB holds ciphertext only) |

### 12.1 C0 as built (alpha.51)

- **Phone:** `cc.start` / `cc.status` / `cc.answer` (`GatewayV5CommandAdapter`). A task is an ordinary Mind mission
  (`MindMissions.startAssigned`). Answers go through Task Kit; approve only for the moment's request id, only when
  nothing in it is redacted; secure input and handover never from the PC.
- **Gateway:** `command/` holds one SQLite file (`runtime/command/command.db`) with account, routine, task, run,
  approval and a hash-chained audit. A job loop runs every 5 s: fire routines (no replay), dispatch to paired and
  ready phones with the account lock, follow runs, and mirror Owner Moments into approvals. Routes are
  `/v1/cc/{overview,accounts,tasks,routines,results,approvals,audit}`, all behind the bearer.
- **Glass:** **Command Center** (`#/command/<tab>`): Approvals, Tasks (recipes from a phone's Marketplace), Routines,
  Results, Accounts.
- **Deferred from the §12 row:** schedules are "daily at HH:MM on weekdays" or "every N minutes" (no RRULE or
  triggers yet); the inbox holds Command Center tasks only (not tasks started on the phone); no retries after a
  mission started (no double posts).

### 12.2 C1 as built (alpha.54)

- **Crypto (browser, WebCrypto only):**
  - VK = 32 random bytes, wrapped with PBKDF2-SHA256 (≥ 600,000 iterations, 16-byte salt) → AES-256-GCM, and again
    with a 32-byte recovery key.
  - Per item version: a random item key wrapped with the VK; fields sealed with AES-256-GCM, AAD
    `cyclone-vault/v1/item/<id>/<kind>/<version>`.
  - The account id is inside the ciphertext too; a changed plain link is flagged, not trusted.
  - Argon2id and XChaCha20 were replaced by WebCrypto-native primitives: Glass has no runtime dependencies.
- **Gateway:** `command/vault.py` holds `vault_meta` and `vault_item`: ciphertext, salt, KDF parameters, kind, account
  link and version only. Routes are `/v1/cc/vault/{init,rewrap,items,import,items/<id>/delete,restore,reset,audit}`.
  Versions are strictly increasing; restore goes into an empty vault only; client-side events (unlock, show, copy,
  export) go into the audit chain.
- **Glass:** Command Center → **Vault**: create with a recovery key, unlock (passphrase or recovery key), items
  (login, authenticator, recovery codes, note), a generator, health, show/copy/code behind a 2-minute step-up, a
  5-minute idle lock, Bitwarden JSON / CSV import, encrypted backup and restore, change passphrase, new recovery key.
- **Exit test run:** a canary absent from the DB, WAL, logs, backup and runtime process memory; restore on a second
  runtime unlocked with the recovery key.
- **Deferred:** passkey (WebAuthn PRF) unlock, because Glass runs at 127.0.0.1 and WebAuthn needs a domain name;
  planned for C6 or a local hostname.

### 12.3 C2 as built (alpha.55)

- **Device key:**
  - An Android Keystore P-256 `PURPOSE_AGREE_KEY` pair (`DeviceKey.kt`). StrongBox when present; an agreement
    self-test falls back to the TEE if StrongBox can't agree.
  - Fingerprint: the first 16 bytes of SHA-256 over the raw key. It is shown in phone Settings → Vault and recomputed
    in Glass.
  - `cc.key` returns the public half; the gateway keeps `device_key` with `trusted_at`, set by the owner.
- **HPKE:**
  - RFC 9180 base mode, DHKEM(P-256, HKDF-SHA256) / HKDF-SHA256 / AES-256-GCM; info `cyclone-sealed-delivery/v1`.
  - Glass seals (`hpke.ts`, WebCrypto) and the phone opens (`Hpke.kt`, ECDH inside Keystore).
  - Both are tested against RFC 9180 A.3.1 and a shared fixture. A third implementation (Python) was used in the
    end-to-end run.
- **Bound data (the AAD):** `{deviceKey, expiresAt, leaseId, place, slot, taskId}`.
  - `place` is added beyond §4.2: a delivered secret fills only on its account's app or site.
  - Slots are `password` and `otp`; `otp` is the authenticator seed, turned into a code on the phone.
- **Leases:**
  - Glass seals for tasks listed by `/v1/cc/leases/pending`. The gateway checks every bound field against the task
    and stores `lease` rows (`ready → delivered → used | failed | unused | expired | rejected`, or
    `revoked`/`replaced`).
  - A task with a `vault_item_id` starts only with a ready lease, and the envelopes ride in `cc.start`.
  - The phone opens them before the mission starts (all or nothing), holds the values in memory for that mission,
    and fills through `OneShotSecretLease` from `AndroidMindPorts.fillSecret` ahead of the Secrets Card. It reports
    outcomes in `cc.status.leases`.
  - Replay is refused on both sides (the phone keeps accepted lease ids only).
- **Deferred:** pre-authorised leases for routines that run while Glass is closed; "remember on this phone"; SMS and
  email code relay.

### 12.4 C3 as built, with pre-authorised leases (alpha.56)

- **MCP client** (`command/mcp.py`):
  - Streamable HTTP: JSON-RPC over POST, answered as JSON or an event stream, with `Mcp-Session-Id`.
  - Only `initialize`, `notifications/initialized`, `tools/list` and `tools/call` are sent (CI-guarded).
  - URLs are https, or http to this PC only. Redirects are not followed.
- **OAuth 2.1:**
  - Discovery: RFC 9728 protected-resource metadata, then RFC 8414 / OIDC authorization-server metadata.
  - RFC 7591 dynamic registration of a public client, PKCE S256 and RFC 8707 `resource`.
  - Glass opens the sign-in page in a new tab. The redirect lands on
    `/v1/cc/connections/oauth/callback`, the one Command Center route without the bearer: it accepts only a
    single-use 10-minute state and needs the verifier the gateway kept.
  - Refresh happens one at a time.
- **Grant storage (a change from §8):** the grant is **not** a vault item of kind `oauth`. The gateway must use the
  token while Glass is closed, and a zero-knowledge vault item can only be opened in the browser. So:
  - tokens live in `connections.dpapi`, sealed with Windows DPAPI for this Windows user, and only in memory on other
    systems;
  - they are kept apart from the database, and never go to Glass, the audit or any model (CI-guarded).
- **Rules** (`command/connections.py`):
  - A per-connection allowlist: no tool is allowed until ticked.
  - A daily cap on calls.
  - An approval rule: `always` (the default), `over_cap` or `cap`.
  - At most one call per connection waits for an OK, and two run at once.
  - Spend approvals go in the same Approvals inbox (`kind: spend`), approved or declined in Glass.
  - Arguments are plain values, screened for secrets.
- **Artifacts:**
  - Files come from image/audio content, embedded resources, `resource_link`s and media URLs in text or
    `structuredContent`.
  - Downloads are https-only from public addresses, re-checked on every redirect, up to 500 MB.
  - Files are stored under `artifacts/<sha256>`.
  - Asynchronous jobs are polled every 10 s for up to 20 minutes, with a status tool the owner picks. The job id is
    read from common keys.
- **Make, then post:**
  - A task or routine may carry `make {connectionId, tool, arguments, pollTool, then}`.
  - With `then: keep`, the task ends with the file.
  - With `then: post`, the file goes to the chosen phone in 256 KB `cc.media` chunks. The phone checks the whole
    file's SHA-256 before adding it to its gallery (Movies/Pictures/Music `Cyclone`).
  - The mission then starts with `publish: true`. For that mission only, the phone's `GateClassifier` treats
    Share / Post / Publish / Upload as a SEND gate (`PublishGate`), so the post waits for the owner's OK.
- **Pre-authorised leases (§4.2 step 2):**
  - A routine with a vault login on one phone may set `preauth` (0–7).
  - The gateway reserves the task ids of the next N runs (`routine_slot`) and lists them in `/v1/cc/leases/pending`
    with `ahead: true`.
  - Glass seals each one to that run's task id, expiring 30 minutes after the run is due, up to 8 days ahead
    (checked on both sides).
  - When the routine fires, the task takes the reserved id, so the phone accepts the lease for that run only.
  - A changed, paused or deleted routine revokes its prepared leases.
  - A vault run with no lease waits and raises a `login` request in the inbox, which clears itself when a lease
    arrives.
- **Not yet:**
  - Higgsfield's real tool names and output format were not verified against the live server. The step uses
    whichever tools the owner allows and picks.
  - Credit-based caps (calls are counted, not credits).
  - The coordinator's `connections.list` (C4).

**Start with C0 → C2.** That is the core of the ask: one place for your accounts and tasks, and phones that log in
for a task without the password ever being visible to anything but you and that phone.

### 12.5 C5 as built: Pages and the workspace redesign (alpha.61)

Built ahead of C4 at the owner's request ("a Notion-like Command Center"). The editor is Cyclone's own (DOM APIs, no
dependency), which keeps the Glass guard; this settles plan 35's open Pages decision.
- **Store** (`command/pages.py`, `PageStore` on `CommandCenter.pages`):
  - `page(id, parent_id, title, icon, blocks, plain, position, version, created/updated, archived_at)` and
    `page_ref(page_id, kind, target)`.
  - **Blocks are typed and validated:**
    - text (`p h1 h2 h3 todo bullet number quote callout`) with spans `{t, b?, i?, c?, s?}` or `{ref}`;
    - `divider`;
    - `ref` (one card);
    - `view` (source `tasks routines approvals phones pages results accounts connections`, a layout, a filter);
    - `board` (`items[{id, title, status todo/doing/done, due, refs, note, taskId}]`, layout `board/table/calendar`).
  - **References** are `{kind page/device/skill/routine/task/account/connection, id (per-kind pattern), label}`.
  - **Limits:** 1000 blocks, 1 MB, 500 cards, 10 deep, 5000 pages.
  - **No secrets:** `INLINE_SECRET` refuses text, labels and titles that hold one.
  - **Saves:** `update` needs the current `version`; a stale save raises `PageConflict`, which the route turns into a
    409 `PAGE_CHANGED`. `_index` rewrites `page_ref`, and `backlinks(kind, id)` reads it.
  - **Search:** `LIKE` over title and a `plain` text column.
  - **Moving and the trash:** `move` checks for cycles and depth, and `before` places a page among its siblings.
    `archive` / `restore` take a subtree; `delete` works only from the trash.
  - **Templates** (`blank`, `weekly`, `daily`, `content`) are built server-side.
  - **Audit:** create, rename, move, archive, restore and delete; content edits are not audited.
- **Routes:** `/v1/cc/pages` (tree and templates; create), `/v1/cc/pages/{id}` (get; save), `/move`, `/archive`,
  `/restore`, `/delete`, `/v1/cc/pages-trash`, `/v1/cc/pages-search?q=`, `/v1/cc/backlinks?kind=&id=`. All behind the
  bearer.
- **Glass shell:**
  - `core/router.ts`: `#/command` is the workspace home, `#/command/page/<id>` a page, `#/command/trash` the trash, and
    `modeOf(route)` gives the face.
  - `app.ts`: the top-left `brand-switch` shows the current face's logo in front (`ui/logos.ts`: `cycloneLogo`, the
    phone app's arcs, and `commandLogo`). Pressing it goes to the other face at its last route. The face swaps the
    sidebar: the Glass nav without a Command Center item, or `workspace/sidebar.ts`. Ctrl/⌘K opens quick find.
- **Workspace** (`src/workspace/`):
  - `sidebar.ts`: search, Home, Inbox with a count, the databases, and the page tree (open/close, + inside, ⋯ menu:
    rename, move to top level, trash). Dropping a page onto another nests it.
  - `home.ts`: a greeting, template tiles, waiting approvals, recent pages, running tasks, upcoming routines, phones.
  - `pageView.ts`: breadcrumb, icon picker, title, the editor, "Pages inside" and "Mentioned in". Autosave after
    600 ms carries `version`; a 409 reloads; a 4xx waits for the next edit; network errors retry.
  - `editor.ts`:
    - contenteditable text blocks read back with `readSpans`; mentions are `contenteditable=false` chips;
    - caret arithmetic with a mention counted as one character (`splitSpans`, `deleteRange`, `insertRef`,
      `textBefore` in `services/pages.ts`);
    - the `/` menu (`SLASH_ITEMS`), `@` mentions from `directory.ts` (`loadDirectory`, cached 15 s), and markdown
      shortcuts;
    - Enter splits (lists continue; an empty item ends the list), Backspace at the start joins, and arrows move
      between blocks;
    - Ctrl+B/I/E and Ctrl+Shift+S; pasting lines makes blocks;
    - drag by the handle, and the ⋮⋮ menu: turn into, duplicate, move, delete.
  - `views.ts`: live views with list, table, board, calendar and gallery layouts (`renderRows`, shared with the
    Tasks database), refreshed every 10 s.
  - `plan.ts`: plan boards with drag between columns and onto calendar days, and a peek dialog per card (status,
    date, links, notes). "Send to a phone" is `command.createTask` with the linked phone and account.
  - `quickFind.ts` and `trash.ts`.
- **Databases in the workspace:** `createCommandPage(ctx, tab, {workspace: true})` gives a clean header (Inbox,
  Tasks, …) with the create form behind New, and Tasks as a table, board or calendar.
- **Tests and checks:** `tests/test_command_pages.py` (5), `tests/workspace.test.mjs` (14), and a guard
  (`test_pages_are_typed_blocks_without_secrets`).

### 12.6 C4, first part, as built: the AI project manager (alpha.62)

Moved forward at the owner's request ("a project-managing AI you can instruct and choose the model for, with OpenRouter
inside the dashboard"). It is the §7 coordinator's core, in the runtime (D7); Glass only shows it.
- **Key and models** (`command/ai.py`, `AiStore` on `CommandCenter.ai`):
  - The owner's OpenRouter key is saved once in the connections key store (DPAPI on Windows). It is checked with
    OpenRouter's `/key` endpoint first, and is never returned, logged, audited or put in a model's context.
  - The model list comes from `/models` (cached for an hour), with prices, context sizes and tool support. Only
    tool-using models can be chosen. There is a default model, and each conversation can pick another.
  - `provider.data_collection = deny` is sent by default ("Private providers only").
- **Conversations:**
  - A turn runs in the runtime: at most 10 model calls and 8 tool calls per step.
  - It stops at the daily or monthly cap, which is counted from the cost OpenRouter reports.
  - A turn that a restart cuts off is marked as failed.
  - What is kept is the owner's messages, the answers, the tool calls and results, and the cost. Hidden reasoning is
    never read or kept.
  - Notes about applied or discarded proposals reach the model on its next turn only.
- **Tools** (fixed JSON schemas, audited):
  - Reads:
    - pages: list, search, read (as Markdown with block ids);
    - tasks, routines, results, phones;
    - accounts (names and health only), connections (names and allowed tools);
    - approvals (read only).
  - Workspace edits:
    - create a page;
    - append to a page, update one block, rename a page;
    - add or update a plan card, with links.
  - Phone work, always proposed:
    - create a task;
    - create, run, or pause/resume a routine.
  - There is no tool for delete, approve, vault, accounts, adding connections or a shell.
- **The owner decides:**
  - Page and card edits are proposals unless the owner picks "Let it edit pages and cards".
  - Phone work is always a proposal, applied by the owner.
  - An applied task then runs like any task, with the phone's usual approvals.
- **Pages as Markdown** (`command/pagetext.py`): the AI writes simple Markdown with `@[kind:id]` mentions.
  - The Markdown becomes typed blocks and goes through `validate_blocks`, so the no-markup and no-secrets rules hold.
  - Unknown ids are refused back to the model.
  - Previews and answers show the current names of what they mention.
- **Glass:**
  - Ask AI is a slide-over panel, docked beside the page on wide screens. It opens with Ctrl J, from the sidebar, from
    "✨ Ask AI" on a page, or with "/ Ask AI" in the editor.
  - The panel has page context, suggestions, a steps list, proposal cards (Apply / Start it / Discard), a model per
    conversation, the costs, and history.
  - AI settings holds the key (masked, write-only), the model picker, the caps with spending bars, the choices, the
    standing instructions, and what the AI can and cannot do.
  - The provider's name reaches Glass only as data, so the Glass guard's rule ("no model or provider calls") still holds.
- **Pages kept in step:**
  - An open page checks its version every 4 s and reloads in place when it is quiet, keeping the caret.
  - A save refused because the page changed elsewhere is merged block by block (`mergeBlocks`) and saved on top.
    The server still refuses stale saves.
- **Still to do for the rest of C4:**
  - budgets in tasks per day;
  - the daily and weekly report page;
  - the Lab coordinator suite;
  - Cyclone as an MCP server;
  - action buttons and automations (plan 35).

## 13. Open decisions for the owner

1. **Pre-authorised routine leases** (routines log in while you're away), yes or no? Yes is convenient; no means every
   login waits for you to unlock the vault. The default in this plan is yes, per routine.
2. **"Remember on this phone"** as the default for your own phones? It is faster and works offline, but a secret then
   stays on the phone.
3. **Local-first only for now, or plan C6 soon?** Hosted mode is where teams and multiple sites come in.
4. ~~**The model for the coordinator**~~: decided in alpha.62. The owner picks any tool-using OpenRouter model (a
   default, and per conversation), on a separate key from the phone's.

## 14. Not in this plan

- Getting past sign-up protections (CAPTCHA, SMS or ID checks): these are always handed to you.
- Running accounts you don't own or manage.
- Generic shell or remote-code tools for the coordinator or MCP.
- Bulk approval of different kinds of actions.
