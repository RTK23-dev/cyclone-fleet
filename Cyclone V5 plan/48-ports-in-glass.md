# Plan 48: Cyclone Ports in Glass, the Port Hub and its dashboard

Date: 2026-10-01. **Run 1: built, released in alpha.96.** **Run 2: built, released in alpha.97.** **Run 3: built, released in alpha.98**
(live traffic, waits that survive restarts, the Activity view and test runs). **Run 4: built, released in alpha.99**
(the phone side: the PC polls the phone's outbox, codes sealed to the trusted phone key, `port_send` / `port_wait`). Builds on plans 45 (Run Ports), 46 (Skill Studio) and 47 (review), and on the kit in
`tools/cyclone-ports-sdk`. Goal: the real Port Hub in the gateway, and a Ports dashboard in Glass that an admin feels
at home in, built in runs. Each run ships something whole and tested, with nothing half-done.

## 1. Where it lives

- **Gateway:** `cyclone_device_gateway/ports/`, the Port Hub.
  - It imports the kit's `cyclone_ports` (catalog, signing, validators, checker), so there is one contract and no copy.
  - Routes are `/v1/ports/*` (the Glass bearer) plus `/v1/ports/{runId}/{port}/deliver` (port tokens, run 3).
  - State: `runtime/ports/ports.db` (SQLite), and plugin keys kept apart, sealed with DPAPI on Windows (0600 file
    elsewhere).
- **Glass:** a **Ports** entry in the Command Center sidebar (`#/command/ports`), next to Connections and Vault, because
  that's where the admin already runs tasks, routines, accounts and secrets.
  - Plugin details open in a side sheet over the page.
  - Adding a plugin is one guided sheet.
  - Later runs add a Port map, Activity, Sources, and a timeline lane in Runs.

## 2. Design principles (the "Apple/Google bar")

1. **One clear next step.** Every plugin shows one status and one action that fixes it:
   - "Waiting for its key" → Copy key;
   - "Changed what it asks for" → Review;
   - "Can't reach it" → Run checks.
2. **Consent you can see.**
   - Every personal port is a visible switch.
   - Remote plugins start with them off.
   - Secret ports carry a lock and say who handles them (the hub, the vault).
3. **Secrets shown once.** A plugin key is shown once, with copy buttons for PowerShell and bash, and never again.
   Losing it means issuing a new one.
4. **Honest status:**
   - checks run against the real plugin, and a failed check comes with a human hint;
   - "last seen" is a real health check;
   - nothing is green unless it passed.
5. **Calm, dense, beautiful.**
   - It uses Glass's tokens and components only, with no page-level colours.
   - Each plugin gets a generated monogram tile.
   - Out and in ports read at a glance (↗ and ↙).
   - The light and dark themes are equal.
   - Motion is limited to the checks revealing one by one and the status settling.
6. **Keyboard and screen readers:**
   - sheets trap focus and close on Esc;
   - switches are real buttons with `aria-pressed`;
   - status is never shown by colour alone.

## 3. Runs

| Run | Ships | Done when |
|---|---|---|
| **1. Foundation: plugins** | Port Hub core in the gateway (plugin registry, pinned manifests, keys, checks with the kit's conformance suite, a signed test send, health monitor, metadata-only activity log, `/v1/ports/*` routes) · Glass Ports page: overview, plugin cards, **Add plugin** sheet (address → review and consent → key once → live checks → live), plugin sheet (ports and consent, health, checks, test, pause, new key, remove), port catalog, recent activity | gateway tests and Glass tests green; the three example plugins added end to end from Glass in a real browser |
| **2. Port map and bindings** | Which plugin serves each port, by default and per task, routine or Sign-up Map; conflicts (two plugins, one in port) resolved visibly; the **Port map**, a switchboard view of the catalog with live counts; extension ports listed under their plugin | a task's resolved bindings are visible and testable from Glass |
| **3. Live traffic** | The real out-port pipeline (envelopes, one-time artifacts, retries, failed-delivery list) and in ports (awaits persisted across restarts, deliver endpoint with port tokens, idempotent `deliveryId`); **Activity** view with filters and a per-run lane on the Run page | the Dev Hub scenarios pass against the gateway hub instead of the Dev Hub |
| **4. Phone side** | Gateway protocol ops `port.emit/await/cancel/status`; Mind tools `port_send` / `port_wait`; Account Setup verification points bound to `code.in`; codes sealed to the phone (plan 33) | an Android test run sends events to a plugin and waits on `value.in`; physical check stated honestly |
| **5. Sources, keys and resilience** | Registered SMS and inbox sources (add, confirm with a test message, remove); zero-downtime key rotation (`…_NEXT`); per-plugin rate limits and auto-pause after repeated failures; manifest-drift review with a diff | drift, rotation and failure flows covered by tests and screenshots |
| **6. Polish and ship** | Accessibility pass, empty and error states everywhere, dark-mode review, docs (README, Glass guide), release notes, physical acceptance checklist | released as an alpha; acceptance stated honestly |

Each run ends with: tests, a screenshot review in light and dark, a commit, and when it changes what the owner can use,
a release.

## 4. Run 1 detail

**Gateway `ports/`:**
- `store.py`: the SQLite tables `plugin` (pinned manifest and its hash, status, consent, kid, last check, health) and
  `activity` (metadata only), plus `KeyStore` (DPAPI or 0600).
- `hub.py`: `PortHub`:
  - `preview(endpoint)`, `add(endpoint, consent)` (issues `k1`, shown once), `check(name)` (the kit's conformance);
  - `set_consent`, `pause`, `new_key`, `remove`, `send_test` (a signed `run.event` or `log.line`);
  - `monitor_once()` (health plus manifest drift → `needs_review`), `approve_changes`, `overview()`.
- `api.py`: `/v1/ports/overview`, `/plugins/preview`, `/plugins`, `/plugins/{name}/check|consent|pause|test|key|approve|delete`.

Plugin states: `waiting_key` → `active` ⇄ `paused`; `needs_review` (manifest drift); `unreachable` (health failing).

Endpoint rules:
- loopback http, or https;
- LAN http is allowed with a warning chip, but personal ports are off by default;
- never `file:` or other schemes.

**Glass:**
- `services/ports.ts`: the client, types and the status, hint and copy helpers. These are pure and unit-tested.
- `pages/portsPage.ts` (the page), `pages/portsAdd.ts` (the Add sheet), `pages/portsPlugin.ts` (the plugin sheet).
- `styles/ports.css`, tokens only.
- Router `command/ports`, a sidebar entry, and tests.

## 5. Run 2 detail (built)

**Rules** (`ports/bindings.py`):
- **Out ports:**
  - automatic means every live plugin allowed the port (fan-out);
  - a choice names any number of plugins, and none means off.
- **In ports:**
  - one answer: with one live candidate it is automatic;
  - two or more is a conflict until the owner picks, and nothing answers meanwhile;
  - a choice names one plugin or none.
- **Scopes:** `routine:<id>` → `app:<package>` → `default`, and the first scope with a choice wins.
- **Unavailable:** a chosen plugin that isn't live leaves the port unavailable. It is never quietly re-routed.
- **Removing a plugin** takes it out of every choice; a choice it alone made goes back to automatic.

**Routes:**
- `GET /v1/ports/bindings?scope=` (the map for one scope, with what it inherits);
- `POST /v1/ports/bindings {scope, port, plugins|null}`;
- `GET /v1/ports/resolve?routine=&app=` (what a run reaches; run 3 sends along it).

**Glass** (`#/command/ports/map`): a switchboard of ports (left) and plugins (right).
- **Lines:**
  - solid: the routes runs use;
  - dashed: could serve but doesn't here;
  - amber: needs a choice.
- **Scope pills:** Everywhere, each routine or app with choices, and "For a routine or app".
- **Each port** opens a chooser:
  - Automatic / Same as Everywhere;
  - its plugins (checkboxes for out, radios for in);
  - Off.
- **The Plugins view** lists conflicts under "Needs you" with a link to the map.

## 6. Run 3 detail (built)

**Gateway** (`ports/traffic.py`):
- **Out ports:**
  - a run's message follows the Port map for its routine and app;
  - one envelope with a unique `id`, sent to each routed plugin;
  - screenshots and files go as one-time artifact links (`GET /v1/ports/artifacts/{id}?t=`, 120 s);
  - 3 attempts with backoff; a failure is logged and never blocks the run.
- **In ports:**
  - a wait goes to the one routed plugin. Empty, conflict, off and unavailable come back as reasons and never pick a
    plugin;
  - the request is saved, and its port token is sealed like the keys. A restart re-sends the same `awaitId` and token;
    expired waits time out and the plugin gets `/cancel`;
  - plugins answer at `POST /v1/ports/{runId}/{port}/deliver` with the run's port token, using the contract's status
    codes. A repeat with the same `deliveryId` gets 200 again; another delivery to an answered wait gets 409;
  - a delivered code, value or link stays in memory (5 minutes) until the run takes it. A code is taken once
    (`take_code`, for run 4's sealed delivery) and never written anywhere. A file is saved in the run's folder.
- **Run-side routes** (bearer): `/v1/ports/runs/{runId}/emit`, `/await`, `/v1/ports/waits/{id}` (long-poll),
  `/cancel`. Run 4 connects the phone to them.
- **Activity:** `/v1/ports/activity` (filters: plugin, port, outcome), `/v1/ports/runs`, `/v1/ports/runs/{id}`.
- **Test runs:** `/v1/ports/test-runs` plays a fixed scenario through the real hub and plugins:
  - run events;
  - sign-up with a code;
  - an image from the PC;
  - a value.

  Test details only. A code that arrives in a test run is dropped at once.

**Glass** (`#/command/ports/activity`):
- filters;
- test-run cards with a live step track;
- runs that open into their lane;
- the full log;
- a Test run sheet with scenario cards, an optional app's Port map choices, and live steps, with hints while
  waiting.

The per-run lane on the Run page waits for run 4, when phone runs use ports.

## 7. Run 4 detail (built)

**The phone never calls the PC.** The runtime's `PhoneBridge` (`ports/phone.py`) polls every ready, paired phone:
every 1.5 s while it has traffic or open waits, every 5 s when idle.

**Phone ops** (`desktop_runtime/v5_contract.py`, `gateway/GatewayV5PortsAdapter.kt`):
- `ports.poll {ack?, max?, drop?}` → `{items}`: emit, await and cancel items, kept until acknowledged;
- `ports.blob {id, offset}`: a screenshot in 384 KB chunks;
- `ports.answer {id, state, reason?, plugin?, value?, url?, file?, sealed?}`: a plugin's answer for a wait;
- `ports.file`: `cc.media` chunks with a `pt_…` task id, for an image, video or audio file.

A phone counts as connected while the PC polled in the last 20 s; without one a run is told `no_pc` at once.

**The bridge:**
- emits go to `traffic.emit` (screenshots fetched through `ports.blob`);
- waits go to `traffic.wait`; a worker follows `traffic.result` until the wait ends, then answers the phone;
- a stopped wait on the phone becomes `traffic.cancel`;
- messages are deduplicated per phone and acknowledged on the next poll;
- a message the PC's secret check refuses is isolated (`max=1`) and dropped (`drop`), and the rest keep flowing.

**Codes:**
- the hub hands the code over once (`take_code`);
- the bridge seals it with HPKE (P-256, HKDF-SHA256, AES-256-GCM, info `cyclone-port-code/v1`) to the phone key the
  owner trusted in Command Center → Phones. A phone whose key isn't trusted gets no code, only a reason;
- the seal is bound to the run, the app or site (`package:…` / `chrome:https://…`), the device key and a 5-minute
  expiry;
- the phone opens it (`SealedDelivery.openCode`) and holds it as the `code` slot. `vault_fill what=one_time_code` fills
  it. The model learns only its length.

**Values, links and files:**
- a value comes back as quoted data; a secret-looking value fails closed;
- a link (https only) is opened on the phone, and the model sees only its site;
- a file is pushed to the phone's gallery folder, and the model gets its name and folder.

**Mind tools** (`PhoneMindToolbox`), offered only while a PC is connected and never in a Lab mission:
- `port_send` on `run.event`, `log.line`, `screen.shot`, `page.text`, `account.fields`. A screen with a secret field,
  or an app kept private (banking, payments), is never sent. Secret-looking fields and text are taken out on the phone
  and again on the PC;
- `port_wait` on `code.in`, `value.in`, `link.in`, `file.in`. Its brief (the run record's line) never carries a value.

**Tests:** `test_ports_phone.py` (the bridge end to end with the kit's example plugins and a real HPKE open),
`PortOutboxTest`, `SealedDeliveryTest`, `PortToolsTest`, and the guard `test_ports_run4_guard.py`.

**Not yet (run 5):** Account Setup's verification points don't bind to `code.in` by themselves. Today the Mind waits
on `code.in` when it reaches a code page. The per-run lane on the Run page is also not built yet.


