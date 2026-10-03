# Native ID Generator — Alpha 99 grounding and build

Baseline: published `v5.0.0-alpha.99.dev1`, commit `c7d0526d`, Glass `1.0.0-alpha.55`. The publisher uses `claude/cyclone-v5-handoff-review-9qrs40`. The earlier UI branch contains the same feature work without the release commit. Read the current Ports SDK contract, hub, bindings, traffic, phone bridge, mobile outbox/toolbox and Glass implementation before changing them.

## Findings

- The real Port Hub already signs messages, checks plugins, persists keys/awaits, routes by app/routine and polls paired phones. Extend these paths rather than introduce another engine.
- Phone `port_send` only exposes five fixed ports; custom generation and owner-provided photo messages cannot be sent. `port_wait` supports only an `ask` string, so it cannot reliably match an employee request or front/back output.
- The hub fans out out ports. An employee photo must instead reach the specifically selected, owner-approved plugin. An agent's explicit plugin choice must never bypass an owner binding, consent, pause, health or usage restriction.
- There is no approved plugin skill advertisement to the phone. Add bounded, owner-configured capability descriptions through the PC's existing authenticated poll. Plugin result values remain data, never instructions.
- MRZ Studio 7.2 implements the generator and four-port contract, but its photo URL validator recognizes the SDK DevHub route and rejects the real hub's `/v1/ports/artifacts/` route. Fix and regress both URL paths.
- Adobe's blocking application screen still prevents real Photoshop export acceptance. Simulator/contract/package checks must not be described as a verified employee export.

## Build checkpoints

1. **Native starter and policy:** ship ID Generator in Cyclone's starter collection; discover local Studio, show its actual health, pair after visible port consent without returning keys to the browser, and persist developer usage guidance plus app/routine restrictions. Keep endpoints literal loopback, redirects/proxies disabled, and do not launch discovered programs. MRZ remains the source of truth for templates, fields, fonts, crop/background removal, country rules and output rendering.
2. **Glass:** dedicated ID Generator route, discovery/connect state, generation/defaults panels, endpoint and agent usage controls, schema/skill copy, and links to the existing Port map/activity. Never claim an executable/template check proves Photoshop operable.
3. **Agents and traffic:** explicit plugin addressing, live capability advertisement, custom structured generation, owner attachment photo, request/output matching and clear unavailable/failure feedback. Retain sealed codes, private-screen refusal, cancellation and value-as-data boundaries.
4. **Acceptance:** tests for consent/keys, endpoint safety, scope enforcement, routing isolation, stale/disconnected capability revocation, attachments and match forwarding; full relevant gateway/SDK/Glass/mobile suites and guards; live MRZ/real hub trial using synthetic data, labelled dry-run if Photoshop remains blocked; browser review.
5. **Release:** checkpoint source, integrate on the real development branch without overwriting concurrent work, increment coherent product/mobile/PC/Glass versions, release through the existing signed CI lane, verify published assets and provenance. No additional development-branch pushes while publication is running.

The bundled skill describes when to use the plugin and the precise request sequence. The agent chooses it for a matching task; developer rules determine where it is actually callable. Descriptive matching does not grant consent, change phone model selection, or execute a skill automatically.

## Concurrent release integration

Alpha 100 (`c74a6ece`) landed during acceptance. Preserve its codes-from-texts implementation and release notes; resolve the mission/toolbox constructor by passing both the portrait attachment and code capability. The native starter therefore ships as Alpha 101 / Glass Alpha 56, Android 246.
