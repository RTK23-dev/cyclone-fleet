# Repair of the 4.3.4 Reddit failure

The uploaded run `19379235a3c4` requested “open reddit.com on Chrome and login for me”.
Its initial four action requests were followed by eight rejected click turns without
meaningful execution. The failure was an execution/recovery lifecycle problem, not
simply a missing cookie instruction. The default cookie preference was already in
the model prompt.

This branch starts at the published `v4.3.4` source. It does not publish a release,
change the installed application, or claim physical-device verification.

| Failure | Repair | Regression coverage |
| --- | --- | --- |
| A private execution guard paused while the outer task kept planning | One task lifecycle; successful verified actions clear retry debt | `AgentReliabilityPolicyTest`, `CycloneExecutionBoundaryTest` |
| Cyclone overlay events and roots contaminated Chrome observations | Application-window selection; overlay-free task fingerprints; the same window for input and screenshots | `TaskSurfaceWindowsTest` |
| Secondary-window paths skipped the root incorrectly | Parse `w<ID>/0` as the selected window root, not its first child | `TaskSurfaceWindowsTest` |
| Early rejections bypassed recovery; observation churn erased escalation | Every rejected turn invokes recovery; only verified progress resets recovery memory | `CycloneExecutionBoundaryTest`, `CyclonePcParityBridgeTest` |
| Cookie consent consumed model turns despite a clear rejection option | Bounded local rejection before model planning; consent controls survive compact-card truncation | `CookieInterruptionPolicyTest`, `CycloneAgentEnvironmentTest` |
| Vision could describe a button without grounding an action | Frame-scoped image locator resolves a unique current control, then uses canonical `phone.click` | `VisualControlGroundingTest` |
| Alternate locators could repeat an already executed, unchanged click | Remember executed actions independently of observation UUID and visual provenance | `VisualControlGroundingTest` |
| Model and recovery screenshot paths had separate budgets | One screenshot budget until verified progress; preserve visual human handoffs | `CyclonePcParityBridgeTest` |
| Reaching Reddit could satisfy a compound login goal | Require current authenticated-session evidence and the requested browser | `GoalContractTest` |
| Several events inflated one failure/recovery; early rejection reasons were lost | Per-turn accounting with detailed action precedence, explicit executor invocation evidence, and sanitized rejection reasons | `AgentRunDiagnosticV39Test`, `CycloneExecutionBoundaryTest` |

## Behavior and limits

- The default is to reject optional cookies when one enabled, current rejection
  control is identified. Generic “Reject all” requires accompanying consent
  choices. Explicit cookie-choice instructions go to the planner. Ambiguous
  controls do not trigger an arbitrary click. One local attempt is permitted per
  rejection label/application/session during a run.
- A cookie click is an interruption, not task completion. The stable login goal
  remains active. A loaded host or visible login form cannot prove authentication.
  Current sign-out controls provide positive evidence; missing evidence keeps
  the login requirement unsatisfied. This is deliberately conservative, not a
  site-specific authentication API or credential acquisition mechanism.
- The visual locator validates capture age, frame identity, session, display,
  image geometry and current observation IDs. It resolves to an existing control;
  it does not add unrestricted coordinate input for an unlabelled custom canvas.
  Ambiguous, stale or unmappable pixels are reported as unresolved. Existing
  `PhoneToolExecutor` freshness, policy, GATE and after-state checks remain active.
- Foreground window screenshots that exclude Cyclone's overlay require Android
  API 34+. Android 13 retains the available live capture source. Named background
  sessions remain on their own display and never use foreground fallback.
- Diagnostic schema `/4` separates rejected tool outcomes, after-state verification
  failures and completion rejections. “Canonical executor invocations” counts
  explicit new telemetry; it is not a count of physical taps or an inference from
  old logs.

## Validation

Run `./apps/mobile/gradlew -p apps/mobile :app:testDebugUnitTest`. The branch's
Mobile CI additionally runs repository/product/security guards, gateway and MCP
tests, Android lint, and release assembly. Assess the CI result for the commit
being reviewed, not an earlier checkpoint. The development workspace cannot
download Gradle; GitHub CI supplies the Android build environment.

Before distributing a versioned build, perform a physical-device replay:

1. With Cyclone's overlay visible and optional-cookie consent pending, ask to
   open Reddit in Chrome and log in. Confirm one optional-cookie rejection and
   continuation to the login goal. Opening the host must not report login success.
2. Exercise a stale observation before dispatch. Confirm semantic recovery and
   then at most one screenshot attempt without progress. Check that an already
   performed unchanged click is not repeated via vision.
3. Check overlay expansion, collapse and status updates while Chrome is active.
   Observation, typing, scrolling and screenshots must retain the task window.
4. Exercise authentication/MFA or a consequential confirmation. Confirm human
   handoff, fresh observation after return, and no stored credentials or OTPs.
5. Repeat the applicable checks in a named background workspace. Confirm no
   foreground fallback and no cross-display target reuse.

Device replay remains unperformed in this development environment. Bump the
authoritative Android version metadata before distributing an installable build.
