# Cyclone Ports contract `cyclone.ports/1`

Status: **v1, frozen for plugin builders** (2026-10-01, hardened for evolution the same day). Design:
`Cyclone V5 plan/45-run-ports.md`. Review: `Cyclone V5 plan/47-run-ports-review.md`.

The Dev Hub in this kit implements the hub side of this contract. The gateway's Port Hub (plan 45 P1–P5) must pass the
same conformance suite before it ships. **A plugin that passes `cyclone-ports-check` against the Dev Hub works with
the real hub, and with every later `/1` hub, without changes.**

"Must", "must not", "should" and "may" are normative.

## 1. Parties

| Party | What it is |
|---|---|
| **Run** | A Cyclone task on the phone (the Mind, Account Setup, the Pilot or a recipe). It emits to ports and awaits ports by name. |
| **Hub** | The Port Hub in the PC gateway (or the Dev Hub). It routes each port to the plugin the owner bound, signs requests, issues port tokens, keeps the audit log, and seals secrets to the phone. |
| **Plugin** | Your HTTP service. It serves a manifest and some ports. It never talks to the phone. |

The phone never calls a plugin, and a plugin never calls the phone. Everything goes through the hub.

## 2. Ports

| Port | Way | Sensitivity | Plugin may serve | Payload |
|---|---|---|---|---|
| `run.event` | out | public | yes | `data.stage`: started, page, step, needs_you, created, done, failed, cancelled (**open list**, §9); optional `reason` |
| `log.line` | out | public | yes | `data.text`, at most 500 characters |
| `screen.shot` | out | personal | yes | `artifactUrl` (PNG), `data.mime`, `data.bytes`, optional `width`/`height`; `pageKey` |
| `account.fields` | out | personal | yes | `data.fields`: name, dateOfBirth, email, username… **never** a password, PIN, OTP, token, card or CVV |
| `page.text` | out | personal | yes | `data.text`: visible text, secret-shaped values already hidden |
| `file.out` | out | personal | yes | `artifactUrl`, `data.name`, `data.mime`, `data.bytes` |
| `file.in` | in | personal | yes | delivery: `name`, `mime`, `base64`, `sha256` (≤ 20 MB) |
| `value.in` | in | personal | yes | delivery: `value` (any JSON, ≤ 64 KB) |
| `code.in` | in | **secret** | yes | delivery: `code` (3–12 of `A-Z a-z 0-9 -`), `source`, `from` |
| `link.in` | in | personal | yes | delivery: `url` (https only), `source` |
| `secret.out` | out | **secret** | **no (v1)** | hub and vault only: a password or API key sealed on the phone to the vault |
| `secret.in` | in | **secret** | **no (v1)** | hub and vault only: a vault item released for one run |
| `x.<plugin>.<name>` | out or in | personal | yes, by its owner | **extension port** (§2.1) |

The table lives in code in `cyclone_ports/catalog.py`; the gateway imports the same file. New catalog ports are added
in minor revisions of `/1` (§9). A plugin only receives ports it lists in `serves`, so new ports never reach an old
plugin.

### 2.1 Extension ports

A plugin may define its own ports, without waiting for a contract change. They are named `x.<plugin name>.<name>`,
for example `x.crm.lead` or `x.crm.next-handle`.
- The `<plugin name>` must be the manifest's `name`, so no plugin can claim another's namespace.
- `way` comes from the manifest. Sensitivity is always **personal**, so `needs.personal: true` is required.
- An out extension port carries an object in `data`, and an in extension port takes a `value.in`-shaped delivery.
- Field names that look like secrets are refused, the same as on `account.fields`. Extension ports never carry codes,
  passwords or keys.
- A run, a recipe or a Skill Studio node uses an extension port by its full name. Extensions that prove generally
  useful get promoted into the catalog under a plain name.

## 3. Manifest: `GET /cyclone-plugin.json`

```json
{
  "contract": "cyclone.ports/1",
  "name": "sms-codes",
  "version": "0.1.0",
  "title": "SMS codes",
  "description": "…",
  "endpoint": "http://127.0.0.1:8773",
  "serves": [{"port": "code.in", "way": "in"}],
  "needs": {"personal": false},
  "features": ["idempotent-delivery"]
}
```

- `name`: `^[a-z][a-z0-9-]{1,40}$`, stable for the plugin's life. `version`: semver.
- `endpoint`: plain `http` only on loopback (`127.0.0.1`, `localhost`, `[::1]`). Anything else must be `https`.
- `serves`: ports from §2 with their fixed `way` (extensions declare theirs). `secret.out` and `secret.in` are
  refused.
- `needs.personal` must be `true` when any served port is personal. The owner then ticks each personal port for the
  plugin in Glass. A remote (https) plugin never gets a personal port without that tick.
- `features` (optional): what the plugin supports beyond the base contract. The hub uses a feature only when the
  plugin lists it, and both sides ignore names they don't know. Defined: `idempotent-delivery`. Reserved for P5:
  `pull`, `upload`, `mcp`.
- The hub **pins** the manifest when the owner adds the plugin. If a later fetch changes `serves`, `endpoint`, `needs`
  or `features`, the plugin is paused until the owner approves it again. A version bump alone is only logged.

`GET /health` must answer `200 {"ok": true, ...}`.

Schema: `schemas/manifest.schema.json`.

## 4. Hub → plugin requests (signed)

Every hub → plugin `POST` carries:

```
Content-Type: application/json
X-Cyclone-Contract: cyclone.ports/1
X-Cyclone-Signature: t=<unix seconds>,kid=<key id>,id=<request id>,v1=<hex HMAC-SHA256(secret, S)>
traceparent: 00-<trace id>-<span id>-01          (W3C Trace Context; optional to use, forward it if you call out)

S = "<t>\n<request id>\n<METHOD>\n<path with query>\n<hex SHA-256 of the raw body>"
```

- **Secrets and rotation.** The hub gives each plugin its own secret, shown to the owner once when they add the
  plugin, as `k<N>.<secret>`; a value without a prefix means key id `k1`. The plugin reads `CYCLONE_PLUGIN_SECRET`.
  To rotate, the owner sets `CYCLONE_PLUGIN_SECRET_NEXT` to the new key and restarts the plugin, which then accepts
  both. Glass switches the hub to the new `kid`, then the old variable is removed. The switch needs no downtime.
- A plugin **must** answer `401` when:
  - the signature is missing or doesn't match;
  - the `kid` is unknown;
  - `t` is more than 300 s from its clock;
  - the request id was already used (a replay; keep ids for 600 s).
- It must compare signatures in constant time. `cyclone_ports.PluginServer` does all of this.
- Because the signature covers the method and path, a request signed for `/ports/code.in/await` can't be replayed to
  `/cancel` or to another port.
- A plugin must answer `404` for a port it doesn't serve, and `422` when the body's `port` differs from the path.
- Test vectors for other languages: `schemas/signature-vectors.json`.

### 4.1 Out ports: `POST /ports/{port}`

The body is an envelope (`schemas/envelope.schema.json`):

```json
{
  "v": 1, "id": "msg_4c1d9e0a7b2f5e8c1a3d", "runId": "run_8f2c", "taskId": "task_41", "rowId": "row_7",
  "port": "screen.shot", "way": "out", "seq": 12, "sentAt": "2026-10-01T14:03:22Z",
  "app": "com.instagram.android", "pageKey": "signup:birthday", "sensitivity": "personal",
  "data": {"mime": "image/png", "bytes": 48213, "width": 1080, "height": 2400},
  "artifactUrl": "http://127.0.0.1:8770/v1/artifacts/art_93a1c2?t=…"
}
```

- Answer `202 {"received": true}` within 10 s, and do slow work after answering.
- **Delivery is at least once.** The hub retries timeouts, `429` and `5xx` with backoff and the same `id`, so
  de-duplicate on `id`. Ordering within a run is by `seq`, but retries can arrive out of order.
- **Read tolerantly.** Ignore unknown fields, and accept values you don't know (a new `run.event` stage, a new `data`
  key). The checker sends such envelopes and requires `2xx`. Reject (`422`) only an envelope whose structure is
  broken: missing `v`, `id`, `runId`, `port`, `way`, `seq`, `sentAt` or `sensitivity`, or `v` ≠ 1.
- **Artifacts** (`screen.shot`, `file.out`) travel as a one-time `artifactUrl`, never inline. The link works for one
  `GET` within 120 s and then answers `410`. Fetch it once and don't store the URL.
- Out ports are fire-and-forget for the run. A failing plugin never blocks or fails a run; the hub records the failure
  in the timeline.
- Busy plugins may answer `429` with `Retry-After` (seconds).

### 4.2 In ports: `POST /ports/{port}/await` and `/cancel`

When a run needs something, the hub asks the plugin (`schemas/await.schema.json`):

```json
{
  "v": 1, "runId": "run_8f2c", "taskId": "task_41", "rowId": "row_7", "app": "com.instagram.android",
  "port": "code.in", "awaitId": "aw_5b1e09", "match": {"from": "Instagram", "pattern": "\\b\\d{6}\\b"},
  "timeoutS": 180, "sentAt": "2026-10-01T14:03:40Z",
  "deliverUrl": "http://127.0.0.1:8770/v1/ports/run_8f2c/code.in/deliver",
  "token": "q2…"
}
```

- Answer `202 {"accepted": true}` at once, then deliver when you have the data (§5). Never hold the request open.
- **The same `awaitId` may arrive again,** for example when the hub restarts or retries. Treat it as the same wait:
  don't start a second one and don't reset its start time.
- `match` holds hints that depend on the port:
  - `code.in`: `from` (sender or app name, case-insensitive) and `pattern` (a regex for the code);
  - `link.in`: `from`;
  - `file.in`: `kind` (`image`, `pdf`…);
  - `value.in`: `ask` (what the run wants).

  Unknown keys must be ignored.
- `timeoutS` is at most 600. After it, the hub sends `POST /ports/{port}/cancel` with
  `{"v":1,"runId","port","awaitId","reason"}`. Answer `200` and forget the wait. A cancel can also come early: the run
  finished, the owner stopped it, or another source answered.
- The `token` is valid only for this `runId`, `port` and `awaitId`, until the timeout. Don't log it.

## 5. Plugin → hub: `POST {deliverUrl}`

```
POST /v1/ports/{runId}/{port}/deliver
Authorization: Port <token>
Content-Type: application/json
X-Cyclone-Contract: cyclone.ports/1
```

Bodies (`schemas/delivery.schema.json`). Every body may carry a `deliveryId` (8–80 characters) and should:

| Port | Body |
|---|---|
| `code.in` | `{"v":1, "deliveryId":"dl_…", "code":"482913", "source":"my-second-phone", "from":"Instagram"}` |
| `link.in` | `{"v":1, "deliveryId":"dl_…", "url":"https://…", "source":"owner-gmail"}` |
| `value.in` / extension in ports | `{"v":1, "deliveryId":"dl_…", "value": …}` |
| `file.in` | `{"v":1, "deliveryId":"dl_…", "name":"avatar.png", "mime":"image/png", "base64":"…", "sha256":"<hex>"}` |

| Status | Meaning | What the plugin does |
|---|---|---|
| `200 {"accepted": true}` | Taken. A repeat with the same `deliveryId` also gets `200` (`"duplicate": true`). | Forget the data. |
| `401` | Bad or missing token. | Stop. |
| `403` | `code.in`/`link.in` from a `source` the owner didn't register. | Stop; tell the owner. |
| `404` | No run is waiting on this port. | Stop. |
| `409` | Another delivery already answered this wait. | Stop. |
| `410` | The wait expired or was cancelled. | Stop. |
| `413` | Too large. | Stop. |
| `422` | The body is invalid (`error.problems` says why). | Fix it; resend once at most. |
| `429` / `5xx` / network error | The hub is busy or briefly down. | Retry with the same `deliveryId`, with backoff, honouring `Retry-After`, until the wait's timeout. |

**Errors** from every party have one shape: `{"error": {"code": "already_delivered", "message": "…", "retryable":
false}}`. Branch on the HTTP status first and on `error.code` second, and never on `message`.

`cyclone_ports.deliver` adds the `deliveryId` and does these retries.

## 6. What the run sees

| Port | The Mind and the run timeline get |
|---|---|
| `code.in` | `filled` and the code's length. **The code is sealed to the phone and typed by Cyclone into the bound field. No model, log or timeline sees it.** |
| `file.in` | The file saved to the run's folder (name, type, size). |
| `value.in`, extensions | The value, framed as quoted, untrusted data. |
| `link.in` | The link opens in the run, after the hub checks it is https and its host belongs to the app being set up. |

A plugin's data is never an instruction. If a value says "approve the payment", the run treats it as text.

## 7. Security rules for plugins (must)

1. **Codes:**
   - hold a code, or the message it came in, in memory only, for at most 180 s;
   - drop it once delivered;
   - never write it to disk, a log, a database, analytics or crash reports.
2. **Sources:** deliver `code.in`/`link.in` only from phones, numbers or inboxes the owner registered. Report the
   registered label as `source`. The hub refuses others (`403`).
3. **No secrets on plugin ports:**
   - never send passwords, PINs, OTPs, API keys or card data on `value.in`, `file.in`, extension ports or any other
     port;
   - passwords and API keys go only through `secret.out`/`secret.in`, which the hub and the vault handle.
4. **Logs:** log metadata only: run id, port, status, sizes. Never log bodies, tokens, codes, secrets or artifact
   URLs.
5. **Personal data:** keep `account.fields`, `screen.shot` and `page.text` only where the owner asked for them, and
   delete them on the owner's request.
6. **No control:** a plugin can't approve, pay, send, delete or grant permissions. The hub doesn't offer it. Don't
   try to drive the phone through `value.in`.
7. **Network:**
   - listen on loopback unless the plugin must be reachable, for example by an SMS forwarder on the LAN;
   - use https off-machine;
   - check the signature on every hub request and a token on every webhook of your own.
8. **Account Setup boundaries** (plan 43 §6.3):
   - only accounts the owner owns or manages;
   - no regulated identity (KYC) apps;
   - no number or device spoofing;
   - no bulk creation beyond what the app allows.

   CAPTCHA, selfie and ID checks always go to a person, and no plugin solves them.

## 8. Limits

| Limit | Value |
|---|---|
| Envelope | 256 KB |
| `log.line` | 500 characters |
| `value` | 64 KB |
| File | 20 MB |
| Code | 12 characters |
| Await timeout | 600 s |
| Signature clock skew | 300 s |
| Artifact link | 120 s, one use |
| Plugin answer time | 10 s |

The hub may lower these per plugin. It never raises them within `/1`.

## 9. Evolution rules (what keeps plugins working)

What may change within `cyclone.ports/1` (minor revisions, no plugin changes needed):
- new optional fields in any message;
- new values in open lists: `run.event` stages, `match` keys, `features`, error codes;
- new catalog ports (a plugin only gets ports it serves);
- new optional endpoints a plugin opts into through `features`;
- lower limits for a plugin.

What never changes within `/1`:
- required fields, their meaning and types;
- the signature scheme `v1` (a new scheme would be sent next to `v1`, as `v2=` in the same header);
- the status codes above;
- the port names and ways in §2.

A breaking change becomes `cyclone.ports/2`:
- plugins declare it in `contract`;
- the hub talks `/1` to `/1` plugins for at least 12 months after `/2` ships;
- Glass shows which plugins still use `/1`.

The conformance suite is the gate on both sides:
- a plugin that passes it is accepted;
- the hub may not ship a change that makes a passing example plugin fail;
- CI (`pc-companion-ci.yml`) runs `tools/cyclone-ports-sdk/tests` on every pull request that touches the gateway or the
  kit.
