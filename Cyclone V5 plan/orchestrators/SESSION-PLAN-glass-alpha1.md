# Session plan — Glass 1.0.0-alpha.1 (web shell, Apps, Map, Phone)

**Charter:** [`03-glass-v1.md`](../03-glass-v1.md) · **Cuts:** [`09`](../09-cuts-and-milestones.md#glass-web-cuts-owner-charter-2026-09-23)
**Base:** `v5/integration` @ `daceebe8` (plan corrected) · **Written:** 2026-09-23
**Status:** scoped; waiting for the owner's green light.

## Goal

Open **Cyclone Glass in a browser** on the PC, see **every app on the phone** with its map status and version, open an app's **Map** (pan, inspect, start and watch mapping), and **drive the phone** (live view, take control, give back, Ask). No intelligence in Glass.

## What exists (verified in code)

| Piece | Where | State |
|---|---|---|
| Local gateway | `apps/device-gateway`, `cyclone-device-gateway serve` (FastAPI, 127.0.0.1) | One process already serves fleet, pairing/trust, `/control`, V5 routes (`atlas/*`, `mapping/*`, `ask/*`, `secrets/*`) and the live video WebSocket `/v1/devices/{id}/video` |
| Browser-compatible auth | bearer header; WebSockets accept subprotocol `cyclone-token.<token>` | works from a browser; the only Tauri dependency is how the page gets the token (`invoke("gateway_session")`) |
| Prototype Glass pages | `apps/pc-companion/src/pages/mapsPage.ts` (809), `ui/appMapCanvas.ts` (563), `services/atlasClient.ts` (966), `maps/mappingWatcher.ts`, `pages/askPage.ts`, `ui/livePhoneView.ts` + tests (`maps-*`, `atlas-*`, `ask-*`, `live-phone`) | plain TypeScript + DOM over HTTP/WS; portable |
| Phone place list | `atlas.places` | only places the Atlas already knows; **no installed-app list, no installed version** |
| App version on doors | `AtlasStore` edge evidence `appVersion {versionName, versionCode}` | stored; not summarised per app |

**Gap found:** alpha.5 and alpha.6 (Trace Field, `af198034`) were published from their release branches and never merged back into `v5/integration`. The merge is clean (`git merge-tree` checked). This is step 0.

## Checkpoints

| # | Checkpoint | Paths | Done when |
|---|---|---|---|
| **0** | Bring alpha.6 into `v5/integration` | merge `release/cyclone-mobile-v5.0.0-alpha.6.dev1` | integration carries Mobile 5.0.0-alpha.6.dev1 (147); Mobile CI green |
| **A** | `apps/glass` scaffold + design system | `apps/glass/**` (Vite + TypeScript, no Tauri, no framework runtime), `tokens.css` + a small component set (sidebar, page header, card, table, chip, button, empty state) | `npm test` / `npm run build` pass; one stylesheet system; light + dark tokens; Minitap-like layout (left sidebar, content, dotted board) |
| **B** | Gateway serves Glass | `apps/device-gateway`: mount the built bundle at `/glass/` when present; `POST /v1/glass/launch-code` (bearer, single use, 60 s) → `POST /v1/glass/session` exchanges the code for the bearer; CSP + `no-store` headers on `/glass`; new CLI `cyclone-device-gateway glass` (starts `serve` if not running, mints a code, opens the browser at `http://127.0.0.1:<port>/glass/#code=…`) | pytest: static served, codes single-use and expiring, bearer never in the HTML/URL after exchange, loopback only |
| **C** | Phone `apps.list` | mobile: launcher apps + Chrome origins, installed `versionName/versionCode`, map status, rooms/doors, mapped versions (from door evidence), `needsRemap`; gateway op + route `GET /v1/devices/{id}/apps` + contract validation | JVM tests (fake PackageManager + AtlasStore), gateway contract tests; no secrets, no user content in the list |
| **D** | Glass **Apps** page (home) + device picker | `apps/glass/src/pages/apps*` | lists every app with version, mapped versions, needs-remap, rooms/doors, **Map** action; honest states: no gateway · no phone · not paired ("pair in Cyclone One" for now) · phone < 5.0 ("update the phone") |
| **E** | App → **Map** tab | port `mapsPage`/`appMapCanvas`/`atlasClient`/`mappingWatcher` + their tests into `apps/glass`, restyled to the design system | pan/zoom/fit, room + door inspector, Start/Pause/Stop mapping, live cursor from `atlas.diff`; ported tests green |
| **F** | **Phone** page | port `livePhoneView` + Ask composer/HUD | live JPEG over `/video`, Take control / Give back (`/control`), Ask sends the unchanged sentence (`ask.start`) and mirrors `ask.status`; `HUMAN_HAS_CONTROL` / `PHONE_LOCKED` honest |
| **G** | Identity, guards, CI, release | `release/version.toml` new `glass = "1.0.0-alpha.1"` + `release_versions.py`; new `.github/workflows/glass-ci.yml`; guard that `apps/glass` has no model/LLM calls or keys; `AGENTS.md` ownership line; include the Glass bundle in the `CyclonePCRuntime` PyInstaller spec so a PC with Cyclone One installed can open Glass today | all guards green; paired Mobile `5.0.0-alpha.7.dev1` (148) because of checkpoint C |

Push after every checkpoint. PR into `v5/integration` at the end; developer prerelease only on the owner's say-so.

## Out of scope (later cuts)

Runs list and run inspector (alpha.2), rooms/frames per step (alpha.3), Scenarios and Versions tabs (alpha.4), Knowledge, Vault page, pairing UI inside Glass, a standalone Glass installer, removing the prototype pages from Cyclone One (after parity), packaged launchers for macOS/Linux (they use `pip install -e apps/device-gateway` + `cyclone-device-gateway glass`).

## Security and privacy

- Binds to `127.0.0.1` only. Same origin, so no new CORS origins.
- The launch code is single use and short-lived. The bearer is held in memory and `sessionStorage` for the tab; never `localStorage`, never in a URL after exchange.
- No secret values on the wire or in the page (existing `secretGuards` / `reject_secret_payload` stay on both sides).
- `apps.list` carries package names, labels, versions and counts only.

## Risks

- **Pairing lives in Cyclone One.** alpha.1 shows trust state and sends you to One to pair. Porting pairing is a later cut.
- **Windows delivery.** Serving Glass from One's runtime sidecar gets it onto the owner's PC without a new installer, but the sidecar must be rebuilt with the bundle; if the PyInstaller data path fights us, fall back to "run from the repo" for alpha.1 and say so.
- **Device-unproven.** Everything is CI/JVM/pytest-tested with fakes; physical verification stays waived or UNVERIFIED unless the owner runs it.

## Owner decisions (defaults if no answer)

1. **Windows delivery:** Glass served by the runtime that Cyclone One already installs (default) vs a separate Glass download now.
2. **UI stack:** plain TypeScript + a small in-house component set (default; reuses the tested prototype code) vs a framework (Svelte/Preact) from day one.
3. **Theme:** Minitap-like light layout with a dark mode (default) vs dark-only like today's Glass.
