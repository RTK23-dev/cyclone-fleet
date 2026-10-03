# Cyclone 4.4.1 visual integration

## Provenance

- Released execution baseline: `7676839c2d5836caa8b40b828644dfcd8490420d` (v4.4.0 / 100).
- Visual source: `b8070466e1287411ab7553d4df302f35855e6854`, [PR #103](https://github.com/premiumcentraal-boop/Cyclone/pull/103), branch `fix/4.3.8-ui-chaos`.
- Original visual base: `efec0488540665491a01837417fd1d82bb975cce` (v4.3.8).
- Target: Mobile 4.4.1 / 101. User explicitly requested this visual upgrade on top of 4.4 infrastructure and publication.
- Full infrastructure comparison: https://github.com/premiumcentraal-boop/Cyclone/blob/codex/artemis-upgrade-research/docs/artemis-upgrades/README.md

## Integration

The 28 visual commits were applied as one coherent port using a three-way squash merge onto released 4.4.0. All 20 changed visual/test files merged without conflict. Original commit history remains available in PR #103. No execution-foundation source file is replaced by an older 4.3.8 copy.

The patch includes compact live-only task glass, exclusive workspace handoff, keyboard/focus cleanup, queue hierarchy, checkpoint-label deduplication, resume presentation, available-action controls and setup/teaching polish. Release metadata and the CI branch filter are updated separately from that port.

## Validation

Existing visual state-machine tests include active minimize/re-expand, terminal teardown, gate continuation and returning control to the agent. The complete 4.4 suite must also pass to protect cancellation, consent, observation and recovery behavior.

Local Android execution was attempted but Gradle distribution download was unavailable. Exact-source GitHub CI is the required Android test/lint/assembly gate. No physical device is connected or claimed tested.

Publication must use the verified candidate and preserve the 4.4.0 certificate. Release assets retain source SHA, CI run ID, metadata, checksum and signing evidence.
