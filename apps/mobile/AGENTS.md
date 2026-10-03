# Cyclone Mobile agent guide

This module is the Android product. Keep changes focused on the current runtime and its tests.

## Invariants

- Package: `com.cyclone.mobile`
- Launcher: `.MainActivity`
- Android 13+ (`minSdk 33`); isolated background displays require Android 15+
- `PhoneToolExecutor` is the canonical phone mutation path.
- Re-observe after page-changing actions. Ordinary taps use Fast Path fingerprint settle; Unchanged is not a second click.
- Prefer `phone.open_app` / intent landing and semantic selectors before coordinates or vision.
- One screen-changing mutation per agent decision turn; form fills may batch.
- Keep approval boundaries for consequential actions.
- Never persist credentials, OTPs, payment data or raw typed secrets in Brain/run logs.
- Brain diagnostics may expose model-visible context, decisions, tool calls/results, verification and recovery events, but not hidden provider reasoning.

## Main product surfaces

`Home`, `Profiles`, `AI / Ask Cyclone`, `Routines`, `Brain`, `Settings`, plus the persistent Aurora activation overlay.

## Before committing

Run from `apps/mobile`:

```bash
./gradlew :app:testDebugUnitTest
```

For a release candidate also assemble the release artifact through the repository CI workflow. Physical-device behavior must be reported separately from unit/CI verification.
