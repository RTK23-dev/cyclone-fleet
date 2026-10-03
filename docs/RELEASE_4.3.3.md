# Cyclone Mobile 4.3.3 — Profiles + grounded task harness + unified task UI

Mobile 4.3.3 / versionCode 94. This release is cut directly from the exact combined 4.3.2 source `5efb94b9da897aefe7d50d3fd4dc91d000108c9b`; the release cut changes version/release metadata only and preserves all three functional upgrade streams together.

## Three integrated upgrades

### 1. Profiles hub and profile switching

- Preserves the Profiles rescue/hub work through `927d577e2e37ba17a3cf0bb4e61303e915fe64f5`.
- Profiles opens the full inventory, includes the owner profile, derives Current from Android's verified active user, and keeps task entry bound to the verified current process/profile.
- Profile switching reports access, preparation, dispatch and verification stages while retaining existing GATE, mutation and identity checks.
- Existing Add Profile, Manage apps/profile and profile bootstrap flows remain canonical.

### 2. Grounded phone-control and overlay backend

- Preserves the grounded harness source through `5922c9caf3650c6fc2b988019055b96098cf99be`, including the handoff-resume checkpoint `8734a66882542b023ded07931323c0c44fd0574a`.
- Phone actions keep execution and verification as separate facts. A dispatched gesture or changed page hash cannot alone complete a semantic operation.
- Back uses the canonical action contract end to end; missing or negative Android verification is not overwritten by gateway optimism.
- Actions carry current observation identity and exact session/display scope. Human takeover invalidates pre-handoff observations and resume requires fresh exact-session evidence before the agent continues.
- Foreground overlay execution publishes into the shared `WorkspaceTaskUi` lifecycle, and foreground/background notifications project that same authoritative task state rather than maintaining separate Working/Done claims.
- Compiled routines stop on rejected executor results and require fresh matching final-page evidence before progress is completed.

### 3. Ask Cyclone task UI / View Progress

- Preserves the frontend status language and task-card updates originally authored as `cce66105a8d5287179a625ba60cfc566534b4670` and `1e388e37f6482ba1e26e439e3d78eaadea96deab`, integrated on the harness line as `91d01f6d8a5dfaf196ae59eaf1473eb5bf0f57c1` and `9a5934e3cc8931f10ecc4adc6b18ced3ee699e92`.
- Ask Cyclone uses concise Working, Action Needed and Done presentation instead of raw execution telemetry.
- View Progress reads semantic step state and displays bounded one-line progress rather than tool coordinates/provider prose.
- Take Over and I'm Done are bound to backend interruption capabilities; resume is not inferred from UI phase alone.
- Autofill remains a future-facing capability and is unavailable unless a trusted backend credential capability explicitly enables it. No credential manager is added in this release.

## Release invariants

PhoneToolExecutor and existing GATE approvals remain the mutation authority. No second Windows mutation engine, Session Contract v2, replacement Capability Registry, GATE rebuild or Aurora architecture rewrite is introduced. The existing activation/window lifecycle remains in place while task status is projected through the shared task state.

## Validation and publication

Publication is explicitly authorized. The standard full-release workflow must first obtain a successful exact-source Mobile CI run, verify artifact identity/checksum/provenance, sign the exact CI APK with the historical update-compatible development key, verify certificate continuity with v4.3.2, and only then publish v4.3.3.

Physical Pixel 8 execution and UI acceptance remain UNVERIFIED for this cut. No claim of physical profile switching, accessibility timing, overlay rendering, or human-handoff behavior is made until device acceptance is performed.
