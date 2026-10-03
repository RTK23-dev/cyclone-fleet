# Android + PC companion reliability review

Reviewed 2026-09-05. Source baseline: `40455ff338d9a676269cea4cb0399f218327912f`
on `release/cyclone-mobile-v3.9.4` (Android 3.9.4 / versionCode 58).

## Assessment

Cyclone already has the right broad shape for two deliverables: an Android APK and an optional
Windows companion. It is not yet a demonstrated reliable release. The immediate CI failure is
small, but task lifetime, semantic completion and physical acceptance are larger remaining gates.

This review covers the repository layout, current Android entry points and agent execution,
completion/recovery/diagnostics, PC gateway and companion composition, MCP adapters, version
metadata, and build/release workflows. It is a source and CI review, not a claim that every source
line or every device behavior has been audited. No physical device or exported failing task trace
was available. The user's observed looping is consistent with the defects below; source review
alone cannot identify which defect caused a particular installed run.

## Build evidence

| Evidence | Result | Meaning |
| --- | --- | --- |
| [v58 CI run 33968371402](https://github.com/premiumcentraal-boop/Cyclone/actions/runs/33968371402), SHA `40455ff` | Failed: 514 Android tests, 2 failures | `AgentRunDiagnosticV39Test` still expected diagnostic schema `/2` and counted Free Mode entry as recovery. Implementation uses `/3` and a separate Free Mode metric. |
| [Earlier v58 CI run 33967915057](https://github.com/premiumcentraal-boop/Cyclone/actions/runs/33967915057) | Failed | A version bump did not establish a passing candidate. |
| [3.9.3 fast publish 33961000096](https://github.com/premiumcentraal-boop/Cyclone/actions/runs/33961000096) | Published | Its steps built/signed/published without the normal unit-test and lint gate. Publication is not proof of runtime reliability. |
| [PC CI 33903595175](https://github.com/premiumcentraal-boop/Cyclone/actions/runs/33903595175), SHA `b073d6d` | Passed | Useful companion baseline; not proof of physical compatibility with this Android candidate. |
| `main` at review start | `9957eea`, Android 3.5.1 / code 37 | The default branch is far behind the release branch. Starting new work from `main` resurrects retired architecture. |

The latest published GitHub release at review time was 3.9.3. There was no successful v58 artifact
in the latest run. VersionCode 58 is a candidate identity, not an installed-build acceptance record.

## Two deliverables, explicit ownership

| Path | Role | Required without PC? |
| --- | --- | --- |
| `apps/mobile` | APK: UI, local agent, provider access, perception, policy, tools, verification, Brain and routines | Yes |
| `apps/device-gateway` | Companion implementation: discovery, ADB transport, phone witnesses, local API | No |
| `apps/pc-companion` | Windows UI and packaged sidecar lifecycle | No |
| `tools/codex-phone-mcp` | Canonical phone tools for external PC agents | No |
| `tools/cyclone-agent-mcp` | Companion connector/status/configuration adapter | No |
| `packaging/pc-companion`, `scripts/pc-companion` | Package the companion and its internal sidecars | No |
| `apps/mobile/mobilerun-embedded` | Embedded diagnostics support; not a separate app/runtime product | Yes, as an APK dependency |

The gateway and MCP packages are implementation modules of the PC companion, not additional
products. Keep those boundaries instead of merging all Python into the UI or moving Android
verification onto the PC.

Standalone execution is:

`Ask Cyclone → OpenRouterAdaptiveAgent → CycloneLocalAgent → CyclonePcParityBridge →
CycloneAgentEnvironment → PhoneToolExecutor`.

Despite its name, `CyclonePcParityBridge` is an in-process Android adapter. It makes no connection
to a PC. Internal API models require internet access and a configured API key; they do not require
PC pairing, ADB, the Windows application, or a Core server. The companion uses the Android gateway
to reach the same constrained phone runtime.

## Corrections in this change

- Align the two failing diagnostics tests with schema `/3`; add coverage that separates completion
  rechecks, tool failures, recovery cycles and Free Mode entries.
- Check cancellation and the task deadline after blocking observations/provider calls and before
  executing a returned action. A late plan must not act after Stop. Add a visible Stop task control,
  cancel outstanding HTTP calls on explicit Stop, and propagate coroutine cancellation to the loop.
- Keep the rejected-DONE counter until verified progress occurs. A failed ACT or another directive
  between DONE claims must not replenish the completion budget.
- Include the graph's recent failure codes in subsequent model context, including rejected DONE.
- Use one completion authority, removing the extra legacy keyword matcher that rejects short
  domains and tasks already satisfied on the starting page.
- Finish simple, independently verified host navigation locally before another provider call.
  Compound tasks and content questions are excluded from this fast completion path.
- Require current browser host evidence. Neither the requested URL echoed in Cyclone nor any
  successful browser launch is proof the requested site loaded. Reject lookalike host suffixes;
  prefer an available address bar over mentions in page text.
- Classify provider authentication, credit, rate-limit and network failures as explicit blockers,
  with safe messages. Limit the whole HTTP call to 60 seconds; do not recycle provider errors as
  malformed model plans. Preserve verified completion returned by a vision fallback.
- Record failed verification/tool trace events as failures and preserve the CANCELLED terminal status.
- Retire the Core/Hermes websocket transport. Old saved settings no longer reconnect or forward
  notifications. Stored Core-dependent routines fail explicitly with `LEGACY_INTEGRATION_RETIRED`.
  A small non-network compatibility facade remains until old UI/source references are removed.
- Remove Core connection settings and the separate Teamwork Sniper APK promotion from active
  Settings. Add a guard against their return and against a fourth app product directory.
- Preserve test/lint reports even when Android CI fails, without publishing failed APK candidates.

The Android package, launcher, phone executor and approval boundaries remain the same. This is a
review candidate; no APK is distributed by this change and versionCode is therefore unchanged.
Increment it before distributing changed APK bytes to devices.

## Remaining blockers, in implementation order

| Priority | Blocker | Concrete next change and acceptance |
| --- | --- | --- |
| P0 | Compound goal contracts are incomplete | Replace keyword-only compilation with typed, ordered effects. `open example.com then click X` must require both effects; `scroll up` must not accept a down scroll. Carry the actual resolved target/direction in the sanitized action ledger. Negative tests must reject unrelated clicks and partial task completion. |
| P0 | Completion evidence is still heuristic | A host mention in page text without an address bar can still be ambiguous; generic goals match a few words. Add authoritative URL/page-state evidence and an explicit inconclusive result. Cover redirects, hidden address bars, search results, login walls and browser loading/error pages. Never infer success from intent dispatch alone. |
| P0 | Task ownership follows a Composable instance | `remember { OpenRouterAdaptiveAgent(context) }` is paired with global chat state. Moving between tabs/recreating the Activity can lose the session needed for resume. Introduce one lifecycle-owned task controller with StateFlow, persistent checkpoint/reconciliation, and explicit suspend/resume/cancel. Test tab changes, rotation, backgrounding, process death, and GATE after a long user pause. |
| P0 | No acceptance evidence on a device | Run the matrix below on the exact signed APK and record SHA, package/version, phone/OS, task log and outcome. CI cannot verify Accessibility targeting, overlays, OEM behavior or real model performance. |
| P1 | Several overlapping progress/recovery models | `AgentReliabilitySession`, `CycloneLocalAgent`, bridge recovery memory, and Free Mode counters coexist. Make the task controller the sole budget owner; adapters should return evidence and suggested recovery, not own additional stop/reset policy. Progress should track goal milestones, not incidental UI/fingerprint changes. |
| P1 | Two independently captured page representations | `observeState` and bridge observation both capture; prompts include legacy page/Brain/AppGraph plus a richer canonical context. Derive both projections from one observation and one generation, then remove duplicate prompt sections. Measure requests, payload bytes and capture latency before/after. |
| P1 | Source cleanup is incomplete | At review time Android contained 223 main-source files. Five older UI shells remain (`CycloneMobileApp`, V26, V27, V291, V292); several exceed 1,000 lines. Extract any shared helpers, prove no entry-point references, delete unused shells and the retired setup/Bridge facade, then run the Android suite. Preserve stored V2/V3 schema identifiers until migrated. |
| P1 | Large orchestration files impede reliable changes | Split the 1,200+ line adaptive agent into provider client, task controller and context assembler. Split the 1,000+ line environment into observation scope, tool validation and outcome recording. Keep one executor. Each extraction needs contract/outcome tests, not renamed copies of old implementations. |
| P1 | Release flows still have exceptions | Consolidate version-named dev/emergency publishing into one parameterized promotion workflow. Require exact successful source CI; never clobber an existing release with different bytes under the same tag/versionCode. Separate the historical development signer from a protected production signer and define an explicit migration. |
| P1 | Default branch and accepted baseline diverge | Integrate the accepted 3.9 lineage into `main` after validation. Retire superseded development branches only with an agreed retention policy. Link README/downloads to verified artifacts, not aspirational version labels. |
| P2 | Companion parity lacks one release acceptance record | Keep independent component versions but version the Android/gateway/MCP protocol explicitly. Test connecting, disconnecting and reconnecting the companion during a local task; confirm only one controller can mutate and local model execution remains usable after disconnect. |

## Acceptance matrix for a working duo

Run each deterministic phone task repeatedly with the PC off, then repeat with the companion
connected. Use at least two configured provider models. Suggested initial release gate: 10 runs per
task/model, no false completion, no action after cancellation, and at least 9/10 verified successes
on deterministic tasks. These are proposed targets, not measured results.

| Scenario | Required observation |
| --- | --- |
| Open `ad.nl`, including when already open | Correct current host; immediate local completion once proven; no further action/provider request after proof |
| Open another site, dismiss a cookie prompt, scroll down | Correct ordered effects and current evidence; no optional cookie acceptance contrary to policy |
| Open Settings, then a specific settings page | Correct destination rather than initial Settings screen or matching chat text |
| Wrong target, stale element ID, changing clock/banner | Fresh targeting or a bounded stop; incidental churn does not reset goal-progress budgets |
| Invalid API key, insufficient credit, 429, offline, slow response | Actionable terminal result and durable diagnostic; no repeated malformed-plan loop |
| Stop during provider request and between batch actions | No next mutation; cancelled run visible in Brain |
| GATE, return control, resume; rotate/change tabs | Same task identity, fresh observation, exact approval preserved; no duplicate task or lost resumability |
| Companion off; connect/disconnect/reconnect | Local API task path remains available; companion has no separate action authority |

Capture task outcome, total duration, provider requests, time after first DONE, verified mutations,
completion rejections, terminal reason, and the sanitized run log. Compare the candidate with the
previous installed version using the same task/model/device conditions. Do not label a release
stable based solely on a green build.

## Validation of this patch

- Local repository guard suite: 39 tests passed; product, security, and version guards passed.
- Local Android JVM/assembly gate: unavailable. The wrapper could not download Gradle 8.9 because
  this workspace cannot reach `services.gradle.org`; no local Android SDK was available.
- Android unit tests, lint and unsigned candidate assembly: use the linked pull request's exact CI
  check as the source of truth. New regression tests are committed for that gate.
- PC implementation was not changed. Its previous CI result is historical evidence only.
- Physical Android/Windows acceptance and real-provider task performance: **UNVERIFIED**.

See the pull request for the exact candidate commit and resulting CI run. Do not promote this
candidate until the remaining P0 device/task-lifetime/contract gaps relevant to the release have
been accepted or fixed.
