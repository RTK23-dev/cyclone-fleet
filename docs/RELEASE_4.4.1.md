# Cyclone Mobile 4.4.1 — calm task surfaces

Mobile **4.4.1 / versionCode 101** layers the visual upgrade from [PR #103](https://github.com/premiumcentraal-boop/Cyclone/pull/103) onto released **4.4.0**, rather than rebuilding from 4.3.8. Cyclone One is unchanged.

## Changes

- One primary task/result surface: the queued-request panel no longer stacks beneath the current task.
- Background work collapses to a compact tappable status ribbon. Completed, failed and stopped tasks release the persistent background glass; results remain available through the existing result/history surfaces.
- Swipe-down, automatic collapse and continuation after human handoff use the same compact presentation. Minimizing does not stop the task or grant input authority.
- Full-screen workspace selection suppresses the overlay, decorative border and share pill. The installed-app menu is capped at 320dp.
- Task and result transitions clear stale editor focus and dismiss the keyboard. Duplicate fallback checkpoint labels are suppressed.
- Action-needed cards show only currently available controls; the inactive Autofill/Soon placeholder is removed.
- Background-task setup, root quick setup and Follow Me teaching use clearer grouping, spacing and existing theme colors.

## Preserved 4.4 infrastructure

The release retains coherent observations, persistent recovery incidents, fresh target revalidation, typed observation health, shared provider deadlines/cancellation, cookie interruption handling, and 4.3.8 OpenRouter settings. The visual port changes presentation files and associated regression tests; it does not replace the execution engine. Human ownership, authentication/permission gates, display isolation and diagnostic redaction remain mandatory.

## Validation and limits

The release pipeline must pass the complete Mobile JVM suite, lint, unsigned APK assembly, gateway/MCP checks and repository guards for the exact published commit. It then verifies APK identity, checksum, source provenance and signing continuity with 4.4.0 before publication. Published source/run details are appended by that pipeline.

Physical-device visual acceptance is **UNVERIFIED**. Unit tests cover presentation state and source contracts; they do not prove screenshot fidelity, keyboard animation smoothness or behavior on every screen size. The local environment could not download Gradle, so Android validation is performed in GitHub CI rather than claimed locally.

Port provenance and integration checks: [4.4.1 integration record](https://github.com/premiumcentraal-boop/Cyclone/blob/release/cyclone-mobile-v4.4.1/docs/SPRINT_4.4.1_VISUAL.md).
