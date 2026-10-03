# Cyclone One 1.1 Stage A1 — tooling seam

**Status:** DONE (code + docs)  
**Identity:** mobile `4.0.4` / versionCode `75` (unchanged); Cyclone One `1.1.0-alpha.1`; device gateway / MCP `4.1.0-alpha.1`  
**Branch:** `grok/one-1.1-s1-tooling`  
**Physical Pixel 8:** **UNVERIFIED** — do not run `doctor` against a live phone as a merge gate. A green CI or unit path is not device evidence.

Stage A1 stops operators from scraping `CyclonePCRuntime` process environment for the PC gateway bearer. Local tools attach through a persisted secret plus a token-free locator. It does **not** add Layer 2 / `phone_workspace`, session tiles, the operator pack, a GitHub release cut, or a mobile APK.

## Why

Installed One **1.0.0** kept the PC bearer in memory. Operators scraped `CyclonePCRuntime` env (`CYCLONE_DEVICE_GATEWAY_TOKEN` and friends) so Cursor MCP and `doctor` could attach. That is fragile, leaks the token into process listings, and breaks cold MCP start.

## Solution

Persist the PC gateway bearer for local tools; never print it.

| Surface | Behavior |
|---|---|
| Secret | Windows: current-user **DPAPI** file `gateway-token.dpapi`. Non-Windows: `gateway-token.json` mode **0600**. |
| Location | `%LOCALAPPDATA%\Cyclone One\runtime\` |
| Locator | Token-free `gateway-locator.json` (`schema=cyclone.one.gateway.locator.v1`). URL / port / runtime / MCP executable only. |
| Flag | `sessionSecretPersisted=true` once a bearer is on disk for local tools. |
| Forbidden | Scraping `CyclonePCRuntime` process memory or env; writing the token into Cursor `mcp.json`, doctor output, or the public locator. |

Existing process env still wins when already set. Tools that need the bearer load it from DPAPI / the runtime file, then inject env in-process.

Auto-injected when missing:

- `CYCLONE_DEVICE_GATEWAY_TOKEN`
- `CYCLONE_DEVICE_GATEWAY_URL`
- `CYCLONE_DEVICE_GATEWAY_PORT`
- `CYCLONE_DEVICE_GATEWAY_RUNTIME`

## MCP / Cursor

`CycloneAgentMCP serve` loads the persisted bearer. Cursor config is `~/.cursor/mcp.json`.

`mcpServers.cyclone-phone` must:

1. Point `command` at **Cyclone One** (`%LOCALAPPDATA%\Cyclone One\CycloneAgentMCP.exe`), never at **Cyclone PC Companion**.
2. Use `args: ["serve"]`.
3. Carry URL / port / runtime in `env`.
4. **Not** store `CYCLONE_DEVICE_GATEWAY_TOKEN`. The sidecar loads the token from DPAPI / the runtime file.

If a writer sees a legacy Companion path, it rewrites to Cyclone One when that executable exists.

## Doctor

`doctor` reports PC Bearer, Install Path, and Cursor MCP without printing tokens and without scraping process memory.

| Check | Ready means |
|---|---|
| PC Bearer | `sessionSecretPersisted=true` from the runtime secret, not from a process-env scrape |
| Install Path | preferred product is Cyclone One under `%LOCALAPPDATA%\Cyclone One` |
| Cursor MCP | `mcpServers.cyclone-phone` points at One and omits the token |

`Tokens are never printed by doctor.` Transport success is still not task success. Do not require a live Pixel `doctor` run to merge this stage.

## Legacy Companion 3.8.x

Cyclone PC Companion **3.8.x** may still sit beside One. Detect and **warn**. Prefer Cyclone One. Uninstall the legacy companion so MCP and install-path checks stop flipping between two products.

## How to attach (no manual token)

1. Start **Cyclone One** once. It persists the bearer and writes the token-free locator.
2. Run `doctor` (or Cursor MCP `phone_status`) on the Agent PC.
3. Cold MCP attach uses the persisted bearer. Do not copy a token out of process env.

Physical Pixel remain **UNVERIFIED**. USB / a11y / `phone_status` on hardware is operator evidence, not a merge gate.

## Out of scope

- **A2** Layer 2 / `phone_workspace` / `workspace.*` gateway routes
- **A3** named VD session tiles
- **A4** operator MCP pack
- **A5** One 1.1.0 release cut
- Mobile APK (`4.0.4` / versionCode `75` stays)

## Handoff to A2

Next stage: `docs/ONE_1.1_BUILD_PLAN.md` Stage **A2** on `grok/one-1.1-s2-layer2` from this A1 tip. Goal: One/MCP speak mobile 4.0.3+ `workspace.*` / `phone_workspace`. Do not start A2 in this change.
