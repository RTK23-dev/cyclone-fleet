# Cyclone coding-agent guide

Cyclone's active baseline is the V4 **4.0.0** Session OS + Cyclone One **1.0.0** on the 3.9.12 workspace plus Stage 1 Fast Path, Stage 2 Session Kernel, Stage 3 Skill Compiler and Stage 4 One glass. Do not reconstruct retired V2/V3 plans, the old Core/Desktop control plane, Teamwork Sniper experiments or version-specific handoff documents unless a task explicitly asks for historical research.

## Read first

1. `README.md`
2. `docs/ARCHITECTURE.md`
3. the owning module's README / nearest tests

Load more context only when the task needs it.

## Product invariants

- Android package: `com.cyclone.mobile`
- Launcher: `.MainActivity`
- `PhoneToolExecutor` is the canonical phone mutation engine.
- Prefer learned routes, `phone.open_app` / intent landing, and semantic selectors before coordinates or vision.
- Re-observe after page-changing actions. Ordinary taps use Fast Path fingerprint settle (300ms, then +500/+1000); Unchanged is not a second click.
- Transport success is not task success. One screen-changing mutation per agent decision turn; form fills may batch.
- Keep approval boundaries for pay/send/delete/permission/authentication-sensitive actions.
- The fleet layer never answers an approval. Glass may show the ask and open the Command Center approvals tab. It must not add an approve or send route.
- The PC holds no model key and makes no model call. Each phone's own Mind does the work.
- Never persist passwords, OTPs, API keys, payment data or raw typed secret values in Brain, learning stores or diagnostics.
- Run diagnostics may contain model-visible context, decisions, tool calls/results, verification and recovery—not hidden provider chain-of-thought.
- PC integrations route through the constrained gateway/MCP contracts; do not expose generic shell/root control to the model.
- Task buttons (stop, take over, I'm done, approve, confirm…) on any surface go through Task Kit (`TaskCommands` in `apps/mobile/.../task/`) to the engine that owns the task; surfaces never call an engine directly (guarded by `scripts/ci/tests/test_mobile_task_kit.py`). See `Cyclone V5 plan/17-structure.md`.

## Ownership

- Android runtime + UX: `apps/mobile/**`
- Device gateway: `apps/device-gateway/**`
- Cyclone for Windows (web-only): `packaging/pc/**`, `scripts/pc/**`, gateway PC features in `apps/device-gateway/cyclone_device_gateway/pc/**` and `terminal/**`; the retired desktop window in `apps/pc-companion/**` (reference only), PyInstaller specs in `packaging/pc-companion/**`
- Cyclone Glass (local browser dashboard, no intelligence): `apps/glass/**`, gateway hosting in `apps/device-gateway/cyclone_device_gateway/glass/**`
- PC agent adapters: `tools/codex-phone-mcp/**`, `tools/cyclone-agent-mcp/**`
- CI/release: `.github/workflows/**`, `scripts/ci/**`, `release/version.toml`

Keep parallel agents on non-overlapping paths whenever possible.

## Versioning

The authoritative product/component metadata is `release/version.toml`. Android `versionName` and `versionCode` live in `apps/mobile/app/build.gradle.kts` and must agree with release metadata. Increment `versionCode` for every distributed Android build.

### Fast release lane (since 5.0.0-alpha.43; web-only PC since 5.0.0-alpha.47)

Releases ship **Android, Glass and Cyclone for Windows** from one push. Cyclone for Windows is web-only (plan 31): the
runtime (`CyclonePCRuntime.exe`), the agent MCP, Glass inside the runtime and the `cyclone` command, as one
`Cyclone-PC-<product_version>.zip`. The Cyclone One desktop window (`apps/pc-companion`, Tauri) is retired: its source
stays for reference, it is not built, and `pc_companion` in `release/version.toml` stays `1.6.0-alpha.43`.

To release: bump `product_version`, `components.mobile`, `android_version_code` (+ `build.gradle.kts`),
`python_version`, `components.device_gateway` and `components.mcp` (+ the three `pyproject.toml` files) when PC code
changed, and `components.glass` only when `apps/glass` changed; add `docs/RELEASE_<mobile>.md`; push to the dev branch.
`.github/workflows/v5-publish.yml` builds and smoke-tests the Windows package on a Windows runner (install into a
scratch profile, `cyclone version`, the runtime serving Glass and the authenticated `/v1/pc/*` routes), waits for
Mobile CI on that commit, signs the APK with the rotated key, builds the Glass zip and publishes, with every file's
SHA-256 in `release-manifest.json`. No RC branch and no per-release publisher. Do not push other commits to the dev
branch until the publish finishes (Mobile CI cancels in-progress runs per branch).

Owners install by double-clicking `Cyclone-Setup-<product_version>.exe` (since alpha.48: NSIS, per user, no admin,
`packaging/pc/cyclone-setup.nsi`; it runs the same `install.ps1` on the package it carries, then adds Start menu and
desktop shortcuts and an Apps & features entry whose uninstaller is `Uninstall Cyclone.exe`, never `uninstall.exe`),
or with `irm https://github.com/premiumcentraal-boop/Cyclone/releases/download/<tag>/install.ps1 | iex` (per user,
no admin, into `%LOCALAPPDATA%\Cyclone One`), and update with `cyclone update`; all refuse a package whose SHA-256
is not in the release manifest. The package build installs and uninstalls the setup silently on Windows before a
release. `CYCLONE_GLASS_DIST` remains a developer override for serving a local Glass.

## Validation

For mobile changes:

```bash
cd apps/mobile
./gradlew :app:testDebugUnitTest
```

For PC gateway/MCP changes:

```bash
python -m pip install -e 'apps/device-gateway[test]' -e tools/codex-phone-mcp -e tools/cyclone-ports-sdk
python -m pytest apps/device-gateway/tests -q
python -m unittest discover -s tools/codex-phone-mcp/tests -v
python -m pytest scripts/ci/tests/test_pc_web_only.py -q
python -m pytest tools/cyclone-ports-sdk/tests -q   # Cyclone Ports kit; the gateway's Port Hub imports it
```

For Cyclone Glass changes:

```bash
cd apps/glass && npm ci && npm test && npm run build
python scripts/ci/glass_guard.py
python -m pytest apps/device-gateway/tests/test_glass_hosting.py -q
```

Run `python scripts/ci/release_versions.py --check` and `python scripts/ci/mobile_product_guard.py` when product identity or release surfaces change.

## Definition of done

A change is done when behavior is implemented, the relevant tests/guards pass, privacy/security invariants remain intact, version identity is coherent, and physical-device verification is stated honestly when applicable.
