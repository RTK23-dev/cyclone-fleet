# Cyclone version matrix — snapshot 2026-09-08 (Europe/Amsterdam)

## What is installed / published

| Surface | Installed (Pixel / Agent PC) | Latest published | This branch (`grok/one-1.1-s5-release`) |
|---|---|---|---|
| Mobile APK | **4.0.3** / versionCode **74** (Pixel may lag) | **4.0.4** / versionCode **75** (`v4.0.4` @ `38f7628`) | still **4.0.4** / **75** — do not bump |
| Cyclone One | **1.0.0** (`C:\Users\Agent\AppData\Local\Cyclone One`) | **1.0.0** (cut with `v4.0.0`) | **1.1.0** (release-lane identity; GitHub tag one-1.1.0 is not claimed) |
| Device gateway | (bundled with One 1.0.0) | published **4.0.0** | **4.1.0** |
| Agent MCP | One 1.0.0 `CycloneAgentMCP.exe` | published **4.0.0** | **4.1.0** |
| Legacy Companion | **3.8.1** still installed beside One | obsolete for V4 | warn + prefer Cyclone One; uninstall legacy so doctor/install-path stop flipping |

Authoritative IDs: `release/version.toml` — `product_version=4.0.4`, `android_version_code=75`, `python_version=4.1.0`, `components.mobile=4.0.4`, `pc_companion=1.1.0`, `device_gateway=4.1.0`, `mcp=4.1.0`, `channel=development`, `publication_authorized=false`. Physical Pixel stays **UNVERIFIED**.

## Repo divergence (critical)

- `main` tip is **behind `v4.0.4`** (release line ~17 commits ahead of stale `main` / 4.0.1 source)
- PRs for this sprint may target the **`v4.0.4` / `release/cyclone-mobile-v4.0.4` line**, not stale `main`
- Sprint bases must start from **`v4.0.4` tag** (or a `release/cyclone-mobile-v4.0.4` tip), **not** stale `main`
- First housekeeping PR (optional Stage 0): fast-forward / merge release line into `main` so Grok worktrees and CI agree

## Pixel retest evidence (One 1.0.0 ↔ phone 4.0.3)

Works today with `session_id=default-foreground` + live PC gateway bearer:
- USB / a11y / AI trust / semantic observe → locate → home → `open_app` (`params.package`) → Chrome
- MCP cold-start `serve` ~3s per process
- PC bearer **no longer requires env scrape** (A1 code path: DPAPI / 0600 runtime file + token-free locator; Pixel **UNVERIFIED**)
- Raw NDJSON without token = AUTH_REQUIRED

Installed One **1.0.0** HTTP `/v1/observe` empty body 422'd. This branch (`grok/one-1.1-s5-release`) accepts empty-body legacy compact observe; MCP uses `/v1/capabilities/observe`. Operator pack browse is typed MCP status→observe→locate→home→open_app Chrome **without OpenRouter**. Timings live in the operator pack test dict. Physical Pixel remains **UNVERIFIED**.

## MCP gap vs mobile 4.0.3 notes

Installed One **1.0.0** MCP tools include `phone_virtual_*` but **no `phone_workspace`**, and its glass does not treat named Session Kernel VDs as first-class tiles.

This branch (`grok/one-1.1-s5-release`) keeps those gaps closed in source:

- **A1** bearer seam: persisted DPAPI / 0600 runtime file, token-free locator, doctor-without-scrape.
- **A2** Layer 2: MCP `phone_workspace` plus gateway `workspace.list/register/switch/pause/release/arm/next` and GET/POST `/v1/devices/{id}/workspaces` (`cyclone.one.layer2.v1`). After switch, mutating `phone_act.params` carry `workspaceId` + `workspaceGeneration`.
- **A3** named VD tiles: `session.added` / `session.removed` with `session_id`, `displayId`, owner HUMAN/AI, per-session JPEG focus, no silent rewrite to display 0.
- **A4** operator pack: `phone.open_app` requires `params.package`; HTTP `/v1/observe` empty 422 is fixed; `phone.home` already-on-home is not `VERIFICATION_FAILED`; typed MCP browse without OpenRouter; `session_id=default-foreground` in One UI + doctor.
- **A5** drop alphas; One **1.1.0** / gateway-MCP **4.1.0** identity; operator cut path after CI.

A1–A5 close the One 1.0.0 gaps in source. Installed One **1.0.0** still lacks them until the operator cuts `Cyclone-PC-Companion-1.1.0-Setup.exe`. Physical Pixel remains **UNVERIFIED**.

## Three control planes (do not collapse)

1. **Human foreground** — `session_id=default-foreground`, `displayId=0`
2. **Session Kernel VD** (V4 Stage 2) — named `sessionId` + `displayId>0` (Shizuku isolated display); one hot Ask still product-gated
3. **Layer 2 workspaces** (mobile 4.0.3) — N registered display-0 app/profile workspaces; **time-sliced** global mutate lock; NOT parallel phone input; switch verifies package/user/generation

One glass must label these distinctly. Mobile must fail closed when a call mixes planes incorrectly.

## Pairing

One **1.1.0** pairs with mobile **≥ 4.0.4**. Ideally mobile **4.1.0** (separate PR **#73**) for the Layer 2 MCP / dual-plane contract.
