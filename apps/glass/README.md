# Cyclone Glass (`apps/glass`)

Cyclone Glass is the **developer's eyes on the engine that runs in Cyclone Mobile**: a local website on this PC that shows what
Cyclone knows (apps, maps, versions), what it did (runs), and lets you control the phone. It has **no intelligence of its own**:
no agent loop, no model calls, no API keys. Charter: [`Cyclone V5 plan/03-glass-v1.md`](../../Cyclone%20V5%20plan/03-glass-v1.md).

## Run it

**Windows with Cyclone One installed:** Cyclone One â†’ Settings â†’ **Open Cyclone Glass**. One's own runtime serves Glass and
opens your browser with a one-time link; nothing else to install.

**From a checkout (any OS):**

```bash
python -m pip install -e 'apps/device-gateway[test]'
cd apps/glass && npm ci && npm run build          # builds dist/, which the gateway serves at /glass/
cyclone-device-gateway glass                      # starts the local gateway if needed and opens the browser
```

`cyclone-device-gateway glass` mints a one-time launch code and opens `http://127.0.0.1:<port>/glass/#code=â€¦`. The page exchanges
the code for a session that lives only in that tab. A new tab needs the launcher again.

## Connect a phone (Devices)

Plug the phone in by USB and allow USB debugging. In Glass, **Devices** lists every phone this PC sees:

- **Ready to connect** â†’ **Connect**. Glass shows a six-digit code; the phone shows a "Connect this PC?" notification with
  the same code. Open it, check the code, tap **Allow**. The phone and the PC keep the trust; Glass never stores it.
- **Connected** â†’ **Use in Glass**, **Reconnect** (when the phone was locked or restarted), **Disconnect** (asks first).
- **Needs the phone** â†’ allow USB debugging or reconnect the cable.

Glass opens on Devices whenever no phone is ready. Pay, send, delete and sign-in steps still ask on the phone.

Development with hot reload: `npm run dev` (Vite on `127.0.0.1:5178`, proxying `/v1` to `CYCLONE_GATEWAY_URL`, default
`http://127.0.0.1:8765`). Open the launch link the CLI prints with `--print-url` and swap in the dev port.

## Pages

| Page | What it shows (all computed on the phone) |
|---|---|
| **Home** | Phone version, apps mapped, runs and failures today, runs over the last 7 days, apps that need attention (critical scenarios, failed last run, updated since mapped), latest runs, knowledge at a glance |
| **Devices** | Connect with a six-digit code + Allow on the phone, reconnect, disconnect; shows the name the phone uses for this PC (log it out on the phone under PC Gateway â†’ Linked PCs) |
| **Apps** â†’ one app | **Map** (board, inspector, start mapping at a depth, live cursor, teach), **Screens**, **Scenarios** (Sign in / Already signed in first; cards or board; mapping pass or your teaching), **Versions**, **Runs**, **Issues** (every open problem with its fix) |
| **Runs** | Every run with filters, or grouped by **Goals** (how often each sentence worked). The inspector shows steps, rooms, map vs model, cause of death with a fix link, the route on the map, scenarios reached, a comparison with the last good run, and **Ask again**; running runs update live. **Export CSV** of the runs in view |
| **Knowledge** | Vault slots as set / not set, **Never pressed** (guarded doors per app, Show on the map), skills, automations, Atlas totals |
| **Phone** | Live view, take control, tap / type / scroll, Ask (a finished Ask links to its run), You are here |
| **Settings** | Connection, phones, Safety rules, keyboard shortcuts |
| **Command Center â†’ Ports** | Plugins plugged into Cyclone runs (plan 48): add one by its address, allow each port with a switch, give it its key (shown once), watch it pass the contract's checks; then health, a test message, pause, new key, remove; the port catalog and who serves each port; recent activity (metadata only). **Port map** (`#/command/ports/map`): which plugin serves each port, everywhere or for one routine or app, with conflicts to settle. **Activity** (`#/command/ports/activity`): test runs played through your real plugins, each run's lane, and the filtered log |

Keyboard: `g` then `h` `d` `a` `r` `k` `p` `s` jumps to a page; `/` focuses search.

## Layout

Command Center â†’ **Connections** includes a dedicated **MRZ Studio Â· Employee ID** entry. The runtime keeps discovering
Studio on this PC, offers one-click setup or the saved Glass JSON paste path, and preserves approved connections across
updates. See [MRZ Studio setup, boundaries and acceptance](../../docs/GLASS_MRZ_STUDIO.md).

| Path | What |
|---|---|
| `src/app.ts` | Shell: sidebar, phone picker, one mounted page |
| `src/core/` | Router (hash routes), session bootstrap (launch code â†’ tab session) |
| `src/services/` | Gateway client (same origin, bearer, error normalisation), devices, phone-owned data clients |
| `src/ui/` | DOM helpers, icons, the component set every page uses |
| `src/pages/` | One file per page; pages compose components and never restyle them |
| `src/styles/` | `tokens.css` (the only place colours, spacing and type are defined), base, components, shell |
| `tests/` | `node --test` against compiled `.test-dist`, DOM via `tests/helpers/mini-dom.mjs` |

## Rules

- Phone thinks and mutates. Glass shows, inspects and sends commands through the gateway.
- No secret values on the wire, in the page, in `localStorage` or in logs. The session bearer lives in memory + `sessionStorage` for one tab.
- One design system. New UI uses `ui/components.ts` and `tokens.css`; no page-level colour values.

```bash
npm test          # unit + DOM tests
npm run build     # typecheck + production bundle
```

Glass Alpha 56 includes **Ports â†’ ID Generator**, a native starter for MRZ Studio Local 7.2.1+. Its local panels preserve Studio's generator/defaults; Glass controls pairing, consent and developer agent-usage policy. See `tools/cyclone-ports-sdk/starters/id-generator/SKILL.md` and `docs/RELEASE_5.0.0-alpha.101.dev1.md` for the workflow and acceptance limits.
