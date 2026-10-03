# Cyclone Mobile 4.6.8

Android versionCode 128; builds on published 4.6.7.

This release continues the failed-run reliability work from 4.6.7. The 4.6.7 WebView target-revalidation fix remains in the base; 4.6.8 adds the next four protections around provider transport, browser intent, diagnostics, and combined regression coverage.

## Reliability fixes

- Adds a task-scoped provider circuit breaker. After OpenRouter's bounded transport retry is exhausted for a selected model/provider-sort route, Cyclone opens that route for the task instead of immediately issuing another provider request.
- Separates transient provider failures from phone grounding recovery. Rate limits, network/lifecycle cooldowns, and circuit-open states terminate as `PROVIDER_RETRY_LATER`; they do not consume stale/semantic/vision recovery budgets or trigger screenshot escalation.
- Preserves explicit browser intent through trailing task instructions. A goal such as “open Chrome and go to Facebook and try to make an account…” now stays on the Chrome/web route and resolves to `https://facebook.com` rather than switching to the native Facebook package.
- Makes run diagnostics orthogonal and truthful: executor invocations, Android-accepted executions, fresh after-state observations, task-progress observations, and semantically verified mutations are reported separately.
- Adds a combined 4.6.8 regression fixture for the exact failed Facebook/signup path, including explicit Chrome routing, signup intent, provider 429 isolation, circuit opening, and read-only progress accounting.

## Preserved safeguards

- PhoneToolExecutor remains the mutation authority.
- Mutation success still requires semantic verification; diagnostic progress does not weaken mutation verification.
- Provider handling never auto-switches the user's selected model.
- GATE/login/signup boundaries from 4.6.7 remain intact.
- The 4.6.7 WebView duplicate-representation revalidation fix remains covered by its existing regression tests.

Mobile CI must pass repository/product guards, gateway/MCP contracts, Android unit tests, lint, release-candidate assembly, provenance checks, signing, and update-continuity verification before publication.

Physical Pixel 8 acceptance remains UNVERIFIED for this release.
