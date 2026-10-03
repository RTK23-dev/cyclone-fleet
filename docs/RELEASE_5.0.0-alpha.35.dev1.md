# Cyclone V5 Alpha 35 — Cyclone Marketplace

Developer alpha for owner testing, built on Alpha 34 (Lab handoff and Glass mirror repairs), which it includes.
Mobile is `5.0.0-alpha.35.dev1` (version code 177), Windows companion is `1.6.0-alpha.35`, and the bundled Glass web
app is `1.0.0-alpha.18`.

## What changed

**Marketplace**: one place for everything Cyclone can use, on the phone (Routines → Marketplace) and on the PC (Glass
→ Marketplace), laid out like a store: an "N installed" entry, search, Featured, "For you" (explained by an app on
your phone, such as "Because you use WhatsApp"), From Cyclone, and Connections.

- **Recipes**: 14 ready-made things to ask Cyclone. Examples: Notification digest, Focus timer, Inbox brief,
  WhatsApp catch-up, Send a WhatsApp, Today at a glance, Directions, Play music, Find a video, Quick answer,
  Wind down, Battery check, Free up space and Quiet until…. Before you add one, the sheet tells you what it does,
  which apps it uses and what it asks you first. Adding saves your inputs (minutes, place, person…); nothing runs
  until you press **Run**.
- **Safe by construction**: a recipe runs as a normal Cyclone mission, through the same entry as typing it
  yourself, so Cyclone still stops before sending, deleting or paying, and passwords still only go through the
  Secrets Card. A recipe does not start while another task runs or while you have control of the phone, and it
  never joins a running mission. Recipes carry no code and cannot contain secrets.
- **Connections**: the AI Cyclone thinks with (OpenRouter: connected or set up, which model; never the key), the PC
  link, and in Glass every AI agent on this PC that can reach Cyclone over MCP: Codex, Grok, Cursor, OpenCode,
  Copilot and any MCP client. Glass shows which are found, configured and connected, with Connect / Repair / Get
  config, using the same connector as Cyclone One.
- **From the PC**: Glass can add, save, run and remove recipes on the phone. The phone stays the one place that
  decides what is added and what runs.

The full plan (quality badges from Cyclone Lab, triggers, standing approvals, making and sharing your own recipes,
routines and skills as listings, phone-side MCP servers, and a community store) is in
`Cyclone V5 plan/19-marketplace.md`.

## Validation and limits

Android unit tests, lint, gateway, agent MCP, Glass and companion tests, and the CI guards pass, including a new
Marketplace guard: recipes run only through the Ask entry, refuse when the phone is busy, never approve and carry no
code, and the PC connector runs only fixed commands. The Glass Marketplace was checked rendered in Chromium against
the real gateway routes with fixture data. Physical Pixel 8 acceptance is UNVERIFIED. Alpha 34's physical items (the
Lab canary, landscape mirror, reconnect soak) also remain open.

Install the APK as an update; do not uninstall to work around a signing mismatch. The developer-alpha publisher
verifies the historical development certificate against the previous release; that signer is update-compatible but
exposed in repository history and unsuitable for production. The Windows installer is not Authenticode-signed.

Suggested checks: Routines → Marketplace on the phone; add Focus timer with 1 minute and Run; add Send a WhatsApp and
confirm Cyclone stops for approval before sending; open Glass → Marketplace and run Notification digest from the PC;
in Glass Connections, confirm your installed PC agents show as found or connected.
