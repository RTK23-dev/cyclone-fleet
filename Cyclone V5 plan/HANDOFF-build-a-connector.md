# Handoff: build a connector for Cyclone Ports

**To the agent receiving this:** you are building a connector (a "plugin") that plugs external tools into Cyclone
runs and Cyclone Glass through **Cyclone Ports**, contract `cyclone.ports/1`. This file is everything you need. If it
passes the checker in §6, it works with Cyclone Ports, now with the Dev Hub and later with the real Port Hub, without
changes.

- Repository: `premiumcentraal-boop/Cyclone`.
- Kit: `tools/cyclone-ports-sdk/`.
- Full contract: `tools/cyclone-ports-sdk/SPEC.md`. If this file and SPEC.md ever disagree, SPEC.md wins.

> **Connector to build:** ______________________ (the owner fills this in; if it's blank, ask before starting)
> **Ports it serves:** ______________________ (pick from §3)

---

## 1. What Cyclone Ports is (60 seconds)

A Cyclone **run** is an automation on the owner's Android phone, such as an account sign-up. In the middle of a run it
can **send** data out to a plugin, or **wait** for data from one. All traffic goes through the **hub** in the owner's
PC gateway; the phone and your plugin never talk directly.

```
phone run ─emit──▶ hub ──signed POST /ports/{port}─────────────▶ YOUR CONNECTOR
phone run ◀─await─ hub ◀─POST {deliverUrl}, Authorization: Port <token>── YOUR CONNECTOR
```

Your connector is a small HTTP service that:
1. serves a manifest at `GET /cyclone-plugin.json` and `GET /health`;
2. receives **out** ports at `POST /ports/{port}`;
3. for **in** ports, receives `POST /ports/{port}/await`, answers `202` at once, and later `POST`s the data to the
   `deliverUrl` it was given; `POST /ports/{port}/cancel` stops the wait.

## 2. Setup (5 minutes)

```bash
git clone https://github.com/premiumcentraal-boop/Cyclone && cd Cyclone
python -m pip install -e tools/cyclone-ports-sdk             # Python 3.10+, no dependencies
python -m pytest tools/cyclone-ports-sdk/tests -q             # all green before you start
```

Put your connector in **`tools/cyclone-plugins/<your-name>/`** and change nothing outside it. If you think the SDK or
the spec needs a change, write it under "Contract feedback" in your README (§8). Don't edit `tools/cyclone-ports-sdk/`.

Start by copying the closest example:

| You serve | Copy | What it shows |
|---|---|---|
| out ports (events, fields, screenshots, files) | `examples/logger/` | receiving envelopes, de-duplicating on `id`, fetching one-time artifact links |
| `file.in` | `examples/pc_images/` | awaiting, delivering a file (base64 + sha256), cancel, the same `awaitId` twice |
| `code.in` / `link.in` | `examples/sms_plugin/` | a webhook of your own with a token, matching, codes in memory only, delivering |

## 3. Ports you can serve

| Port | Way | Sensitivity | Your connector… |
|---|---|---|---|
| `run.event` | out | public | gets `data.stage` (started, page, step, needs_you, created, done, failed, cancelled, **and maybe new ones later**) |
| `log.line` | out | public | gets `data.text` (≤ 500 characters) |
| `screen.shot` | out | personal | gets `artifactUrl` (a PNG, one GET, within 120 s) and `pageKey` |
| `account.fields` | out | personal | gets `data.fields` (name, dateOfBirth, email, username), never a password |
| `page.text` | out | personal | gets `data.text`, the visible page text with secrets hidden |
| `file.out` | out | personal | gets `artifactUrl` plus name, mime and size |
| `file.in` | in | personal | delivers `{v, deliveryId, name, mime, base64, sha256}` (≤ 20 MB) |
| `value.in` | in | personal | delivers `{v, deliveryId, value}` (any JSON, ≤ 64 KB) |
| `code.in` | in | **secret** | delivers `{v, deliveryId, code, source, from}`: a verification code from the owner's own phone or inbox |
| `link.in` | in | personal | delivers `{v, deliveryId, url (https), source}`: a confirmation link from the owner's inbox |
| `x.<your-name>.<thing>` | out or in | personal | your own **extension port**, when nothing above fits (SPEC §2.1) |

`secret.out` and `secret.in` (passwords and API keys) belong to the hub and the vault. **Don't serve them.**

## 4. The wire format you must speak

### Manifest (`GET /cyclone-plugin.json`)

```json
{
  "contract": "cyclone.ports/1",
  "name": "your-name",
  "version": "0.1.0",
  "title": "Human title",
  "description": "One line on what it does and what data it keeps.",
  "endpoint": "http://127.0.0.1:8790",
  "serves": [{"port": "value.in", "way": "in"}],
  "needs": {"personal": true},
  "features": ["idempotent-delivery"]
}
```

- Plain `http` is allowed only on loopback; use `https` anywhere else.
- `needs.personal` must be `true` if you serve any personal port.

### Requests the hub sends you (signed)

```
X-Cyclone-Signature: t=<unix s>,kid=<key id>,id=<request id>,v1=<hex HMAC-SHA256(secret, S)>
S = "<t>\n<request id>\n<METHOD>\n<path with query>\n<hex sha256 of the raw body>"
```

- Secret: `CYCLONE_PLUGIN_SECRET`, formatted as `k<N>.<secret>` (no prefix means `k1`). During a key rotation,
  `CYCLONE_PLUGIN_SECRET_NEXT` holds the second key: accept both.
- Answer **401** when:
  - the signature is wrong;
  - the `kid` is unknown;
  - `|now − t| > 300 s`;
  - the request id was seen in the last 600 s.
- Answer **404** for ports you don't serve, and **422** if the body's `port` differs from the path.
- In Python, `cyclone_ports.PluginServer` does all of this. In Node, use `tools/cyclone-ports-sdk/js/verify.mjs`.
  In any other language, implement it and prove it against `schemas/signature-vectors.json`.

**Out-port envelope** (`POST /ports/{port}`). Answer `202 {"received": true}` within 10 s:

```json
{"v":1,"id":"msg_…","runId":"run_8f2c","taskId":"task_41","rowId":"row_7","port":"run.event","way":"out",
 "seq":3,"sentAt":"2026-10-01T14:03:22Z","app":"com.example.app","pageKey":"signup:birthday",
 "sensitivity":"public","data":{"stage":"started"}}
```

**Await** (`POST /ports/{port}/await`). Answer `202 {"accepted": true}` at once and deliver later:

```json
{"v":1,"runId":"run_8f2c","port":"code.in","awaitId":"aw_5b1e09","match":{"from":"Instagram"},
 "timeoutS":180,"deliverUrl":"http://127.0.0.1:8770/v1/ports/run_8f2c/code.in/deliver","token":"q2…"}
```

**Cancel** (`POST /ports/{port}/cancel`) carries `{v, runId, port, awaitId, reason}`. Answer `200` and stop that wait.

### Delivering

```
POST {deliverUrl}
Authorization: Port <token>
Content-Type: application/json

{"v":1, "deliveryId":"dl_<random>", "value": "…"}
```

| You get | Do |
|---|---|
| `200` (also for a retry with the same `deliveryId`) | Done; forget the data. |
| `401`, `403`, `404`, `409`, `410`, `413` | Stop; don't retry. `403` means an unregistered source: tell the owner. |
| `422` | Your body is wrong (`error.problems`); fix it. |
| `429`, `5xx`, network error | Retry with the **same** `deliveryId`, backoff, honour `Retry-After`, until the wait's timeout. |

Errors always look like `{"error": {"code", "message", "retryable"}}`. Branch on the status, then on `error.code`.
`cyclone_ports.deliver()` does the `deliveryId` and the retries for you.

## 5. The rules that keep you compatible as Cyclone grows

These are the ones the checker tests. Breaking any of them breaks your connector on a future update.

1. **Ignore what you don't know:**
   - unknown fields anywhere;
   - unknown `run.event` stages;
   - unknown `match` keys;
   - unknown `features`.

   Never reject an envelope because of a value you don't recognise. Reject only broken structure.
2. **Expect repeats:**
   - the same envelope `id` can arrive twice, so de-duplicate;
   - the same `awaitId` can arrive twice: it's the same wait, so don't start a second one.
3. **Answer fast:**
   - `202` within 10 s;
   - do slow work on a background thread or queue;
   - never hold an await request open.
4. **Send `deliveryId`** and retry only what's retryable (§4).
5. **Use only the ports in your manifest.** Add new data as an extension port `x.<your-name>.<thing>`, never as an
   undeclared field on a catalog port.

## 6. Test it (this is your definition of "works with Cyclone Ports")

```bash
export CYCLONE_PLUGIN_SECRET=dev-secret
python tools/cyclone-plugins/<your-name>/plugin.py &          # or however your connector starts

# 1. the checker: the same checks the hub runs when the owner adds your connector
python -m cyclone_ports.conformance http://127.0.0.1:8790     # must end with "all required checks passed"

# 2. a real run against it: write tools/cyclone-plugins/<your-name>/scenario.json (see tools/cyclone-ports-sdk/scenarios/)
python -m cyclone_ports.devhub tools/cyclone-plugins/<your-name>/scenario.json \
  --plugin http://127.0.0.1:8790 [--source <label> for code.in/link.in]   # must exit 0
```

Scenario format: `{"run": {...}, "steps": [{"emit": "<port>", "data": {...}}, {"await": "<port>", "match": {...},
"timeoutS": 60}]}`. The Dev Hub prints each step's result. It never shows, keeps or logs a `code.in` code; it reports
"would be sealed to the phone".

## 7. Hard rules (security and privacy)

1. **Codes** (`code.in`):
   - memory only, at most 180 s, dropped once delivered;
   - never on disk, in logs, in analytics or in crash reports.

   Copy `test_signup_scenario_with_sms_code_never_keeps_the_code` from `tools/cyclone-ports-sdk/tests/test_ports.py`:
   it scans every file and all output for the code.
2. **Sources:** codes and links only from phones, numbers or inboxes **the owner registered**. Send that label as
   `source`.
3. **No secrets** (passwords, PINs, OTPs, API keys, card data) on any port. Your own credentials (IMAP app password,
   bot token, API key) live in environment variables or the OS keychain: never in the repo, the manifest or logs.
4. **Logs:** metadata only (run id, port, status, sizes). Never bodies, tokens, codes or artifact URLs.
5. **No control:** your connector can't and mustn't approve, pay, send, delete or grant permissions, or try to steer
   the phone through values. Cyclone treats your data as untrusted text.
6. **Network:**
   - listen on `127.0.0.1` unless you must accept a webhook;
   - protect every webhook of your own with a token;
   - use https off-machine.
7. **Account Setup limits:**
   - only accounts the owner owns or manages;
   - no KYC or identity apps;
   - no spoofing;
   - no bulk creation beyond what the app allows.

   CAPTCHA, selfie and ID checks always go to a person. Never build a solver.
8. Never expose a shell, root access or "run this command" through a connector.

## 8. Deliverables

In `tools/cyclone-plugins/<your-name>/`:
- `cyclone-plugin.json`;
- `plugin.py` (or `index.mjs`, …), with a `--port` flag and the secret from `CYCLONE_PLUGIN_SECRET`;
- `scenario.json`, playable by the Dev Hub. For in ports, also cover the timeout path: the run ends in `needs_you`;
- `tests/`, which cover:
  - matching;
  - an unsigned request gets 401;
  - de-duplication;
  - the same `awaitId` twice;
  - for anything secret, the never-stored scan;
- `README.md` with:
  - what it does;
  - setup and env vars;
  - what data it keeps, where, for how long, and how to delete it;
  - "Contract feedback" (anything in Ports that got in your way).

Report back:
- the checker output;
- the Dev Hub run output;
- the test results;
- what you verified for real (a real inbox, a real forwarder phone) and what is **UNVERIFIED**.

## 9. Ideas, ranked, if the owner hasn't named one

1. **Mail codes and links:** IMAP, Gmail or Outlook → `code.in` / `link.in`.
2. **SMS codes, production:** harden `examples/sms_plugin` with forwarder-app templates, several sources and rate
   limits.
3. **Notifications:** Telegram, Discord, Slack or ntfy ← `run.event` (`needs_you`, `created`, `failed`).
4. **Sheets:** Google Sheets, Airtable or Notion ← `account.fields`, `run.event`; → `value.in` (next username,
   captions).
5. **Files:** a PC folder, OneDrive or Drive ↔ `file.in` / `file.out`.
6. **Generators:** a local model or an image API → `value.in` / `file.in` (bio, caption, profile picture; the owner
   approves before use).
