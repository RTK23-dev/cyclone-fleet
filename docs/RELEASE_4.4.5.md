# Cyclone Mobile 4.4.5 — One authoritative observation

Mobile **4.4.5 / versionCode 105** implements Sprint 2, proposal 15 of the Artemis comparison.
It preserves the published 4.4.4 Liquid Glass redesign and Cyclone One 1.5.5 compatibility.
The requested 4.4.4 number was already published for that UI work; this release advances the
version rather than replacing its immutable APK.

## Observation foundation

- One semantic capture supplies the legacy PageContext, current Page Card, executable controls,
  prompt and learning snapshot. Every projection shares the source evidence ID and generation.
- Learned controls and old screenshots no longer appear as current executable evidence.
- Session, display, workspace/profile, window, geometry and timing travel with captured evidence.
  Unknown fields remain explicitly unavailable. Workspace profile identity requires the current lease.
- Lightweight metadata checks reject changes during tree/image capture, including rotation,
  window changes, scope changes and background execution-generation changes.
- Visual escalation uses one new semantic/image bundle. Failed or stale screenshots are unavailable;
  a stable semantic tree remains usable. Cancellation cannot publish a successful image bundle.
- Streamed image requests wait for a frame captured after the request boundary. Transient pixel
  failures have one paced retry and do not consume the single usable-image budget.
- Shadow comparison covers full capture identity and executable control state, bounds and capabilities.
- Capture changes clear the current page and report a precise bounded-recovery state. PhoneToolExecutor,
  current-target revalidation, human ownership, approval gates, overlay exclusion and secret redaction remain.

## Verification

The shadow comparison preceded the authority switch. The capture-boundary checkpoint passed
1,066 Mobile JVM tests and 78 repository guard tests. Controlled fixtures cover page changes during
capture, overlay animation, screenshot failure, rotation, display/profile/scope changes, stale/delayed
pixels and cancellation. They verify one semantic traversal per bundle, one screenshot for an explicit
visual bundle, and zero additional captures for repeated projections. A semantic observation followed
by visual escalation is two explicit generations overall. Injected timing is not real-device latency.

The external-review fixes passed 1,074 local Mobile JVM tests and all 78 repository guard tests. The combined release candidate must additionally pass exact-source Mobile CI tests, lint and APK
assembly. Publication verifies APK identity, SHA-256/provenance and signer continuity against v4.4.4.
Android setup explicitly requests `platform-tools` because the old action default requested the
unavailable `tools` package; no validation or signing check is disabled.

Physical-device acceptance is **UNVERIFIED**: no Android device was attached. Accessibility events can
be delayed or absent; these checks do not make Android capture atomic. The 1,500ms image bound and
multi-display behavior still need device calibration. This release does not claim the original browser
failure is solved solely from compilation or synthetic tests.

Implementation provenance, checkpoint SHAs and limitations: [cumulative ledger](SPRINT_4.4_EXECUTION.md).
Future perception, grounding and memory work must use the [observation contract](OBSERVATION_CONTRACT.md).
