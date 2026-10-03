# Development

## Baseline

New product work should start from the latest accepted Cyclone baseline rather than an old version branch. Keep each change in a focused feature/chore branch and preserve the runtime invariants in `AGENTS.md`.

## Android

Requirements: JDK 17 and Android SDK 35.

```bash
cd apps/mobile
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

For changes to agent execution, also run the closest unit tests for AI, phone tools, verification, recovery, Brain diagnostics and overlay behavior.

## PC gateway / MCP

```bash
python -m pip install -e 'apps/device-gateway[test]' -e tools/codex-phone-mcp
python -m pytest apps/device-gateway/tests -q
python -m unittest discover -s tools/codex-phone-mcp/tests -v
```

Start Cyclone One once so it persists the PC gateway bearer (Windows DPAPI, or a 0600 runtime file under `%LOCALAPPDATA%\Cyclone One\runtime\`). `CycloneAgentMCP serve`, Cursor MCP and `doctor` load that bearer; do **not** scrape `CyclonePCRuntime` process env. Cursor `~/.cursor/mcp.json` `mcpServers.cyclone-phone` must point at Cyclone One (not Cyclone PC Companion) and must not store `CYCLONE_DEVICE_GATEWAY_TOKEN`. `doctor` reports PC Bearer / Install Path / Cursor MCP without printing tokens. A live-phone doctor run is not a merge gate; physical Pixel remains UNVERIFIED. See [`ONE_1.1_STAGE1_TOOLING.md`](ONE_1.1_STAGE1_TOOLING.md).

ChatGPT and grok.com chat connectors use the **Settings → Remote MCP** HTTPS tunnel, not local stdio. Copy the live MCP URL and bearer from that card after Start. Do not paste the public URL into `~/.grok/config.toml`. See [`A4_MCP_TUNNEL_SETTINGS.md`](A4_MCP_TUNNEL_SETTINGS.md).

## CI guards

Repository CI also validates product identity, the single launcher, the canonical phone executor, version coherence and critical 3.9 UI surfaces.

## Device verification

CI is not physical-device evidence. Changes involving Accessibility behavior, overlays, permissions, PackageInstaller, real app navigation or OEM-specific behavior should explicitly state whether they were tested on a physical Android device.
