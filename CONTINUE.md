# CONTINUE.md — hand-off

Read this, then `AGENTS.md`, then `docs/FLEET.md` and `docs/FLEET_REVIEW.md`.

## Baseline

Cyclone 5.0.0-alpha.102.dev1 with the fleet layer. Repo: `RTK23-dev/cyclone-fleet`, branch `main`.

The fleet lists an approval and opens the Command Center Approvals tab. It does not answer. That is intentional.

## Last severe pass

Fixed: a full change queue no longer drops the newest update; Glass resyncs every 5 seconds; open missions are found through `fleet_task`; the empty-mission sweep has a test that fails if the sweep is removed; health reports loop age and dropped changes.

Not run: phones, emulator, Glass browser, npm test, gateway restart under kill -9. Those stay UNVERIFIED.

## Next

Run `docs/FLEET_ACCEPTANCE.md` on two phones. Do not bump `release/version.toml`.
