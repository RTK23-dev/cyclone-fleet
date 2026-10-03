# Handoff: build plugins for Cyclone Run Ports

For: agents and developers who build tools that plug into Cyclone runs and Cyclone Glass. Date: 2026-10-01.

**To hand one connector to one agent, send `HANDOFF-build-a-connector.md`.** It stands on its own. This file is the
overview for several agents. The design review is plan 47.

**Start here.** The design is plan 45 (Run Ports) and plan 46 (Skill Studio). The contract you build against is
`tools/cyclone-ports-sdk/SPEC.md` (`cyclone.ports/1`). The gateway's Port Hub isn't built yet, so you test against
the **Dev Hub** in the same kit. It speaks the same contract, so a plugin that passes there needs no changes later.

## 1. The system in one picture

```
Phone run ──emit──▶ Port Hub (gateway) ──signed POST /ports/{port}──────▶ your plugin
Phone run ◀─await── Port Hub (gateway) ◀─POST {deliverUrl} + port token── your plugin
                         │
                         └─ audit (metadata only), artifacts (one-time links), sealing secrets to the phone
```

- **Out ports**, run to plugin: `run.event`, `log.line`, `screen.shot`, `account.fields`, `page.text`, `file.out`.
- **In ports**, plugin to a waiting run: `file.in`, `value.in`, `code.in`, `link.in`.
- `secret.out` and `secret.in` are handled by the hub and the vault only in v1. Don't build plugins for them yet.

Glass is where the owner adds a plugin from its manifest URL, ticks personal ports, binds plugins to ports on a task,
routine or Sign-up Map, and watches port messages in the run timeline. Skill Studio (plan 46) will show resolver nodes
backed by these plugins.

## 2. What's ready to use

| Path | Use it for |
|---|---|
| `tools/cyclone-ports-sdk/SPEC.md` | the normative contract: read §2–§7 before writing code |
| `tools/cyclone-ports-sdk/cyclone_ports/` | Python SDK (`PluginServer`, `deliver`, `verify`, validators), stdlib only |
| `python -m cyclone_ports.devhub` | play a run against your plugin (`scenarios/*.json`) |
| `python -m cyclone_ports.conformance` | the checks the real hub will run when the owner adds your plugin |
| `tools/cyclone-ports-sdk/examples/` | `logger`, `pc_images`, `sms_plugin`: copy the closest one |
| `tools/cyclone-ports-sdk/schemas/` | JSON Schemas for builders in other languages |

Quick start: `tools/cyclone-ports-sdk/README.md`. Tests: `python -m pytest tools/cyclone-ports-sdk/tests -q`.

## 3. Plugins to build (pick one, keep to its folder)

Put each plugin in `tools/cyclone-plugins/<name>/` with:
- `cyclone-plugin.json`;
- `plugin.py` (or another language);
- `README.md`;
- `tests/`;
- a `scenario.json` the Dev Hub can play.

One agent per plugin keeps the paths apart.

| # | Plugin | Ports | Notes |
|---|---|---|---|
| 1 | **Mail codes and links** (IMAP first, then the Gmail and Outlook APIs) | `code.in`, `link.in` | Watch the owner's inbox from the moment of the await. Match on sender and subject. Pull the code or the https link. The source is the inbox label the owner registered. App passwords and OAuth tokens go in the OS keychain, never in files. Ranked first in plan 45 §5c. |
| 2 | **SMS codes, production** | `code.in` | Harden `examples/sms_plugin`: <ul><li>forwarder templates for common Android SMS forwarder apps;</li><li>several registered sources;</li><li>per-app code patterns;</li><li>LAN binding with the token;</li><li>rate limits;</li><li>a "test text" button.</li></ul> |
| 3 | **Notifications** (Telegram, Discord, Slack, ntfy) | `run.event`, `log.line` | Pings on `needs_you`, `created`, `failed` with the run id. No personal ports, so no screenshots or fields unless the owner ticks them. |
| 4 | **Sheets** (Google Sheets, Airtable, Notion) | `account.fields`, `run.event` (out); `value.in` (in) | Append rows with the handle and status; answer `value.in` asks such as "next username" or "a caption". Never write passwords. |
| 5 | **Files** (a PC folder, OneDrive, Drive) | `file.in`, `file.out` | Generalize `examples/pc_images`: by kind, by name and per-run subfolders. Downloads land in the owner's folder. |
| 6 | **Run logger and auditor** | all out ports | Grow `examples/logger` into a small local viewer: a timeline per run, screenshots, retention and delete. |
| 7 | **Generators** (local model, Higgsfield) | `value.in`, `file.in` | Bio, captions, a profile picture. The run shows the result to the owner before using it. |

Not now:
- password managers (`secret.*` comes in P4 with pinned keys);
- MCP `cyclone_port_wait` and recipes (P5);
- anything that solves CAPTCHA, selfie or ID checks (never).

## 4. Rules (SPEC.md §7, enforced by review and by the hub)

1. **Codes:**
   - keep them in memory only, at most 180 s;
   - drop them after delivery;
   - never put a code, a message body, a port token or an artifact URL on disk or in logs.

   Copy the test `test_signup_scenario_with_sms_code_never_keeps_the_code`: it scans every file and stdout/stderr for
   the code.
2. **Sources:** codes and links come only from phones, numbers and inboxes the owner registered and confirmed. Report
   the registered label as `source`.
3. **No secrets** (passwords, PINs, OTPs, API keys, card data) on any plugin port. Plugin credentials (IMAP app
   password, bot token) live in the OS keychain or an env var, never in the repo or the manifest.
4. **Signatures:**
   - check `X-Cyclone-Signature` on every hub request; `PluginServer` does it;
   - answer `404` for ports you don't serve;
   - listen on loopback unless you must accept a webhook, and protect any webhook with its own token.
5. **No control:** plugins don't approve, pay, send, delete or grant permissions, and don't try to steer a run through
   `value.in`. A run treats plugin data as quoted, untrusted text.
6. **Account Setup boundaries** (plan 43 §6.3):
   - only accounts the owner owns or manages;
   - no KYC apps;
   - no spoofing;
   - no bulk creation beyond what the app allows.
   CAPTCHA, selfie and ID checks go to a person.
7. **Generic shell or root is out.** Don't expose either through a plugin, and don't make a plugin that runs commands a
   run asks for.

## 5. Definition of done for a plugin

- `cyclone-ports-check <endpoint>` passes all required checks.
- A `scenario.json` plays to the end with the Dev Hub (`exit 0`). For in ports, also cover the timeout path
  (`needs_you`).
- Tests cover:
  - matching;
  - the never-store rule for anything secret;
  - refusing an unsigned request;
  - the source rule for `code.in`/`link.in`.
- The README covers setup, the env vars, what data it keeps and for how long, and how to delete it.
- Nothing outside `tools/cyclone-plugins/<name>/` changes. Contract gaps go to §6 instead of being patched into the
  SDK.
- Physical checks (a real forwarder phone, a real inbox) are stated honestly: done, or UNVERIFIED.

## 6. Open questions (write down what you find)

1. Should `code.in` allow several codes per run (two-step sign-ups)? Today it's one await per code, which works.
2. Should the hub's link check for `link.in` (host belongs to the app) use a per-app allowlist from the Sign-up Map?
3. Should remote (https) plugins also sign their deliveries? Today the single-use port token over TLS is the proof.
4. Should there be batched `account.fields` for plugins that want one row per run instead of per page?

## 7. What the Cyclone side builds next (for context, not for plugin agents)

Plan 45 P1–P5:
- **P1:** Port Hub, out ports and the Glass Plugins page.
- **P2:** `file.in`/`value.in` and the Mind's `port_send`/`port_wait`.
- **P3:** `code.in` with sealed delivery and Sign-up Map verification points.
- **P4:** share secret.
- **P5:** recipes, MCP and remote plugins.

Plan 46 S1–S4 is Skill Studio. The SDK's `catalog.py` is the one port table the gateway will import, so keep it the
source of truth.
