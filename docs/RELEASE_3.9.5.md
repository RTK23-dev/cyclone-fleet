# Cyclone 3.9.5 — standalone reliability release

Cyclone 3.9.5 is a **personal-development prerelease** built from the 3.9 Android-first line. It incorporates the 3.9.4 reliability work plus the standalone runtime audit from draft PR #55, then fixes the final API-34 lint blocker and increments the distributed Android identity to `3.9.5` / versionCode `59`.

## Reviewed source lineage

- 3.9.4 release base after completion-loop test repair: `40455ff338d9a676269cea4cb0399f218327912f`.
- Standalone runtime audit candidate: `05f7d6ea88572d4d0170e93778d94b0c758cbc24` (draft PR #55).
- PR #55 CI run `33970002078` executed **523 Android JVM tests with zero failures/errors**. The Android lane failed only at lint because `GuidedRecorderEngine.undo()` resolved `List.removeLast()` to an API-35 method while Cyclone supports API 34.
- 3.9.5 fixes that compatibility defect with `removeAt(lastIndex)` rather than suppressing the lint rule.

## Reliability changes carried into 3.9.5

- Rejected completion claims are bounded and re-observed locally before another provider turn.
- The rejected-DONE budget survives failed detours and resets only after verified progress.
- Stop/coroutine cancellation and the task deadline are checked after blocking observation/provider calls and before mutations, preventing a late plan from acting after cancellation.
- Explicit Stop cancels outstanding provider HTTP calls.
- Provider authentication, credit, rate-limit, timeout/network and unavailable failures become clear terminal results rather than malformed-plan retry loops.
- The whole provider request has a bounded call timeout.
- Simple independently verified web navigation can complete locally without another provider request.
- Website completion requires current browser evidence; the requested URL echoed by Cyclone or successful intent dispatch is not success evidence.
- The extra legacy completion keyword matcher was removed so the Goal Contract is the single completion authority.
- Failed verification/tool trace events are recorded as failures, and cancelled runs retain a `CANCELLED` terminal status.
- Core/Hermes websocket delivery is retired from active Android startup and Settings. Old Core-dependent routines fail explicitly as `LEGACY_INTEGRATION_RETIRED`.
- Teamwork Sniper promotion was removed from the active Cyclone product surface. The supported product pair is the Android APK plus the optional Windows companion.
- Android CI preserves unit-test and lint reports even when candidate assembly is blocked.

## Release gate

The 3.9.5 publisher is allowed to promote only the exact source SHA whose **Cyclone Mobile CI** push run succeeds. That CI lane runs repository/version/security guards, PC gateway/MCP contract tests, Windows bridge dry-run, Android unit tests, lint and unsigned release assembly. The publisher verifies source SHA, run ID, package `com.cyclone.mobile`, versionName `3.9.5`, versionCode `59`, checksum and signing state before signing.

The resulting APK is signed with the historical update-compatible **personal-development** signer so it can update the existing 3.9.3 personal-development install. The publisher verifies certificate continuity against the published `v3.9.3` APK and destroys recovered signing material after use. This signer is historically exposed and is **not production-secure**.

The tag/release is `v3.9.5`; an existing release under that tag is never silently overwritten.

## Known limits after launch

This prerelease does not claim that all autonomy problems are solved. The largest remaining engineering items are typed ordered contracts for compound goals, stronger authoritative browser/page-state evidence when the address bar is unavailable, a lifecycle-owned task controller that survives Activity recreation/process interruption, consolidation of overlapping progress budgets, and deletion of old unused UI generations.

Most importantly, **physical Pixel 8 acceptance is UNVERIFIED**. CI cannot prove Accessibility target selection, overlay behavior, real-provider performance or OEM behavior. A green release build is source/build evidence, not physical-device evidence.

## Device acceptance targets

Before calling the line stable, validate the exact signed APK repeatedly on the intended Pixel 8 for: opening `ad.nl`, Gmail/account-switcher inspection, cookie-prompt + scroll tasks, Settings navigation, stale-target recovery, invalid API key/credit/429/offline behavior, Stop during a provider request, GATE suspend/resume, Activity/tab changes, and optional PC companion connect/disconnect/reconnect. Capture the run diagnostic for failures and compare false-completion rate, provider requests, verified mutations, recovery cycles and total duration.
