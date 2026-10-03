# Plan 45: Run Ports, plugins that work inside a Cyclone run

Status: research and design (2026-10-01). Nothing here is built yet. Visual explainer: the "Cyclone Run Ports"
artifact.

Plugin builders: the contract `cyclone.ports/1`, an SDK, a Dev Hub, a conformance checker and three example plugins
are in `tools/cyclone-ports-sdk/`. Start at `HANDOFF-build-a-connector.md` (one connector) or
`HANDOFF-run-ports-plugins.md` (several). The design review, with a confidence score, is plan 47.

## 0. The ask and what exists

The owner wants multi-step automations inside a run, with plugins he writes himself (or that exist) on the PC or
elsewhere. Examples:
- a run sends a screenshot or the account's fields (name, date of birth) to a logger;
- an SMS plugin receives a verification text on one of the owner's other phones, and the run uses the code;
- an image on the PC lands on the phone in the middle of a run.

What exists and is reused:

| Piece | Plan | What it gives |
|---|---|---|
| Connections | 34 | MCP and OpenAPI connectors, per-tool rules, results as artifacts, outside results framed as data |
| Task steps | 34 M3 | `steps` that run before the phone, `{stepN.field}` filled into the goal |
| Sealed delivery | 33 C2 | a value sealed to one phone's Keystore for one task, single-use leases |
| Account Setup | 43 | Sign-up Map, rows of values, verification points, Verification desk, boundaries in code |
| Mind tools | 16/37 | `owner_ask`, `vault_fill`, `signup_page`, check-in card |
| Run history | 11 | every run's events, decisions and tool calls |

**The gap:** a plugin can act before a run starts, but nothing in the middle of a run can **send** something to a
plugin or **wait** for something from one.

## 1. The idea: ports on a run

A **port** is a named, typed point on a run where data goes out to a plugin or comes in from one.
- The run (the Mind, Account Setup, the Pilot or a recipe step) uses ports by name.
- **The Port Hub** in the PC gateway routes each message to the plugin bound to that port for this run, and back.
- The phone never talks to a plugin directly. Plugins never talk to the phone directly.

```
Phone run ──port.emit──▶ Port Hub (gateway) ──▶ plugin (MCP connector, local HTTP plugin, webhook)
Phone run ◀─port.await── Port Hub (gateway) ◀── plugin (deliver)
```

## 2. The port catalog (fixed types, so plugins are interchangeable)

| Port | Way | Carries | Sensitivity | Example |
|---|---|---|---|---|
| `run.event` | out | lifecycle: started, page, step, needs-you, done, failed | public | dashboard, Slack, logger |
| `screen.shot` | out | PNG of the current screen + page key | personal | QA log, audit trail |
| `account.fields` | out | the row's typed fields (name, date of birth, email, username), never passwords | personal | CRM, sheet |
| `page.text` | out | the visible text of the page (redacted) | personal | parser, translator |
| `file.out` | out | a file the run made or downloaded | personal | PC folder |
| `log.line` | out | a short note the run writes | public | any |
| `file.in` | in | a file for the phone (image, PDF), saved to the run's folder | personal | PC image picker |
| `value.in` | in | text or JSON the run asked for (a caption, an address) | personal | any |
| `code.in` | in | a verification code (SMS or email) | **secret** | SMS plugin, mail plugin |
| `secret.out` | out | a password the run generated, or an API key shown once on a page, sealed on the phone | **secret** | the PC vault (default), a password manager |
| `secret.in` | in | a vault item released to the phone for one run (sealed delivery lease) | **secret** | the PC vault, unlocked by you in Glass |
| `link.in` | in | a confirmation link from the owner's own inbox, opened in the run | personal | mail plugin |

**Out** ports are fire-and-forget with delivery receipts. **In** ports are always awaited with a timeout.

## 3. The envelope (every message, both ways)

```json
{
  "v": 1,
  "runId": "run_8f2c", "taskId": "task_41", "rowId": "row_7",
  "port": "screen.shot", "way": "out", "seq": 12,
  "sentAt": "2026-10-01T14:03:22Z",
  "app": "com.instagram.android", "pageKey": "signup:birthday",
  "sensitivity": "personal",
  "data": { "width": 1080, "height": 2400 },
  "artifactId": "art_93a1"
}
```

- Files travel as artifacts (`artifactId`), never inline.
- `code.in` carries no code in the envelope the gateway keeps: the code is sealed to the phone (§6).

## 4. The standard endpoints

### 4.1 What a plugin implements
- `GET /cyclone-plugin.json`: the manifest (name, version, ports it serves, ways, the data it needs).
- `POST /ports/{port}`: receives an out-port envelope (+ artifact URL with a one-time token).
- For in ports, either:
  - it calls `POST {gateway}/v1/ports/{runId}/{port}/deliver` with the run's port token; or
  - as an MCP server it exposes `cyclone_port_wait(port, runId, match)`, which the hub calls and which returns when
    data is ready (long-poll).

Remote plugins get the same contract over HTTPS: envelopes are HMAC-signed and the hub verifies the reply.

### 4.2 What the phone run uses
- **Mind tools:**
  - `port_send(port, note?)`: screen, fields and files are taken by Cyclone, not typed by the model;
  - `port_wait(port, timeout_s, match?)`: returns the data, or for `code.in` "filled" (the model never sees a code).
- **Account Setup:** a verification point in the Sign-up Map can be bound to a `code.in` port. The run waits on it and
  fills the code field itself, like `vault_fill`.
- **Recipes (§5):** declarative `emit` and `await` steps.
- **Gateway protocol ops:** `port.emit`, `port.await`, `port.cancel`, `port.status`.

### 4.3 What the owner uses (Glass)
- Plugins → **Add plugin**: a manifest URL, a local folder, or an existing connection.
- On a task, routine or Sign-up Map, set **Ports**: which plugin serves each port, with per-port consent.
- The run timeline shows each port message (sent, delivered, waited, received, timed out).

## 5. Recipes: multi-step automations inside a run

A recipe is a run plan that mixes phone steps and port steps. The Mind handles anything the recipe doesn't.

```yaml
recipe: instagram-signup
uses: { map: instagram.sign_up, row: required }
ports:
  run.event:    { plugin: my-logger }
  screen.shot:  { plugin: my-logger, when: [page, failed] }
  code.in:      { plugin: sms-plugin, from: "+31 6 •••• 4821", timeout: 180s }
steps:
  - emit: run.event { stage: started }
  - map: pages 1-3          # fill from the row
  - emit: account.fields     # to the logger, no password
  - verify: sms              # waits on code.in, fills the field
  - approve: final-submit    # always the owner
  - emit: run.event { stage: created }
```

## 5b. Share secret: passwords and API keys into the PC vault

The Command Center vault (plan 33) is zero-knowledge: Glass encrypts in the browser and the PC holds only ciphertext.
Plan 33 §4.4 already has the phone seal a new password to the vault's public enrolment key. Run Ports make that a
port, and extend it to API keys and any one-time secret a run meets.

- **`secret.out` (phone → vault):**
  - **Sources:** a password the phone generates in the vault layer, or a value on screen (an API key shown once)
    that the Mind points at by ref.
  - **Sealing:** Cyclone reads the value itself and seals it on the phone to the vault's public key
    (ECDH P-256 + AES-256-GCM, with run, app and slot as associated data).
  - **What the Mind gets back:** "sealed to your vault", never the value.
- **The hub** checks the envelope, never the value. It writes a `vault_item` (kind `password`, `api_key`, `totp`,
  `token`) with `created_by: run`, linked to the account and the run.
- **Opening it:**
  - only Glass with the passphrase can open the item;
  - viewing needs a passkey step-up and shows the value for 30 seconds, audited.
- **`secret.in` (vault → phone):** releasing an item for a run is the existing sealed delivery. The owner unlocks it
  in Glass, it's sealed to the phone's Keystore, used once, and filled with `vault_fill`.
- **Secret sinks:** a plugin may act as a password manager (Bitwarden, 1Password CLI) only with an explicit tick.
  The phone then seals to that plugin's pinned public key. Without the tick, secrets go only to the vault.
- **Plugins can store, not read.** A plugin that receives a new key from a service's API seals it to the vault's
  public key. Reading a secret back always goes through the owner in Glass.

## 5c. Other connections worth adding (ranked)

Build first:
1. **The owner's email inbox** (IMAP, Gmail, Outlook): `code.in` and `link.in` for email verification.
2. **Authenticator (TOTP) in the vault:** seeds in the vault produce the 2FA code on the PC, sealed to the phone at
   login (`code.in`).
3. **A password manager** (Bitwarden, 1Password): `secret.in` / `secret.out` through their CLIs.
4. **Sheets and databases** (Google Sheets, Airtable, Notion): rows in, handles and status out.
5. **Notifications** (Telegram, Discord, Slack, ntfy): "needs you" and "created" pings with a link to the approval.

Next:
6. **Automation hubs** (n8n, Make, Zapier, Home Assistant): start runs from a webhook (under task rules), events back.
7. **Files** (a PC folder, OneDrive, Drive): `file.in` / `file.out`.
8. **Image and text generators** (Higgsfield, a local model): profile picture, banner, bio. Shown to the owner
   before use.
9. **Clipboard bridge:** text only, cleared after use, never secrets.

## 6. Boundaries (code, not a model)

- **The owner adds every plugin** and grants each port per plugin. Remote plugins get no `personal` port without an
  explicit tick.
- **Secrets:**
  - `code.in` codes are sealed to the phone (plan 33 sealed delivery), single-use, expire after 5 minutes and are
    bound to the run, port and app;
  - they are never stored, never written to the run history or diagnostics, and never shown to a model;
  - passwords and API keys travel only on `secret.out` / `secret.in`, sealed end to end; the PC stores ciphertext it
    cannot open.
- **Codes only from the owner's own numbers or inboxes.** An SMS source is a phone or number the owner registers and
  confirms (plan 43 §6.3).
- **Plugins can't approve.** Final submits, payments, sends, deletes and permissions stay with the owner. A plugin's
  data is quoted, untrusted content, never an instruction.
- **Account Setup's limits still apply:**
  - only accounts the owner owns or manages;
  - no regulated identity (KYC) apps;
  - no device spoofing, no rotating fake details, no bulk creation beyond what the app allows;
  - CAPTCHA, selfie and ID checks go to a person.
- **Audit:** every port message (metadata only) is in the run history; a plugin's manifest is pinned and re-approved
  when it changes.

## 7. Build plan (estimate)

| Run | Scope |
|---|---|
| P1 | Port Hub in the gateway: envelope, catalog, `port.*` ops, out ports `run.event` / `screen.shot` / `log.line`, the local HTTP plugin contract, manifest pinning, the Glass Plugins page, a sample "logger" plugin |
| P2 | In ports: `file.in` (saved to the run folder, MediaStore) and `value.in`; Mind tools `port_send` / `port_wait`; timeline view |
| P3 | `code.in` with sealed delivery; SMS source registration; Account Setup verification points bound to ports; sample SMS plugin (Android SMS forwarder on the owner's other phone → PC) |
| P4 | Share secret: `secret.out` sealed to the vault's public key (plan 33 §4.4 as a port), `secret.in` on sealed delivery, capture by ref for API keys, secret-sink plugins with pinned keys |
| P5 | Recipes (YAML + Glass editor), MCP `cyclone_port_wait`, remote plugins with HMAC, connector cards for plugins |
