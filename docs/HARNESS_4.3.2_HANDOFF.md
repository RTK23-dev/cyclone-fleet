# Cyclone 4.3.2 engineering handoff

Backend branch: `agent/432-grounded-harness-backend`.
Preserved base: `4dbd32c2bbe95361bb75b69d9bcb70e4c7238de0` (4.3.1 /92).
Candidate: 4.3.2 /93. The release artifact's `source-sha.txt` identifies its exact final source. Release publication must be confirmed from the successful Full Release workflow, not inferred from this document.

## Milestones

- `02d1bd888ca52f97f02da3392f5451b40f566a76` — chore(mobile): prepare 4.3.2 grounded harness from preserved 4.3.1
- `a3ccef429257505eb3bdf8e304778199b863755f` — feat(mobile): add evidence-bound semantic progress and interruption capabilities
- `91d33f3fea02232947317d3b630f7fb94dfad7ef` — fix(harness): reject stale targets and separate dispatched actions from verified transitions
- `8734a66882542b023ded07931323c0c44fd0574a` — fix(mobile): invalidate handoff assumptions and reconcile exact session before resume
- `e8d79d802a4014e102c405cb8b2d67533e944ed3` — feat(mobile): project foreground and background notifications from authoritative task state
- `91d01f6d8a5dfaf196ae59eaf1473eb5bf0f57c1` — feat(mobile): simplify task status language
- `9a5934e3cc8931f10ecc4adc6b18ced3ee699e92` — feat(mobile): add working done and action-needed task card
- `a2e40b2a4826ba51ef59fd1b0dee196d7d51d751` — fix(mobile): bind task controls to interruption capabilities and align frontend tests
- `1d82201300aeae163f0c4fd6882c751410d8ed37` — fix(mobile): verify compiled routine outcomes and classify execution scope failures
- `7a4e057690a4f6129418b83fee047a2f07b8c941` — fix(mobile): unify legacy takeover commands and document 4.3.2 validation
- `69730f9fd95096346598bc0cd5fad8317d75e579` — test(mobile): align visual contract guard with integrated task card
- `645561322327a926526da3e045ae0dce515c90bc` — test(mobile): distinguish fresh unchanged pages from reused verification evidence

The metadata authorization commit follows these milestones; its exact SHA is embedded in published provenance.

## Actual reliability seams

The gateway action router treated Android OBSERVED/ok as verification and used page/fingerprint changes as a fallback when authoritative verification was absent. Desktop agent handling could also substitute an already-present goal label for negative action verification. These shortcuts are removed. `phone.back` remains registered and transmitted unchanged; regression fixtures exercise accepted execution with absent/negative/OBSERVED verification and a genuinely verified success.

The mobile adapter now emits negative semantic verification for OBSERVED, checks before/after execution identity and freshness, and only consumes expectations actually evaluated by the executor. PhoneToolExecutor checks supplied observation identity against current state, scopes duplicate-result caching to execution identity/controller epoch, and does not replace failed semantic selection with stale coordinates. Fast Path settlement remains local.

SemanticTaskStep extends WorkspaceTaskUi. Only matching-session, matching-display, matching-workspace/generation, current-control-revision evidence can complete a step. Unverified execution marks a step failed; interruption invalidates active evidence. Labels are deterministic and exclude action parameters and provider prose. Unknown task descriptions are not echoed into status.

Existing foreground agent and background service publish into WorkspaceTasks. Notification actions route through WorkspaceTaskService. Existing Aurora state still manages windows, activation, GATE, and agent lifecycle; task presentation takes precedence when authoritative task state exists. Compiled routines retain their existing executor and replay implementation and publish a checked route outcome.

Human handoff retains task identity and transfers existing input authority. Resume joins the interrupted execution, rejects stale command scope, invalidates cached observations and action history, and observes before further mutation. Repeat foreground resume requests are coalesced. Review/GATE never gains resume eligibility from phase alone. Autofill capability remains false.

## Frontend integration

Integrated in order:

1. `cce66105a8d5287179a625ba60cfc566534b4670` as `91d01f6d8a5dfaf196ae59eaf1473eb5bf0f57c1`.
2. `1e388e37f6482ba1e26e439e3d78eaadea96deab` as `9a5934e3cc8931f10ecc4adc6b18ced3ee699e92`.

Compatibility changes bind Take Over/I'm Done/Autofill to backend capabilities, align Failed with terminal state, and show semantic step states in View Progress. Obsolete swipe-layout tests were updated for the supplied frontend, without changing queue or activation architecture.

## Validation actually performed

- `python -m pytest apps/device-gateway/tests -o addopts='' -q`: **198 passed, 1 skipped**, one dependency deprecation warning, 11.44 seconds.
- `python -m unittest discover -s tools/codex-phone-mcp/tests -q`: **158 run, OK, 1 skipped**, 1.088 seconds.
- `python -m unittest discover -s scripts/ci/tests -q`: **72 run, OK**.
- `python scripts/ci/release_versions.py --check`: passed; mobile 4.3.2 /93 agrees with metadata.
- `python scripts/ci/mobile_product_guard.py`: passed.
- `git diff --check`: passed at checkpoints.
- Local `./gradlew :app:testDebugUnitTest`: attempted, but Gradle distribution download failed due local Java network access. Android compilation, unit tests, lint, and APK assembly are therefore validated by repository CI instead.
- Initial CI identified outdated Python verification fixtures and a missing JUnit import. Those were fixed in subsequent checkpoints. A later full Android run completed 933 tests with one outdated reused-observation fixture failing; the fixture was corrected and a separate reused-evidence regression added. The release workflow requires the final exact-source run to pass all unit tests, lint, assembly, provenance checks, and signing continuity before publication. Final run links and outcomes accompany the release handoff.

## Device validation and remaining limits

No physical phone or emulator acceptance run was performed. Pixel 8 execution and UI acceptance remain **UNVERIFIED**. Unit fixtures do not prove platform accessibility settlement, actual account sign-in, root/profile handoff timing, or overlay geometry on device. This release adds no credential broker, account manager, or password storage. Autofill remains unavailable. No new generic shell/root surface or Windows mutation engine was introduced.

## Modified paths

- `.github/workflows/mobile-ci.yml`
- `apps/device-gateway/cyclone_device_gateway/actions/router.py`
- `apps/device-gateway/cyclone_device_gateway/desktop_runtime/agent.py`
- `apps/device-gateway/tests/test_canonical_action_envelope.py`
- `apps/device-gateway/tests/test_element_id_resolution.py`
- `apps/device-gateway/tests/test_gateway.py`
- `apps/device-gateway/tests/test_v35_android_verification_authority.py`
- `apps/mobile/app/build.gradle.kts`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/PhoneToolExecutor.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/agent/CycloneLocalAgent.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/agent/contract/CycloneAgentContract.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/agent/contract/HarnessFailureCopy.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/agent/integration/CyclonePcParityBridge.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/agent/tools/CycloneAgentEnvironment.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/fastpath/MutationGrounding.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/GatewayV33ActionAdapter.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/TaskConsumerCopy.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/TaskHarnessState.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/TaskNotificationProjection.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/WorkspaceProgressActivity.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/WorkspaceTaskService.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/WorkspaceTaskState.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/skills/CompiledSkillReplay.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/AgentTaskNotificationRuntime.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChromeRuntime.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneAskTaskPanel.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/TaskGlassPresentation.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/agent/CycloneLocalAgentTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/agent/tools/CycloneAgentEnvironmentTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/fastpath/MutationGroundingTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/runtime/background/TaskGlassObservabilityTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/runtime/background/TaskHarnessStateTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/runtime/background/TaskNotificationProjectionTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/skills/CompiledSkillReplayTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/ui/v32/Cyclone422TaskGlassTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/ui/v32/CycloneTaskPresentationTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/ui/v32/CycloneVisual42ContractTest.kt`
- `docs/HARNESS_4.3.2_HANDOFF.md`
- `docs/RELEASE_4.3.2.md`
- `release/version.toml`
