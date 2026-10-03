# CONTINUE.md — hand-off

Read this, then `AGENTS.md`, then `docs/FLEET.md` and `docs/FLEET_REVIEW.md`.

## Baseline

Cyclone 5.0.0-alpha.102.dev1 with the fleet layer. Repo: `RTK23-dev/cyclone-fleet`, branch `main`.

The fleet lists an approval and opens the Command Center Approvals tab. It does not answer. That is intentional.

## Last severe pass

Fixed: a full change queue no longer drops the newest update; Glass resyncs every 5 seconds; open missions are found through `fleet_task`; the empty-mission sweep has a test that fails if the sweep is removed; health reports loop age and dropped changes.

`apps/device-gateway/tests/test_fleet_acceptance.py` covers two phones, one request id, an Owner Moment that is listed and not answered, a sleeping phone not shown as running, restart without a second task, broadcast confirmation, the three stops, canary hold, and an empty group. Those passed on the real Command Center. The phone RPC is a harness. Device rows stay UNVERIFIED.

## Next

An unknown task status is shown as UNKNOWN, not RUNNING. A command over 4000 characters is refused. Device acceptance stays UNVERIFIED. This is not a production release until `docs/FLEET_ACCEPTANCE.md` runs on two phones. Do not bump `release/version.toml`.
