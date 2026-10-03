# Cyclone One 1.1.0 — Session Contract Glass

Cyclone One **`1.1.0`** (Windows glass / `Setup.exe`). Device gateway and MCP adapters are **`4.1.0`**. Mobile on this tree stays **`4.0.4`** / versionCode **75**. This cut does not ship a new APK.

Base: One **1.0.0** sources as of `v4.0.0`, rebased onto the **`v4.0.4`** mobile protocol (tag `38f7628`) so gateway and MCP speak Layer 2. Stacked A1–A4 on that line; A5 is this release lane. Do not rewrite that history.

| Stage | PR | Branch / tip | Identity |
| --- | --- | --- | --- |
| A1 Tooling | #66 | `grok/one-1.1-s1-tooling` tip `d044bc2` | `1.1.0-alpha.1` / `4.1.0-alpha.1` |
| A2 Layer 2 | #68 | `grok/one-1.1-s2-layer2` tip `88905a4` | `1.1.0-alpha.2` / `4.1.0-alpha.2` |
| A3 Sessions | #70 | `grok/one-1.1-s3-sessions` tip `03d1313` | `1.1.0-alpha.3` / `4.1.0-alpha.3` |
| A4 Operator | #72 | `grok/one-1.1-s4-operator` tip `4d75911` | `1.1.0-alpha.4` / `4.1.0-alpha.4` |
| A5 Release lane | this PR | `grok/one-1.1-s5-release` | **`1.1.0` / `4.1.0`** |

Physical Pixel 8: **UNVERIFIED**. `publication_authorized=false`. This document is product release notes for the One 1.1 cut, not a claim that GitHub tag `one-1.1.0` or a GitHub Release already exists. Alpha identities on the way here (`1.1.0-alpha.1` … `alpha.4` / `4.1.0-alpha.1` … `alpha.4`) were not published One installers. The release identity is **One `1.1.0` + gateway/MCP `4.1.0`**, with mobile still **`4.0.4` / 75**.

Operator merge order and cut commands live in [`ONE_1.1_STAGE5_RELEASE.md`](ONE_1.1_STAGE5_RELEASE.md). This file is what shipped, not how to cut it.

## Session Contract Glass

1.1 makes One attach without scraping process memory, speak the mobile Layer 2 workspace protocol, draw named Session Kernel VD tiles as first-class glass, and make the default-foreground MCP browse pack boringly reliable. It does not redo V4 Fast Path, Session Kernel, Skill Compiler, or One 1.0.0 JPEG glass.

Installed One **1.0.0** kept the PC bearer in memory, had no `phone_workspace`, treated named Shizuku VDs as second-class, and left operator browse fragile (`phone.open_app` mixed names, empty observe 422, already-on-home as `VERIFICATION_FAILED`). Mobile **4.0.3+** already shipped Layer 2 on the phone. One 1.1 absorbs that protocol on PC/MCP/glass and keeps the three Session Contract planes distinct.

North star: any local tool attaches to One without scraping process env. Operators see and own the correct plane (foreground / VD session / Layer 2 workspace). The PC never becomes a second brain. `PhoneToolExecutor` on Android is the only mutator.

The phone remains authority. Ordinary navigation uses accessibility Page Cards and a local fingerprint settle. Cyclone One remains glass: JPEG live view, human/AI handoff, session tiles, Layer 2 strip, and MCP routed with an exact plane identity. The PC does not run a second Fast Path, skill compiler, or `PhoneToolExecutor`.

Three planes, no fourth. Do not collapse them. Full contract: [`SESSION_CONTRACT.md`](SESSION_CONTRACT.md).

| Plane | Identity | Display | Mutation model |
| --- | --- | --- | --- |
| Foreground human | `session_id=default-foreground` | `displayId=0` | Direct; human may hold input |
| Session Kernel VD | named `session_id` | `displayId>0` required | Isolated VD inject; never display 0 |
| Layer 2 workspace | `workspaceId` + `workspaceGeneration` | stays display 0 | Time-sliced global mutate lock |

One glass must never draw a Layer 2 workspace as a VD tile or vice versa. Named VD Ask still hot-gates **one** concurrent task. Types and APIs already hold N≥2. Do not claim 20 concurrent virtual displays.

Transport success is not task success. Vision is an escalate when the tree is empty or `perceptionMode=vision_escalate`, not the primary loop.

## Pairing with Cyclone mobile

This pairing note is the 1.1 contract. Do not skip it.

- One **1.1.0** pairs with mobile **≥ 4.0.4**.
- **Full Layer 2 MCP** (`phone_workspace` list / register / switch / pause / release / arm / next, and `workspaceId` + `workspaceGeneration` on `phone_act.params`) needs a phone that already speaks Layer 2 (published **4.0.4**; **ideally mobile 4.1.0** for the dual-plane Session Contract).
- Mobile **4.1.0** is a separate B5 cut (PR **#73**). Do not claim it is merged, and do not claim that tag `v4.1.0` exists.
- A **4.0.4 phone + One 1.0.0** remains **foreground-capable**: USB / trust / default-foreground observe / act is still the 4.0.4 pairing contract. That pair does **not** have `phone_workspace`.
- Do **not** require mobile 4.1.0 to install One 1.1.0. Foreground MCP browse on a published 4.0.4 phone is the expected 1.1 pairing path until 4.1.0 is a published B5 cut.

This One **1.1.0** tree keeps mobile **4.0.4** / versionCode **75**. Mixing One 1.1.0 glass with a 4.0.4 phone is the Layer 2 MCP path that 4.0.3 already implemented on Android; the dual-plane Session Contract harden (fail-closed mix matrix, `plane` metadata) is the 4.1 mobile line. Full Layer 2 MCP against a physical Pixel 8 is **UNVERIFIED**.

## Uninstall legacy Companion 3.8.1

Legacy **Cyclone PC Companion 3.8.1** may still sit beside One under a separate install path. Uninstall Cyclone PC Companion 3.8.1 before or immediately after installing One 1.1.0.

Prefer `%LOCALAPPDATA%\Cyclone One`. Cursor `mcp.json` must point at Cyclone One's `CycloneAgentMCP.exe`, never at the legacy Companion. Mixing 3.8.1 Companion + One 1.1.0 makes doctor and install-path checks flip between two products.

## What shipped

Shipped in source / CI. Physical Pixel 8 remains **UNVERIFIED**. CI green is not device evidence.

### Tooling seam (A1, PR #66)

- The PC gateway bearer is persisted for local tools and is never printed. Windows uses a current-user **DPAPI** file `gateway-token.dpapi`; non-Windows uses `gateway-token.json` mode **0600**. Location is `%LOCALAPPDATA%\Cyclone One\runtime\`.
- A token-free locator `gateway-locator.json` (`schema=cyclone.one.gateway.locator.v1`) carries URL / port / runtime / MCP executable only. `sessionSecretPersisted=true` once a bearer is on disk for local tools.
- Scraping `CyclonePCRuntime` process memory or env is forbidden. The token is not written into Cursor `mcp.json`, doctor output, or the public locator. Existing process env still wins when already set; tools that need the bearer load it from DPAPI / the runtime file, then inject env in-process.
- Cursor `mcpServers.cyclone-phone` points `command` at **Cyclone One** (`%LOCALAPPDATA%\Cyclone One\CycloneAgentMCP.exe`) with `args: ["serve"]`, carries URL / port / runtime in `env`, and does **not** store `CYCLONE_DEVICE_GATEWAY_TOKEN`. If a writer sees a legacy Companion path, it rewrites to Cyclone One when that executable exists.
- `doctor` reports PC Bearer, Install Path, and Cursor MCP without printing tokens and without scraping process memory. PC Bearer READY means `sessionSecretPersisted=true` from the runtime secret. Install Path prefers Cyclone One under `%LOCALAPPDATA%\Cyclone One`. Cold MCP attach → `phone_status` without a manual token.

### Layer 2 on PC / MCP / glass (A2, PR #68)

- Gateway routes `workspace.list` / `register` / `switch` / `pause` / `release` / `arm` / `next` and GET/POST `/v1/devices/{id}/workspaces` under protocol `cyclone.one.layer2.v1`.
- MCP tool `phone_workspace`. After switch, mutating `phone_act.params` carry `workspaceId` + `workspaceGeneration`.
- Glass Layer 2 strip: registered workspaces, lock owner, pause / release, armed goal, generation. Distinct from VD session tiles.
- Fail closed on stale generation, wrong package (`TARGET_MISMATCH`), pending GATE, and named session mix. One glass never draws a Layer 2 workspace as a VD tile.

### Session Kernel glass (A3, PR #70)

- Real `session.added` / `session.removed` tiles with `session_id`, `displayId`, and owner HUMAN / AI.
- Per-session JPEG focus. Named VD is never silently rewritten to display 0.
- Pause / Take control / Give to AI per tile. `PHONE_LOCKED` / `HUMAN_HAS_CONTROL` stay fail-closed.
- Foreground vs Session Kernel VD vs Layer 2 workspace copy stays distinct. The A2 Layer 2 strip is not a VD tile. Product hot-gate for named VD Ask remains **1**.

### Operator MCP pack (A4, PR #72)

- `phone.open_app` requires `params.package`. App name / `packageName` errors are distinct. Chrome is `com.android.chrome`.
- Legacy compact HTTP `POST /v1/observe` accepts an empty body (no empty-422). MCP uses `POST /v1/capabilities/observe`.
- `phone.home` already-on-home is not `VERIFICATION_FAILED`.
- Typed MCP browse: `phone_status` → `phone_observe` → `phone_locate` → `phone.home` → `phone.open_app` Chrome. No OpenRouter key.
- `session_id=default-foreground` is named in One UI and doctor. Operator browse stays on Foreground (`session_id=default-foreground`, display 0).

V4 Fast Path settle, Session Kernel fail-closed identity, Skill Compiler learn → compile → replay, GATE, Take control / Continue, and One 1.0.0 JPEG live + human/AI handoff are preserved through the stack. Mutations still execute on the phone.

## Physical Pixel 8 checklist — UNVERIFIED

No item below was verified on a physical Pixel 8 for this cut. CI green is not device evidence. Leave every box unchecked until a human runs it on hardware.

- [ ] Pixel 8 USB connected — **UNVERIFIED**
- [ ] Shizuku / Accessibility / notification listener granted as required (Android 15+ required for background workspace) — **UNVERIFIED**
- [ ] Bearer doctor READY without process-memory scrape (`sessionSecretPersisted=true`; tokens never printed) — **UNVERIFIED**
- [ ] Cold MCP attach → `phone_status` without a manual token — **UNVERIFIED**
- [ ] Layer 2 list (`phone_workspace` list) against mobile **≥ 4.0.4** — **UNVERIFIED**
- [ ] Default-foreground Chrome open: `phone_status` → observe → locate → home → `open_app` `params.package=com.android.chrome`, `session_id=default-foreground`, no OpenRouter — **UNVERIFIED**
- [ ] Named VD tile vs Layer 2 strip stay distinct (no Layer 2 drawn as a VD tile, no silent rewrite of named VD to display 0) — **UNVERIFIED**
- [ ] Legacy Companion **3.8.1** uninstalled; install path is Cyclone One (`%LOCALAPPDATA%\Cyclone One`); Cursor `mcp.json` points at One's `CycloneAgentMCP.exe` — **UNVERIFIED**
- [ ] JPEG live view (~2 fps `adb-screenshot`, no H.264 handshake required) on default-foreground — **UNVERIFIED**
- [ ] Give control to AI / Take control; locked/asleep phone is `PHONE_LOCKED` and is not stolen — **UNVERIFIED**
- [ ] GATE still blocks pay / send / delete / permission / authentication-sensitive actions — **UNVERIFIED**

Background work still requires Android 15+, authorized Shizuku, Accessibility, and an app that supports isolated displays. Apps already on display 0, unsupported OEM task layouts, secure surfaces, unsupported text input and cross-app transitions fail closed or require handoff. Process death revokes execution and does not restore autonomous authority.

## Upgrade from One 1.0.0

| Surface | From | To |
| --- | --- | --- |
| Cyclone One (Windows glass) | **1.0.0** (cut with `v4.0.0`) | **1.1.0** |
| Device gateway / MCP packages | **4.0.0** | **4.1.0** |
| Mobile APK | **4.0.4** / versionCode **75** (`v4.0.4` @ `38f7628`) | still **4.0.4** / **75** (this tree does not ship a new APK) |

Breaking / must-change:

- Cursor MCP and doctor must attach through the persisted bearer and token-free locator. Do not scrape `CyclonePCRuntime` process env for `CYCLONE_DEVICE_GATEWAY_TOKEN`.
- Cursor `mcp.json` must point at Cyclone One's `CycloneAgentMCP.exe` under `%LOCALAPPDATA%\Cyclone One`, never at legacy Companion 3.8.1.
- Full Layer 2 operator demos over MCP (`phone_workspace` and `workspaceId` + `workspaceGeneration` on `phone_act.params`) require a phone that already speaks Layer 2 (mobile **≥ 4.0.4**). One 1.0.0 glass + a 4.0.4 phone remains foreground-capable only.
- `phone.open_app` requires `params.package`. Chrome is `com.android.chrome`. App name / `packageName` are not substitutes.

Retained from One 1.0.0 / V4:

- JPEG live is a sampled screenshot stream (~2 fps), not an H.264 fleet.
- Take control / Continue (intentional human take-over onto display 0; not a silent inject fallback).
- GATE confirm for pay / send / delete / permission / authentication-sensitive actions.
- MCP `session_id` required on observe / act and related UI tools (`SESSION_REQUIRED` / `SESSION_DISPLAY_MISMATCH`).
- One hot named-VD Ask workspace; follow-ups still require explicit start after releasing the previous workspace.
- Exact-session progress frames; no foreground screenshot fallback for a missing workspace frame.
- Phone remains authority. The PC does not run a second Fast Path, skill compiler, or `PhoneToolExecutor`.

Install / pairing notes:

- Replace One **1.0.0** (and uninstall leftover Companion **3.8.1**) with the One **1.1.0** installer artifact from this cut. Do not mix 3.8.1 Companion with 1.1.0 glass.
- Pair with a published mobile **4.0.4** / versionCode **75** phone. Do not wait on mobile 4.1.0 (PR #73) to install One 1.1.0.
- Loopback gateway only. Do not expose generic shell / root / ADB as model tools.
- Keep `publication_authorized=false` until a signed exact-source CI artifact exists. Physical Pixel 8 remains **UNVERIFIED**. This notes file does not flip that flag.

Operator commands live in [`ONE_1.1_STAGE5_RELEASE.md`](ONE_1.1_STAGE5_RELEASE.md).

## Limits / non-goals

- No Magisk, auto-root, or a second mutation engine on Windows.
- No 20 concurrent virtual displays, 20 concurrent VLMs, or 20 hot LLM agents. Extra session tiles are inventory.
- One hot named-VD Ask task. Hot-gate stays 1.
- JPEG live is a sampled screenshot stream (~2 fps), not an H.264 fleet or continuous provider video.
- Secrets (passwords, OTPs, API keys, payment data, raw typed secret values) are not persisted in Brain, playbooks, compiled skills, diagnostics, doctor output, Cursor `mcp.json`, or the public locator.
- ColorOS / OxygenOS may ignore `OWN_DISPLAY_GROUP` or steal focus. Fail closed rather than acting on display 0.
- Virtual display + Accessibility `windowsOnAllDisplays` is OEM-dependent. An empty workspace tree is `perceptionMode=vision_escalate`, not permission to inject on the human display.
- This tree does not ship a new mobile APK. Do not claim mobile 4.1.0 is merged, and do not claim tag `v4.1.0` exists.
- `publication_authorized=false` until a signed exact-source CI artifact exists. Physical remains **UNVERIFIED**. This notes file does not flip that flag or create GitHub tag `one-1.1.0`.

## How this is published

Do not treat these notes as a published GitHub Release. Tag **`one-1.1.0`** only after the stack merges in order **#66 → #68 → #70 → #72**, then this A5 PR on `grok/one-1.1-s5-release`. Operator commands live in [`ONE_1.1_STAGE5_RELEASE.md`](ONE_1.1_STAGE5_RELEASE.md).

Intended cut:

1. Merge Tooling, Layer 2, Sessions, and Operator (PRs #66, #68, #70, #72) without rewriting their history.
2. Land A5 (this notes file + the version / CI / packaging work owned by the other A5 agents).
3. Require gateway / MCP tests and repository product / security / version guards on the merged commit.
4. Create immutable tag `one-1.1.0` from that commit. Cut through `pc-companion-release.yml` → `Cyclone-PC-Companion-1.1.0-Setup.exe`. Pair that installer with the already-published mobile **4.0.4** / versionCode **75**.
5. Do **not** dispatch `mobile-release.yml` as part of this One cut. Mobile 4.0.4 is already published. Mobile 4.1.0 is PR #73.
6. Keep `publication_authorized=false` until the successful exact-source CI artifact and existing signer-continuity / protected-environment workflow are in place. Do not publish from a local tree. Physical Pixel 8 remains **UNVERIFIED**.

Alpha identities on the way here (`1.1.0-alpha.1` … `alpha.4` / `4.1.0-alpha.1` … `alpha.4`) were not published One installers. The release identity is **One `1.1.0` + gateway/MCP `4.1.0`**, with mobile still **`4.0.4` / 75**.
