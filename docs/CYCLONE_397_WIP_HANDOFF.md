# Cyclone 3.9.7 development handoff

## BRANCH
`agent/397-v4-live-background-completion`

## BASE SHA
`830d73e6060c84b956e089681d6764f05000f325`

## STATUS
This is a development checkpoint, **not a verified V4 release**. The user requested that current work be pushed and the first CI build started, leaving final APK production to them. Version/signing metadata intentionally remains 3.9.6 / 60.

## PREVIOUS ASTRA WORK RECOVERY
No previous implementation checkout, dirty patch, or 3.9.7 branch was recoverable in this environment. The exact base was cloned without resetting or overwriting another checkout. The existing ExecutionSession, SessionObservationStore, LiveFrame and InMemoryLiveFrameBroker foundations were reused at their actual paths under runtime/session and ai/vision/live.

| Reported prior item | Recovery result | Current state |
|---|---|---|
| MediaProjection service | absent | Reconstructed consent activity and foreground service |
| In-memory live frames | foundation already committed; prior pixel buffer absent | Existing broker plus bounded pixel buffer |
| Screenshot/live integration | absent | Native screenshot and gateway capture prefer live frames |
| Post-action frame waiting | absent | Session/display/ID/time eligibility and bounded wait |
| Actual AI perception integration | absent | Image-capable normal planning consumes healthy live frames |
| Session foreground-fallthrough protection | absent | Strict identity parsing; legacy gateway paths reject background scope |
| Task pill | absent | New small task overlay |
| Expanded task card | absent | Expandable task controls |
| Pause/Stop | prior workspace implementation absent | Task pause/resume/cancel actions |
| Narrow Shizuku service | absent | New typed AIDL UserService and owned display lifecycle |
| Prior Kotlin/session fixes | absent | Current integration compiled locally |

## FINAL SHA
Use the commit containing this handoff; the assistant's final message and GitHub CI run identify the pushed SHA.

## EXECUTION SESSION INTEGRATION
CycloneAgentEnvironment and CyclonePcParityBridge accept a bound execution context. OpenRouterAdaptiveAgent propagates that context to observation, screenshot and canonical mutation calls. GatewayObservationStore now wraps the existing SessionObservationStore; element evidence includes session/display identity. Background actions require the current observation ID, execution generation and unchanged semantic fingerprint. Existing foreground calls retain their default scope. Public legacy gateway operations fail closed for unsupported background requests.

## LIVE VISION
MediaProjection captures the physical screen only, after Android-owned consent. Each background ImageReader is the output Surface of its owned virtual display. Both feed the existing LiveFrameBroker through LiveVisionRuntime. The operational buffer retains three frames per session; requested live evidence files are bounded to four. Image timestamps must fit the device monotonic clock freshness window. Post-action eligibility requires a newer frame ID and a capture timestamp strictly after mutation completion. The model receives at most one selected image per planning request, rather than the capture FPS.

Accessibility screenshot fallback remains for foreground capture. Background live-stream failure rejects capture/input rather than borrowing foreground evidence. **Display-specific Accessibility screenshot fallback is not implemented. Scrcpy is not yet bridged:** the existing decoder is WebCodecsH264Renderer in the PC companion; do not create a second decoder. A trusted, bounded frame ingress with device timestamp provenance is still needed. The current selector enforces freshness, but has no perceptual scene-signature/keyframe deduplication.

## BACKGROUND WORKSPACE
WorkspaceUserService exposes only create, launch, status, typed input, revoke/resume, handoff, close and destroy. No arbitrary shell command endpoint is exposed. Shell arguments are fixed command families and explicit nonzero display IDs. App launch uses a resolved component; am stack list output must prove unique task ownership. Apps already present on display 0 are rejected. Unknown OEM task output fails closed.

Android 15+ is required for this prototype's own-focus and do-not-steal-top-focus flags. Trusted display creation and destroy-content-on-removal flags are required, with no downgrade to a mirroring/foreground backend. Reflection/permissions/OEM support for these flags and Shizuku's shell context remain physically unverified.

Tap/long press use grounded control bounds; vertical scroll/swipe uses a scrollable target; Back and text use display-targeted shell events. **Text support is partial:** empty editable controls, printable ASCII, no percent escape, no replacement of nonempty fields. Unsupported input is rejected. Cleanup releases the display with destroy-content-on-removal; handoff first revokes ownership and moves the existing root task to display 0.

## DISPLAY-0 SAFETY
Request scope is checked before foreground cache lookup/observation. Outer identity cannot be dropped while normalizing params. Conflicting, blank, null, fractional and malformed identities are rejected. Background calls never enter the foreground dispatch branch. Every shell input command names a positive display ID; stale/dead sessions cannot substitute zero. The privileged process checks ownership and generation before input. Background Accessibility events are filtered from the global foreground state.

Deterministic tests cover invalid/foreign scopes, lease invalidation, dead backend/display decisions, nonzero command generation, stale/action-correlated frames and task parsing. These are structural tests, not proof of OEM event routing.

## INPUT OWNERSHIP + HANDOFF
Pause/handoff invalidates local leases and observation handles, then waits for remote revocation. The synchronized remote service drains prior input before returning HUMAN ownership. Old generations are rejected even after resume. Handoff never turns the old session into a foreground mutation session.

**Remaining:** after human review/payment the task is stopped; automatic re-observation of the preserved foreground task and post-human completion verification are not implemented. No payment completion is claimed from the handoff itself. Display/backend/accessibility loss and process death fail closed. Screen lock blocks input. Broader race/rotation/restore acceptance remains to be completed.

## PAYMENT SAFETY
Existing policy/GATE code remains authoritative. Background selected-control GATE classification stops for human review; shell privilege does not grant payment authority. No final purchase, biometric or 3DS automation was added. FLAG_SECURE is not bypassed. Explicit detection and consumer classification of black/secure live surfaces need further work.

## UX
Ask Cyclone has live-view consent and background-task entry points. The workspace picker chooses a target app explicitly. A foreground task service owns a compact expandable overlay and notification, with pause/resume/cancel/review actions. No fabricated task checklist or private chain of thought is displayed. Only the Markdown brief was attached; the mentioned Gemini reference images were unavailable, so visual comparison is unverified.

Remaining UX work includes animation/visual QA, app icons, verified step summaries, friendlier capability messages, model qualification preflight parity in the new background entry point, and foreground execution offered as an explicit alternative.

## TESTS
`cd apps/mobile && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` — **PASS**, BUILD SUCCESSFUL in 3m 32s, 93 actionable tasks. JDK 17, SDK 35; only environment proxy/trust-store settings were adjusted. All 565 unit tests passed with zero failures/errors/skips. Lint and debug APK assembly passed. The debug APK is not published.

`./gradlew :app:compileDebugKotlin -Pkotlin.incremental=false` — PASS. A stale incremental compiler overload error was resolved with a nonincremental compile, without changing source APIs to suppress it.

`python -m unittest discover -s scripts/ci/tests -q` — PASS, 39 tests.

`python scripts/ci/mobile_product_guard.py`, `python scripts/ci/repository_security_guard.py`, `python scripts/ci/release_versions.py --check`, and `git diff --check` — PASS.

`python -m unittest discover -s tools/codex-phone-mcp/tests -v` — PASS, 95 tests. The gateway pytest invocation produced incomplete output without a final result summary, so its result is **UNVERIFIED**, not a claimed pass. The first GitHub Mobile CI run executes the complete gateway and MCP suites again.

## PHYSICAL TESTING
`PHYSICAL DEVICE: UNVERIFIED`

`adb devices` returned no attached devices. No Instagram/Uber Eats test, payment, purchase, or account interaction was performed.

## REMAINING V4 GAPS
The current work is not the complete defining vertical slice. Finish Scrcpy ingestion, source/keyframe selection and diagnostics, background screenshot fallback, safe wider text support, secure-surface classification, post-human observation/completion, model qualification parity, lifecycle/rotation races and physical isolation acceptance. Audit display flag support and task parsing on a real Android 15+ Shizuku device before distribution.

## RELEASE SAFETY
The 3.9.6 release branch is not rewritten. Version identity is unchanged. Signing files and publisher workflows are untouched. No tag, GitHub Release, PR or signed APK publication is created. The existing Mobile CI workflow is enabled for this development branch so pushing starts the first unsigned candidate build; final APK work remains with the user.

## FILES CHANGED
- `.github/workflows/mobile-ci.yml`
- `apps/mobile/.kotlin/sessions/kotlin-compiler-3141766368498352294.salive`
- `apps/mobile/app/build.gradle.kts`
- `apps/mobile/app/src/main/AndroidManifest.xml`
- `apps/mobile/app/src/main/aidl/com/cyclone/mobile/runtime/background/IWorkspaceService.aidl`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/CycloneAccessibilityService.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/PhoneToolExecutor.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/PhoneToolProtocol.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/agent/integration/CyclonePcParityBridge.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/agent/tools/CycloneAgentEnvironment.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ai/vision/live/FrameSelection.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ai/vision/live/LiveVisionRuntime.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/capture/LiveCaptureConsentActivity.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/capture/LiveCaptureService.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/capture/PhoneScreenCapture.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/GatewayCaptureAdapter.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/GatewayObservation.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/GatewayRuntime.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/GatewayV33ActionAdapter.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/WorkspaceActivity.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/WorkspaceCommands.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/WorkspaceLifecycle.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/WorkspaceRuntime.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/WorkspaceTaskService.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/WorkspaceUserService.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/session/ExecutionRequestScope.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/session/ExecutionSession.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/session/ExecutionSessionStore.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/runtime/session/ExecutionRequestScopeTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/runtime/session/V4FoundationTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/runtime/session/WorkspaceSafetyTest.kt`
- `docs/CYCLONE_397_WIP_HANDOFF.md`

- `scripts/ci/tests/test_mobile_permission_architecture.py`
