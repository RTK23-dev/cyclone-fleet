# Cyclone 4.4.0 execution foundation sprint

Baseline: published v4.3.8, efec0488540665491a01837417fd1d82bb975cce (versionCode 99).
Target: Mobile 4.4.0 / 100. Branch: codex/cyclone-4.4-execution.
Reference dossier: https://github.com/premiumcentraal-boop/Cyclone/tree/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades

## Checkpoints

| Checkpoint | Original proposals | Implementation / tests | Commit |
|---|---|---|---|
| 1: regression and timing foundation | [09][p09], [13][p13] (deadline basis [01][p01]/[08][p08]) | ExecutionTiming and controlled task fixtures | 6b7be439de5f0f5ba30b6c5c0d513394f5a87c02 |
| 2: coherent observation | [15][p15] | Shared semantic projection; bracketed visual capture | fd29e10821b32d51fb131bb1efe51b2b9d3b6e9b |
| 3: persistent incidents / target revalidation | [02][p02], [03][p03] | Journal incidents and fresh exact target resolution | 97f4197cfaac67d569bf32f21e9935cdcbd90cc9 |
| 4: health / shared provider lifecycle | [07][p07], [08][p08] | Typed bounded observation recovery; shared HTTP deadlines/cancellation/pacing | cbfb0436da18ba91aeac220699a325c8e6bb43da |
| 5: interruption phases and budgets | [01][p01] | Explicit local outcomes; phase budgets and provider progress | 73fbd20b6147674b6d12e782bde161f3c45f358c |
| 6: release identity and workspace health | [07][p07] | 4.4.0 / 100; four fixed workspace error codes and redaction regression | bfef18b6cca0eee9cf6778e3e5ce8c4c4238e143 |

[p01]: https://github.com/premiumcentraal-boop/Cyclone/blob/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades/01_LATENCY_AND_INTERRUPTION_BUDGETS.md
[p02]: https://github.com/premiumcentraal-boop/Cyclone/blob/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades/02_PERSISTENT_RECOVERY_INCIDENTS.md
[p03]: https://github.com/premiumcentraal-boop/Cyclone/blob/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades/03_TARGET_REVALIDATION.md
[p07]: https://github.com/premiumcentraal-boop/Cyclone/blob/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades/07_PERCEPTION_BACKEND_HEALTH.md
[p08]: https://github.com/premiumcentraal-boop/Cyclone/blob/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades/08_PROVIDER_REQUEST_LIFECYCLE.md
[p09]: https://github.com/premiumcentraal-boop/Cyclone/blob/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades/09_TRACE_EVIDENCE_AND_REPLAY.md
[p13]: https://github.com/premiumcentraal-boop/Cyclone/blob/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades/13_REPRODUCIBLE_DEVICE_BENCHMARKS.md
[p15]: https://github.com/premiumcentraal-boop/Cyclone/blob/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades/15_COHERENT_OBSERVATION_SNAPSHOTS.md

## Fixture manifest

Suite v1; seed 0; JVM scripted Android/provider ports; no physical device, account, network model, tokens or human intervention. Locale en initially; display default-foreground/0. Runtime convergence is exercised through CycloneLocalAgent. Independent fixture state verifies consent removal; model DONE alone is insufficient. The original real login/authentication task is NOT claimed complete by this narrower consent-to-login-surface fixture.

Controlled reproduction: two independent semantic captures with a banner transition return different generations. This captures the source-level race class, not proof about the private original export. Existing CookieInterruptionPolicyTest proves one reject dispatch across observation churn. Existing overlay exclusion tests remain mandatory.

Measurements and checks will be recorded per checkpoint. Synthetic injected-clock durations are accounting tests, not phone performance claims. No production pixels, full trees, provider reasoning or raw secrets are added to telemetry.

## Remaining limitations

Final full tests/lint/build and release guards are recorded below when complete. Physical-device browser/login acceptance UNVERIFIED. No AndroidWorld score, provider performance or real-device latency claim. Cyclone One unchanged.

Checkpoint 1 validation: full :app:testDebugUnitTest passed (5m27s clean build); 7 new task/timing fixtures passed. Injected provider span: 75ms exactly; cancellation/deadline/403/stale fixtures dispatched zero actions; consent fixture dispatched once with zero prior provider calls. Overlay collection exclusion asserted. All attempted fixture cases retained.

Checkpoint 1 SHA: 6b7be439de5f0f5ba30b6c5c0d513394f5a87c02; 1,007 tests, zero failures/errors.

Checkpoint 2 (proposal 15): GatewayObservation's existing PageContext now travels with its Page Card as an in-process projection. Standalone observe/after-action/learning paths consume that projection and no longer call phone.observe followed by bridge.locate. Failed capture clears the current page rather than retaining a stale actionable view. Semantic capture records monotonic interval and geometry. Explicit visual escalation brackets pixels with two same-scope semantic observations and rejects changed package/activity/content/fingerprint/geometry or >1,500ms capture interval. This is a conservative skew check, not hardware atomicity. Normal planning no longer attaches unbracketed live task pixels; reference-only user attachments remain separate. No image failure fabricates a semantic tree.

Tests: production environment/bridge capture-queue race (one capture for both projections), rotation/display/content/skew rejection. Physical-device p95 and visual false-rejection rates remain unmeasured; 1,500ms is a conservative initial bound requiring device calibration.

Checkpoint 2 full suite: 1,009 tests passed. Checkpoint 1's late-added overlay assertion was not included in the first compiling test snapshot and failed on rerun; corrected to TaskSurfaceWindows.includeSibling, the actual service boundary. This correction is included in checkpoint 2. No runtime overlay policy was weakened.

Checkpoint 2 SHA: fd29e10821b32d51fb131bb1efe51b2b9d3b6e9b.

Checkpoint 3 (02/03): task journal schema 2 retains one incident, originating task/session/display/package/generation, typed intended effect, bounded internal strategy codes, and resolution. Old schema 1 still reads. The compact incident is outside history truncation. Unrelated accepted actions do not close it. Consent closure requires a fresh same-package/scope useful tree, a login control and no remaining consent text/control evidence. Unsupported goal effects remain open until independently verified task completion or a separately marked terminal boundary. Terminal is not verified resolution. No action arguments/typed values or coordinate identities are added to incidents.

Android production environment revalidates element intent against one fresh capture immediately before PhoneToolExecutor. It preserves scope/workspace generation, requires exact semantic identity, unique enabled visible nonoverlapping bounds, and replaces the observation/element ID. Old-rectangle replacement, missing/ambiguous/occluded controls and scope changes reject with typed reasons. Graph/legacy resolution no longer chooses the first fuzzy match. Existing executor approval/typing/readiness checks still run after revalidation.

Tests: accepted-but-unverified recovery retains one incident and original goal; incident codec redacts non-code strategies; wrong effect/display cannot close it; moved control dispatch uses new ID; replacement/ambiguity/scope mismatch produce zero executor calls. Limitations: exact identity matching is conservative; unknown effects are not auto-closed; no process/device restart experiment or real keyboard/browser timing measurement yet.

Checkpoint 3 exact working-source rerun: 1,014 tests passed, zero failures/errors; version/product/security guards passed. Consent proof uses the fresh capture boundary rather than comparing process-local generation counters after restart.

Checkpoint 3 SHA: 97f4197cfaac67d569bf32f21e9935cdcbd90cc9. Matching push CI 34752640792 passed.

Checkpoint 4 (07/08): typed semantic observation health distinguishes healthy/empty valid, timeout, permission/service unavailable, disconnected, scope mismatch and unavailable. Same-scope recovery has a 500ms cooldown and two-attempt bound; permanent boundaries stop without provider calls. Resuming a handoff resets health; no alternate display/backend is introduced. Health is projected into task progress, trace and provider context. Settings-wide diagnostics UI and alternate backend switching remain outside this foundational slice.

Phone tasks, text chat and model qualification share ProviderRequests: exact request passthrough, individual cancellation, 30-second maximum remaining deadline, at most one classified transient retry, 250–5,000ms bounded pacing, and one active/half-open request per account/model/endpoint/purpose. 401/402/403/400 and embedded 403 do not retry. Key replacement cancels/invalidate old-account requests and pacing. Stop does not cancel a global HTTP dispatcher. Coroutine and late-response fixtures verify isolation and discarded results. Offline interceptor fixtures do not establish real provider latency or physical phone response time.

Tests include permanent one-attempt errors, 503→success, repeated 429 bounded to two attempts, cancellation during backoff, response past deadline, independent concurrent request survival, coroutine cancellation, one half-open probe, key replacement, and no raw key in request context. Existing chat source assertion now checks the shared cancellation adapter; behavioral cancellation is covered by transport tests.

Checkpoint 4 final suite: 1,027 tests passed, zero failures/errors. Transient observation fixture recovered after cooldown with exactly two captures in the same task; dead-service fixture made one capture and zero provider calls.

Checkpoint 4 push CI: https://github.com/premiumcentraal-boop/Cyclone/actions/runs/34753146814 (passed).

Checkpoint 5 (01): extends the existing allowlisted cookie policy with explicit handled/not-applicable/ambiguous/user-choice/unresolved outcomes and English/Dutch reject labels. A unique scoped reject is still selected before route recall or any provider request. Ambiguous, disabled or already-attempted consent controls suspend with a specific unresolved incident; authentication remains human-owned and the original goal stays active. The task fixture now explicitly suspends at login instead of treating consent removal as login completion.

Monotonic phase checks reject an over-budget returned plan before dispatch; provider calls share the remaining decision/task deadline and actively cancel HTTP. Local policy, recall, prompt, observation, plan, dispatch and verification have budgets. Grounding/settling remain included in dispatch timing; their existing executor freshness/settle bounds are retained. Synchronous Android callbacks cannot be forcibly interrupted by the caller: their budget is checked on return, whereas provider deadlines are actively enforced. These bounds are initial engineering limits, not measured device latency targets. Provider progress reports elapsed waiting/backoff/deadline/Stop state, with payload-free causal request IDs. Historical page projections no longer retain full legacy pages.

Added fixtures cover Dutch consent, auth/permission exclusion, exact ambiguous/missing/repeated-effect reasons, non-dialog cookie text, a 35,001ms injected plan rejected with zero actions, and Stop precedence over phase expiry. Physical latency percentiles and a live browser/device run remain unverified.

Checkpoint 5 suite: 1,032 tests passed; 72 release-guard tests passed; metadata, product and repository security guards passed. Final release compilation/lint/assembly follows the version checkpoint.

Checkpoint 5 exact push CI passed: https://github.com/premiumcentraal-boop/Cyclone/actions/runs/34787264959.

Release checkpoint (07 follow-up): normalizes the workspace runtime's fixed legacy exception codes for disconnected backend, expired session, missing Accessibility service and target not visible. Raw message suffixes are discarded. The added regression checks precise terminal health and zero actions for all four boundaries. Version identity is 4.4.0 / 100; publication remains gated by exact release-branch CI and the existing provenance/signing workflow.

Local gateway contracts: 199 tests passed; phone MCP: 158 tests passed. ADB reported no attached device; physical acceptance remains UNVERIFIED.

Final release working-source Mobile suite: 1,033 tests passed, zero failures/errors (37s). Metadata, product and security guards passed. The initial 4.4 local tests/lint/release assembly passed (11m51s); the final workspace-code follow-up was then verified by the full Mobile suite. Exact-final-source lint/assembly and signing provenance are additionally required in the release branch CI; links and immutable source SHA are recorded by the release assets and PR #106.

## Sprint 2 — 4.4.4 coherent observations

Baseline: published 4.4.3 / 103, `65fc673ba5d328c57dd8502fb14d1d51dec7396d`; [Mobile CI](https://github.com/premiumcentraal-boop/Cyclone/actions/runs/34827911335) and [release](https://github.com/premiumcentraal-boop/Cyclone/actions/runs/34827911071) passed. The unrelated PC CI on that baseline failed; this sprint does not change Cyclone One. Sprint 1's `ExecutionTiming`, scoped `SessionObservationEnvelope`, recovery journal, provider lifecycle and race fixtures are present. The obvious legacy/current double capture was already removed in checkpoint 2; this sprint completes its provenance and projection contract instead of reintroducing another observer.

Read the complete comparison and [proposal 15][p15] at the pinned dossier revision. Artemis is architectural evidence; no runtime source is copied.

Checkpoint A introduces pure `ObservationProjections` and additive `ObservationIdentity` in shadow comparison. The current IDs remain authoritative during this checkpoint. Comparison uses the same input capture, reports only fixed difference codes, and never invokes a capture port. The session envelope's source generation is carried into the candidate legacy, card, executable control evidence, prompt and learning projections. Unknown profile/window/geometry/timing fields are explicitly unavailable, never filled from a prior page. Page provenance is transient, separate from learned-page identity.

Fixture seed 0, scripted JVM source: one semantic capture serves five repeated projection passes; all projections share source generation; shadow detects scope/page races; unavailable fields remain null; projection mutation does not overwrite captured control evidence. Exact test results and checkpoint SHA are recorded by the next checkpoint before switching authority. Physical device acceptance remains UNVERIFIED.

Checkpoint A validation: 1,054 Mobile JVM tests passed, zero failures/errors (4m49s clean compilation after a transient Windows Gradle cache move error). Production bridge race fixtures report matching shadow projections; five pure projection passes invoke the scripted semantic source once. Learned-control/old-preview contamination is reproduced and the fresh projection excludes it.

Checkpoint A SHA: 1b448c504aa08ef4db8fbcf0f3892dd29034d07d. Draft PR #112 tracks this sprint.

Checkpoint B switches the standalone adapter to authoritative pure projections. Legacy PageContext, Page Card, executable evidence, prompt and learning snapshots consume the source envelope generation. The gateway projects only current captured controls; learned history remains separate. The retained shadow mode compares projections without recapture. The authority fixture uses one capture, five prompt passes and source generation 82 throughout. Full Mobile JVM suite: 1,055 passed, zero failures/errors (43s). Capture boundary and visual timing work remains pending; this checkpoint is not release acceptance.

Checkpoint B SHA: 34472359da3d8c0660141d2f8129f9f258d70fa1. GitHub runs 34901623755 / 34901626751 passed guards and gateway contracts, then failed before compilation because the pinned Android setup action requested the removed `tools` package. The following checkpoint sets its supported `packages` input to `platform-tools` in build and signing workflows; no checks are disabled.

Checkpoint C (proposal 15) adds one semantic capture boundary with window, rotation, geometry, scope/profile and Accessibility event-revision samples. Incompatible captures clear current evidence before publication. Visual escalation now requests one semantic/image bundle instead of two extra semantic traversals around a screenshot. Screenshot timing uses the platform's uptime clock; stale, delayed, missing or mismatched pixels are explicitly unavailable. Cancellation cannot publish a successful bundle. The legacy/current/prompt/learning projections and image share source identity and generation. PhoneToolExecutor, approval gates and current-target revalidation remain authoritative.

Fixture results: one semantic traversal per ordinary observation or visual bundle; visual bundle uses one image call and three lightweight metadata samples. Five repeated pure projection passes add zero captures. Eight semantic race variants (page revision, window, 180-degree rotation, display, profile, workspace generation, geometry, session) reject before pixels/publication. Screenshot-stage rotation invalidates the bundle; five stale/delayed/scope/geometry/missing-time image cases preserve valid semantics but discard pixels. Overlay animation does not alter the filtered task window signature. Failed capture clears the bridge page and dispatches zero actions. Timings are injected (tree 10�11ms, image 11�13ms), not device measurements.

The cumulative consumer contract is [OBSERVATION_CONTRACT.md](OBSERVATION_CONTRACT.md). Android callbacks/events are not an atomic physical snapshot; queued or missing events and the 1,500ms visual bound still require device calibration. ADB reports no attached devices. Physical acceptance remains UNVERIFIED.

Release collision discovered during final metadata inspection: v4.4.4 / 104 was independently published for the Liquid Glass redesign at b539608fc919bad7585f49c7a5ff9ce62aff67a1 (Mobile CI 34866683775 and release 34866683357 passed). Preserve its immutable APK and UI work. This sprint will integrate that verified release and use the next available Mobile version, 4.4.5 / 105. The working branch retains its original 4.4.4 sprint name for checkpoint continuity.

Checkpoint C final working-source suite: 1,066 JVM tests passed, zero failures/errors (36s), including cancellation and current-workspace lease validation. 78 repository guard tests, metadata/product/security guards passed. Local boundary-source lint and release assembly passed (10m23s); final scope-binding follow-ups were then covered by the full JVM rerun. Exact combined-release-source CI remains required after the verified 4.4.4 merge.

Checkpoint C SHA: 7ab32e8f7cf7d308e5ab6af6f9d0482a26279121.

Combined release candidate: merge the exact verified v4.4.4 release into the observation branch, preserving all 25 files of its published UI/release changes. No conflicts. Mobile advances to 4.4.5 / 105; Cyclone One remains 1.5.5. Release notes are docs/RELEASE_4.4.5.md. The release workflow must build/verify this exact combined source and compare its signer against the published v4.4.4 APK before publication.

Combined candidate local validation: 1,066 JVM tests passed (53s), 78 guard tests and metadata/product/security guards passed. Checkpoint C exact push CI 34903268783 also passed. An external audit identified buffered-frame freshness versus request-boundary mismatch, premature visual-budget consumption, and incomplete shadow comparison. These remain release blockers pending dedicated fixes; the combined integration checkpoint is not publication approval.

Combined integration SHA: 4627aeeb367a3cf94d2190e6607c80f8ce8ddbd7 (merge parents include the exact published 4.4.4 SHA). This resolves the audit's pushed-integration gap.

External-review follow-up: preserve strict image freshness but pass a request-time lower bound through PhoneToolExecutor to LiveVisionRuntime. Its shared production FrameSelection wait loop preserves post-action/session/display restrictions and waits up to 800ms for a post-request frame. A healthy 500ms-old buffered frame now causes a bounded wait, not immediate selection followed by rejection. Separate two capture attempts (500ms pacing) from the single usable-image budget; transient failure no longer records screenshot evidence or prevents the remaining attempt. Selecting a vision recovery level also no longer records pixels as inspected. Shadow comparison now includes complete identity/actionability and 15 independently checked executable evidence fields.

Review fixtures use production FrameSelection and SemanticCaptureBoundary together: initial buffered frame at 500ms, request/tree at 1,000�1,010ms, fresh frame at 1,035ms; one wait and one tree produce accepted evidence. A stale-only stream ends at its 800ms deadline; source replacement yields no frame. Bridge fixtures prove unavailable pixels then success uses two captures, one 500ms pause and one usable image; two failures stop without claiming screenshot evidence. These are injected-clock/control-flow results, not real-device measurements.

Physical acceptance remains outstanding: on a real device, demonstrate consent rejection followed by resumed progress, overlay exclusion on each supported visual path, transient screenshot recovery, rotation/display/profile switching and the observed rejection rate of the 1,500ms bound. No device is attached and no hardware acceptance claim is made.

External-review follow-up validation: 1,074 Mobile JVM tests passed, zero failures/errors (2m43s), and all 78 repository guard tests passed. The complete combined source and fixes now require exact-head CI before publication.

External-review fix SHA: e02f7fa2383b152b930681c34211286aa23757bc. It is based on the combined 4.4.5 / 105 integration and preserves the published 4.4.4 ancestor. The final release seal changes only this ledger, release notes and a metadata comment. Its immutable source SHA and exact CI run are recorded by release provenance and PR #112. The unrelated PC workflow still checks the old Mobile 4.4.2 identity and failed on the prior baseline and combined candidate; no Cyclone One runtime or publication is included.
