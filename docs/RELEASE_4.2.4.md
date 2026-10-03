# Cyclone Mobile 4.2.4 — Live Phone + Ask/Profile repairs

Mobile 4.2.4 / versionCode 85 combines both previously separate updates. It supersedes the published Mobile 4.2.3 / 84 without changing that release.

## Preserved source updates

- Published Live Phone baseline: `a9814e4f75b0628e651c858f50ee607d1b4683d2` (v4.2.3), containing the complete `ce719416` Live Phone sprint.
- Ask/Profile/UI repair checkpoint: `c964fe0c72535e4563d4aef328b708d002324f16`. All 17 changed Android source/test files from that repair are retained byte-for-byte. Only the Android release identity is advanced.
- PC, gateway, MCP and PC packaging source remains byte-identical to the Live Phone sprint. Component versions remain One 1.1.2 and gateway/MCP 4.1.0; the UI branch's older PC metadata is not imported.
- Both historical notes are retained: `RELEASE_4.2.3.md` describes the published Live Phone release; `UI_QUEUE_4.2.3_CHECKPOINT.md` preserves the previously unpublished repair handoff.

## Included

Live Phone: typed adapter, fresh screenshots and UI observations, verified actions, private Windows IPC, restart recovery, separate One/Mobile controls, and native Codex MCP preservation. Android Live Phone remains constrained to the human display; background and Layer 2 identities remain separate. PhoneToolExecutor and GATE remain authoritative.

Ask/UI repairs: real runner ownership instead of composer ANALYSIS state for task admission; Steer retries; explicit blocked-request reasons; Profile A inventory without app registrations; saved-profile recovery; compact profile selector; shared model/intelligence controls; removed redundant task heading; improved AI spacing and Home shadows.

## Installation and limits

This is the combined Android update. Live Phone additionally requires the Live Phone sprint Windows adapter; the previously published One 1.1.2 installer does not contain it. The unchanged Windows sprint candidate is available from run 34299725847. This release does not claim a new Windows installer release.

Secondary-profile isolated Ask execution remains unsupported and explicitly blocked, as documented in the repair checkpoint. This merge preserves both updates; it does not claim to add that missing execution route.

The user explicitly authorized publication on September 9, 2026, overriding the earlier physical-evidence prerequisite while retaining UNVERIFIED physical acceptance. The publication workflow reuses the exact green Mobile CI artifact and the existing update-compatible development signing workflow. Signature equality with published 4.2.3, version identity, APK checksum and source provenance are required before publishing. This development signer is update-compatible, not production-secure.

Physical phone / Pixel / USB / ADB acceptance remains UNVERIFIED. No physical testing was performed. No release or build success is inferred from committing source; CI and publication results are recorded by their GitHub runs and release sidecars.
