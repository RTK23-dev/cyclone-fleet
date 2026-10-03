# CONTINUE.md — hand-off

Read this, then `AGENTS.md`, then `docs/FLEET.md`. Do not claim a device check passed unless it ran on a phone.

## Baseline

Cyclone 5.0.0-alpha.102.dev1 with the fleet layer. Repo: `RTK23-dev/cyclone-fleet`, branch `main` only.

Alpha.102 numbers stay. The fleet sits above the Command Center. Routes stay `/v1/fleet`. The tab label is Multi-phone. The fleet does not answer an approval.

## Integrated

SQLite missions, async dispatch, live events, queued phones, health report, preflight, pause, do-not-target, spend cap, retry, canary rollout, queued-behind, and owner approval display. Review fixes for rollout rows, empty missions, task binding, and CSV export are included.

## Checks on this tree

- `python -m pytest apps/device-gateway/tests` exited 0. No failed tests.
- `python -m pytest scripts/ci/tests` exited 0.
- Phone acceptance stays UNVERIFIED. Do not bump `release/version.toml`.
