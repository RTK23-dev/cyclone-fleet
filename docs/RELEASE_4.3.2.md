# Cyclone Mobile 4.3.2 — Profiles hub

Mobile 4.3.2 / versionCode 93. Based on published v4.3.1 (4dbd32c2), preserving its Ask Cyclone and PC Gateway refresh. Integrates the profile rescue follow-up through 927d577e without replacing those changes.

## Changes

- Profiles opens the full inventory, includes the owner even without saved workspaces, and sorts the verified current profile first.
- Current is derived from Android's active-user query, not the selected profile or application process alone. Unknown identity is reported honestly.
- Owner-profile foreground work appears in Active; the existing queue exposes Steer and Stop.
- New task opens the existing AI composer only for the verified current process/profile. Open an alternate profile first to start work there; no silent cross-profile retargeting.
- Profile switching reports access, preparation, dispatch and verification stages and ignores duplicate taps. Existing ownership, GATE and mutation checks remain authoritative.
- Destination settings import clears stale portable defaults and credentials when absent at source. Readiness is committed only after import and permission verification.
- Retains Add Profile and Manage apps/profile through the existing provisioning runtime.

## Validation and publication

The earlier profile CI compiled but failed two legacy source assertions for the old Active default and hard-coded owner user 0. Those expectations are updated here, retaining the identity/action checks. Added deterministic profile inventory/current-user/task-entry policy tests. The exact release source must pass Android unit tests, lint and APK assembly before the standard publication workflow signs and verifies update continuity with v4.3.1.

Publication explicitly authorized by the owner. Physical Pixel and UI acceptance remain UNVERIFIED. No USB, ADB, live phone taps or installed-phone verification were performed. The reported physical switch stall has not been reproduced; improved status is diagnostic, not proof of a device fix. Root/Magisk and permission bootstrap behavior still requires device testing. This release does not promise unattended cross-profile task dispatch or transfer of arbitrary apps' private data.

## Device checklist

- Owner appears with Current on the owner profile; alternate profile selection does not change that badge.
- Open Profile B: inspect preparation/switch status, verify Android changes user, and use Return to Profile A.
- New task in the current profile reaches Ask Cyclone; Open Chrome uses that profile.
- Active and queued work retain exact identities; Stop and Steer use the existing runtime.
- Check settings/permission continuity and ensure a failed import is not marked ready.

## Preserved grounded task harness update

# Cyclone Mobile 4.3.2

Version 4.3.2, Android versionCode 93. Built from the preserved 4.3.1 source with grounded action verification, persistent task handoff, and one shared task status.

- Phone actions retain separate execution and verification outcomes. A dispatched gesture or changed page hash cannot alone complete a semantic operation.
- Back now uses the same canonical action contract end to end. Gateway handling no longer overrides missing or negative Android verification.
- Actions carry current observation identity and exact session/display scope. Resume invalidates pre-handoff observations and reconciles the current page before continuing.
- Ask Cyclone, View Progress, and task notifications share Working, Action Needed, Done, and terminal Failed/Stopped state. Progress labels omit raw tool parameters and provider prose.
- Take Over and I'm Done use explicit backend capabilities and existing command routing. Autofill remains unavailable; no credential manager was added.
- Compiled routines stop when the executor rejects a step and verify their final page before publishing progress.
- The two frontend task-card commits are integrated, with their provisional resume eligibility replaced by backend capabilities.

PhoneToolExecutor, existing GATE approvals, execution planes, and Aurora activation/window lifecycle remain in place. Signing material is unchanged; the existing release workflow verifies update-signature continuity with 4.3.1.

Validation: Python gateway, MCP, source guards, and Android CI results are recorded in [the engineering handoff](HARNESS_4.3.2_HANDOFF.md). Physical Pixel 8 execution and UI acceptance have not been tested for this release. Device-specific accessibility behavior, handoff timing, profile switching, and overlay rendering still require device acceptance testing.


Combined release preserves harness source 5922c9ca and profile hub source 927d577e. Both are included in this release; exact combined-source CI is required.
