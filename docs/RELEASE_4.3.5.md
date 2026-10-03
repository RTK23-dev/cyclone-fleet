# Cyclone Mobile 4.3.5 — agent reliability tip

Mobile 4.3.5 / versionCode 96. Published from the `codex/4.3.4-agent-reliability` tip after v4.3.4 (`c051477b`). This cut keeps the 4.3.4 task-glass, Profiles, and grounded harness work and publishes the Reddit-login execution repairs that landed after that tag.

Publication is authorized by the user. Exact-source Cyclone Mobile CI on `release/cyclone-mobile-v4.3.5` is mandatory. Physical Pixel 8 Reddit login and UI acceptance remain UNVERIFIED, as with 4.3.4.

## What this tip repairs

The 4.3.4 Reddit run `19379235a3c4` asked to open reddit.com in Chrome and log in. Four action requests were followed by eight rejected clicks without meaningful execution. That was an execution/recovery lifecycle failure, not a missing cookie instruction.

This release includes:

- One task lifecycle: a private execution guard no longer pauses while the outer task keeps planning. Verified actions clear retry debt.
- Overlay isolation: Cyclone overlay events and roots no longer contaminate Chrome observations. Input and screenshots use the same application window. `w<ID>/0` is the selected window root, not its first child.
- Recovery on every rejection: early rejections invoke recovery; only verified progress resets recovery memory. Escalation survives observation churn.
- Cookie consent: a bounded local rejection runs before model planning when one enabled, current reject control is identified. Consent controls survive compact-card truncation. Cookie clicks are interruptions, not task completion.
- Visual recovery: a frame-scoped image locator resolves a unique current control, then uses canonical `phone.click`. Ambiguous, stale, or unmappable pixels stay unresolved. Non-mutating boundaries (including human handoff) are preserved when image evidence expires.
- Duplicate-click memory: already executed, unchanged clicks are not repeated through alternate locators or vision.
- Shared screenshot budget: model and recovery screenshot paths share one budget until verified progress. Visual human handoffs are preserved.
- Compound login evidence: reaching Reddit or showing a login form does not satisfy a login goal. Current authenticated-session evidence and the requested browser are required.
- Task-window binding: typing, scrolling, and screenshots stay on the observed task window.
- Diagnostics: per-turn accounting, explicit executor invocation evidence, and sanitized rejection reasons. Schema `/4` separates rejected tools, after-state verification failures, and completion rejections.

PhoneToolExecutor, GATE, credentials architecture, root profile primitive, and execution planes are unchanged. This release does not add unrestricted coordinate input, a credential manager, or a site-specific authentication API.

## Validation

Reliability-branch Mobile CI succeeded on source `9a349889` (run `34637843359`) and on checkpoint `417d808c` (run `34600511486`). Intermediate pushes were cancelled by branch concurrency after later commits. The exact 4.3.5 identity on this release branch must independently pass unit tests, lint, release assembly, provenance, APK identity, and update-signature continuity with v4.3.4 before GitHub publishes the release.

Physical device replay of overlay-visible cookie rejection, Reddit login, stale-observation recovery, and named-workspace isolation was not performed in this environment.

## Preserved 4.3.4 product

Ask Cyclone still uses Working / Action needed / Done with backend-controlled Take Over / I'm Done. Autofill remains disabled. Named VD frames remain exact and secure.
