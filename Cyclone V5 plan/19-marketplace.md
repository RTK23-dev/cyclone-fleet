# 19 — Cyclone Marketplace

One place, on the phone and on the PC, to see and manage **everything Cyclone can use**: the AI it thinks with, the
agents and MCP servers it talks to, and the skills and routines it can run, whether made by Cyclone, by the owner or,
later, by other people. The reference is the Grok desktop Marketplace: featured makers, "for you" suggestions
explained by a reason, a team shelf, and an "N installed" entry to manage what you have.

Status: **Phase 1 is built in alpha.35.** Everything after that is the plan.

---

## 1. Why this matters, and what goes wrong in marketplaces

A marketplace turns Cyclone from one agent into a platform: every new routine makes the phone more useful without a
new app release, and every connection widens what the Mind can reach. It is also the riskiest surface in the product.
A phone agent that installs other people's automation is exactly where things go wrong:

| Failure seen in other stores | What Cyclone does instead |
|---|---|
| Installing grants silent, broad power | Every listing declares its apps, what it may do, the approvals it will stop for, and its inputs. The install sheet shows all of it; nothing runs at install time. |
| Packages carry secrets or collect them | No listing can contain or ask for a secret value. Secrets still only go through the Secrets Card at run time. Inputs are plain values the owner types; sensitive-looking inputs are refused. |
| A "routine" quietly skips the approval that a person would have had | Packages never carry permission to skip GATE or approvals. Consequential actions stop for the owner exactly as if the owner had typed the goal. |
| Remote code | Phase 1 packages contain no code: a recipe is a goal sentence with typed inputs, run by the same Mind with the same boundaries. Deterministic routines (Phase 3) are validated capsules: typed steps, declared capabilities, no secret literals. |
| Rotting listings | Every first-party listing gets a Cyclone Lab mission; the store shows how often it actually works on real phones (Phase 2). |
| The PC and the phone disagree | The **phone is the authority** for what is installed and runs there. Glass reads and changes it through typed gateway ops, like every other phone state. |

---

## 2. The model

### Listing kinds

| Kind | What it is | Runs where | Phase |
|---|---|---|---|
| **Recipe** (`recipe`) | A goal template with typed inputs, e.g. "Set a timer for {minutes} minutes and turn on Do Not Disturb". Running it starts a Mind mission with the filled-in goal. | Phone (Mind) | 1 |
| **Connection** (`connection`) | Something Cyclone connects to: an AI provider (OpenRouter), a PC agent over MCP (Codex, Grok, Cursor, OpenCode, Copilot, any MCP client), the cloud MCP tunnel. The store shows its live status and the one action that fixes it. | Phone and PC | 1 (manage); 3 (phone-side MCP servers) |
| **Routine** (`routine`) | A deterministic, validated routine capsule: typed steps, selectors, verification, recovery. Fast and cheap with no model call per step, and the Mind takes over when a step fails. | Phone (WorkflowRuntime) | 3 |
| **Skill** (`skill`) | A reusable building block that routines and the Mind can call: compiled skills, stock skills. | Phone | 3 |
| **Bot** (`bot`) | A persona bundle: system-prompt addition, preferred model and effort, a shelf of recipes. "Lauren's inbox bot." | Phone | 4 |

### A listing (manifest)

```json
{
  "id": "cyclone.focus-timer", "kind": "recipe", "version": "1.0.0",
  "name": "Focus timer", "publisher": {"id": "cyclone", "name": "Cyclone", "verified": true},
  "summary": "A timer plus Do Not Disturb, in one go.", "category": "Productivity", "glyph": "⏱",
  "goal": "Set a timer for {minutes} minutes and turn on Do Not Disturb.",
  "inputs": [{"name": "minutes", "label": "Minutes", "kind": "number", "default": "25"}],
  "apps": ["com.google.android.deskclock"],
  "does": ["Changes a phone setting", "Uses the Clock app"],
  "asksFirst": [],
  "suggestFor": ["com.google.android.deskclock"],
  "labMission": null
}
```

- `does` is the capability summary shown before install ("Reads your notifications", "Sends a message after you approve").
- `asksFirst` lists the consequential actions the recipe will stop for (send, delete, pay, post). It is shown on the
  install sheet and **cannot be used to skip** the approval. The Mind's GATE still decides at run time.
- `suggestFor` powers the "Because you use WhatsApp" reason: a listing is suggested when one of its packages is
  installed on the phone.

### Installed state (the phone)

`<files>/Cyclone Brain/Marketplace/installed.json`: listing id, version, the owner's saved input values (never
secret-shaped), enabled, installed-at, source (catalog / PC / file), run count, last run time and outcome. Removing a
listing deletes its entry; nothing else on the phone is touched.

### Running

A recipe run = the filled-in goal sent through the **same entry as a typed Ask** (`OverlayChromeRuntime.submitRequest`),
so it gets the same Mind mission, overlay, Owner Moments, approvals, Secrets Card, run trace and Lab metrics. The run
is recorded against the listing, and its outcome comes from the mission's own ending.

---

## 3. Surfaces

**Phone — Routines → Marketplace**. Built to Grok's layout:
- Header "Marketplace", a search field, and an **"N installed ›"** chip that opens *Installed*.
- **Featured**: four large cards (glyph, maker, name).
- **For you**: listings suggested because an app they use is installed ("Because you use WhatsApp"), with **Add**.
- **From Cyclone**: the first-party shelf, with **Add**.
- **Connections**: the AI provider, with model and key status (never the key); the PC link. Each has its one action (*Set up*, *Open settings*).
- **Listing sheet**: what it does, the apps it uses, what it will ask before doing, the inputs; **Add** / **Run** /
  **Remove**. Adding saves the inputs; nothing runs until you press Run.

**PC — Glass → Marketplace** (`#/market`): the same shelves, read from the phone, plus **PC connections**: each MCP
agent (Codex, Grok, Cursor, OpenCode, Copilot, Generic MCP) with detected, configured and connected state, and a
*Connect* / *Repair* action. That action runs the same agent-MCP connector Cyclone One uses, with fixed arguments.
Installing or running from Glass changes the phone through typed gateway ops; the phone refuses what it does not
accept.

**Agents — MCP**: Phase 2 adds `phone_market_list` / `phone_market_run`, so a PC agent can find and run recipes.

---

## 4. Architecture

```
 Glass #/market ──HTTP──▶ gateway /v1/devices/{id}/market/* ──typed op──▶ phone market.* ──▶ Marketplace (installed.json)
                         gateway /v1/pc/connections  ──fixed argv──▶ CycloneAgentMCP status / connect <host> --verify
 Phone Marketplace page ─────────────────────────────────────────────▶ same Marketplace object
 Run a recipe ─▶ OverlayChromeRuntime.submitRequest(goal) ─▶ Cyclone Mind mission (GATE, Secrets Card, Owner Moments, trace)
```

- Phone ops: `market.catalog` (listings + installed + phone connections), `market.install`, `market.remove`,
  `market.run`. Every response is validated by the gateway contract like the other V5 ops.
- The first-party catalog ships inside the phone app, so the store works without a PC. The PC adds PC-side connections.
- Guards: listings validated at load (ids, inputs, templates, no secret-shaped text, every `{placeholder}` declared);
  installs refuse secret-shaped input values; a run is refused while another task runs or the owner has control.

---

## 5. The road from here (how a serious studio would take it)

### Phase 1 — alpha.35: the store exists and is safe *(built)*
First-party recipes, install / run / remove on the phone and from Glass, connections status and repair on both, "for
you" from installed apps, and the install sheet with its disclosures. Validation, contract and guard tests.

### Phase 2 — quality you can see (alpha.36–37)
- **Lab-verified badges**: every first-party listing gets a Lab mission. The listing shows "Works 9 of 10 times on
  Pixel 8 (alpha.37)" from real runs, and falls back to "Not yet measured". A release gate: no featured listing
  below 80%.
- **Run history per listing**, with a failure reason and a link to the run inspector.
- **Agent MCP tools** `phone_market_list`, `phone_market_run`.
- **Triggers for recipes**: schedule ("every weekday 07:30") and notification ("when a PostNL notification arrives"),
  off by default and enabled per install, with a pause-all switch.
- **Standing approvals** scoped per listing: "Focus timer may change Do Not Disturb without asking, for 30 days".
  Revocable, shown in the listing, never for pay, delete or send to a new person.

### Phase 3 — the owner as maker (alpha.38–40)
- **Save as recipe** from any successful mission. Cyclone proposes a template, and the owner names the inputs.
- **Routines and skills as listings**: deterministic capsules exported from Follow Me and teaching, validated by
  `RoutineCapsuleCodec`, with the Mind as fallback when a step fails.
- **Share and import**: a signed `.cyclone` package file (manifest + capsule + content hash), with import through the
  same install sheet. An "Unverified publisher" label and a stricter sheet for anything not from Cyclone.
- **Phone-side MCP connections**: the Mind can use tools from a remote MCP server the owner adds, such as a calendar
  or a notes app. Each server gets a per-tool allowlist, calls from untrusted content are blocked, it gets a spending
  limit and a kill switch, and its tokens stay in the vault, never in prompts.
- **More API connections**: second provider keys, direct provider accounts, and key health, all key-in-vault.

### Phase 4 — other people (a community store)
- **Registry service**: publisher accounts (verified identity for "Verified"), a signing key per publisher, package
  signing, version history and yanking.
- **Review pipeline**: static checks (the Phase 1 validators, the capsule codec), an automated Lab run on a device
  farm (emulators plus a small real-device pool), and human review for anything that uses messaging, payment or
  accounts. The listing shows the results.
- **Trust signals**: installs, Lab success rate, ratings with written reviews, "used by people like you".
- **Bots**: persona bundles, following Grok's "maker's bot" pattern.
- **Economics**: free first. Paid listings or tips only after the trust and refund machinery exists.
- **Policy**: no packages that bypass verification, CAPTCHAs or approvals; no data collection off the phone
  without an explicit, reviewed connection; a takedown and remote-disable path for a listing found harmful.

### Metrics the studio would steer by
Weekly active installers; recipes run per active phone; **run success rate per listing** (from Lab and from real
runs); approval declines per listing, which is a smell; uninstall within 24 h; time from "Add" to first successful
run; share of runs that needed the owner's hands.

### What is deliberately *not* in Phase 1
Third-party code, remote catalogs, paid listings, background triggers for installed recipes, and phone-side MCP servers.
Each needs the trust machinery above first.
