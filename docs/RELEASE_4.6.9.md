# Cyclone Mobile 4.6.9

Android versionCode 129; builds on published 4.6.8.

This release continues the failed-run reliability hardening from 4.6.7 and 4.6.8. It closes the next four gaps around human handoff, web-intent proof, stale-target convergence, and account-creation completion.

## Reliability fixes

- Re-grounds transient planner state after GATE / human takeover. Resume still requires a fresh authoritative observation, and Cyclone now also clears stale click-memory, stale-target quarantine, recovery/free-mode carryover, and the previous login-autofill burst position before planning again. Explicit user Autofill authorization is preserved, but it restarts from the fresh current page rather than carrying a pre-handoff submit step forward.
- Strengthens `phone.launch_intent` verification for HTTP/HTTPS. A package transition to Chrome, a resolver, or a new tab is no longer sufficient: the requested destination host must appear in the authoritative after-state before the launch is semantically verified or learned as a successful route. Lookalike domains do not satisfy the host check.
- Adds semantic stale-target quarantine. Observation UUID churn cannot authorize endless retries of the same logical control; after two fresh captures reject the same semantic target as stale, Cyclone forces a different grounding/recovery strategy. Verified progress or a human handoff clears the quarantine.
- Adds an explicit account-creation completion contract. Signup goals cannot complete merely because the requested site is open, a registration form is visible, or a submit/continue action ran. Cyclone requires post-registration evidence such as an email-confirmation/registration-success state or strong signed-in evidence, in addition to the existing site/browser requirements.

## Regression coverage

- Human-handoff action-memory and login-autofill reset behavior.
- HTTP launch host verification, including package-change-only and lookalike-domain counterexamples.
- Repeated stale logical targets across changing observation IDs, plus reset on verified progress/handoff.
- Facebook-style account creation: signup landing remains incomplete; post-registration confirmation or strong signed-in evidence can satisfy completion.

## Preserved safeguards

- PhoneToolExecutor remains the sole phone mutation authority.
- GATE and user takeover behavior remain mandatory.
- Mutation success still requires semantic verification.
- Provider circuit isolation and explicit Chrome routing from 4.6.8 remain unchanged.
- Signup goals remain excluded from saved-login autofill.
- Current-target revalidation still fails closed for genuinely ambiguous overlapping controls.

Mobile CI must pass repository/product guards, gateway/MCP contracts, Android unit tests, lint, release-candidate assembly, provenance checks, signing, and update-continuity verification before publication.

Physical Pixel 8 acceptance remains UNVERIFIED for this release.
