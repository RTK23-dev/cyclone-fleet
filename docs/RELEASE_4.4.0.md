# Cyclone Mobile 4.4.0 — execution foundation

Mobile 4.4.0 / Android versionCode 100, based on published v4.3.8. Cyclone One is unchanged.

Ordinary cookie interruptions use the existing local policy before route recall or provider planning. A recognized, unique reject control requires zero provider calls before the first dismissal action. English and Dutch reject choices are supported. Ambiguous, unavailable or already-attempted controls produce a precise pause while preserving the original goal. Login, permission and consequential-action boundaries remain human-owned.

One scoped semantic observation now feeds the legacy page, current page, executable controls and learning projections. Explicit visual inspection brackets the screenshot with semantic captures and rejects changed scope, content, geometry or excessive capture skew. Failure clears actionable stale state. Recovery incidents persist with their intended effect and close as verified only when the effect has evidence. Moved controls are re-resolved against a fresh observation immediately before the canonical PhoneToolExecutor; stale coordinates are not reused.

Observation failures have typed health, same-scope cooldown and bounded recovery. Phone tasks, text chat and qualification share per-request deadlines, individual cancellation and bounded transient pacing. Permanent access errors do not retry. A late provider response after Stop cannot dispatch an action. The selected model and routing are never silently changed. All key-scoped catalog/search/picker/error and exact per-model reasoning behavior from 4.3.8 is preserved, as is Cyclone overlay exclusion.

Payload-free causal timing covers observation, local policy, recall, prompt, provider lifecycle, dispatch and verification. A late returned plan fails before action dispatch. Progress distinguishes provider waiting, backoff, deadline and cancellation.

## Automated evidence

The five implementation checkpoints and release follow-up passed 1,033 Mobile JVM tests, 72 release-guard tests, 199 gateway tests and 158 phone MCP tests. Controlled fixtures measured:

| Fixture | Result |
|---|---|
| Unique cookie reject | One dismissal; zero provider calls before it; original login goal remains suspended at authentication |
| Semantic projection race | One shared capture replaces two independent captures |
| Moved target | Fresh element ID dispatched; replacement, ambiguous and wrong-scope targets dispatch zero actions |
| Permanent provider 403 | One attempt, zero actions |
| Transient 503 / repeated 429 | Recovery on second attempt / maximum two attempts |
| Response after Stop or deadline | Zero actions; unrelated request remains alive |
| Observation recovery | Two same-task captures after cooldown; permanent unavailable service uses one capture and zero provider calls |
| Injected phase delay | 35,001ms plan rejected before dispatch; 75ms span accounting exact |

These are scripted JVM/interceptor/injected-clock results, not live provider or physical phone latency measurements. The exact release source must pass Mobile CI tests, lint, release assembly and repository guards before the existing publication workflow verifies APK identity, SHA-256, source/run provenance and signing continuity with v4.3.8.

## Limits

Physical-device browser/login acceptance is **UNVERIFIED**. Compilation and fixtures alone do not establish that the original real-device failure is solved. Device p50/p95, process-restart behavior and screenshot-skew false-rejection rates remain unmeasured. Semantic matching is deliberately conservative. Only supported incident effects have automatic verification; other effects remain unresolved. Synchronous Android calls check phase budgets when they return; HTTP has active deadline cancellation. Grounding and settling are included in dispatch timing and retain the executor's existing bounds.

Implementation-to-proposal mapping, individual checkpoint SHAs, tests and limitations: [sprint record](SPRINT_4.4_EXECUTION.md). Artemis citations were used as architectural evidence, without replacing Cyclone's canonical executor, ownership or display isolation.
