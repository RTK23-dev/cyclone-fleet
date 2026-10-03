# Cyclone Mobile 4.6.7

Android versionCode 127; builds on published 4.6.6.

This release is a focused reliability update based on a failed browser/account-creation run. It keeps Cyclone's phone-authoritative execution, GATE behavior, and verification-first mutation contract intact while fixing four failure modes in the observe → act → verify loop.

## Reliability fixes

- Treats `phone.launch_intent` as a real mutation and page transition, requiring a fresh pre-action observation and using the same bounded 1.8-second settle/re-observe window as app launches.
- Separates read-only task progress from mutation verification. A fresh read-only observation such as `phone.wait_for` can establish verified task progress without being promoted to executable route evidence; mutations still require semantic verification.
- Makes login autofill goal-aware. Explicit account-creation intents such as “sign up”, “register”, “create an account”, and “make an account” bypass saved-login autofill, while explicit login tasks retain the existing behavior.
- Hardens WebView/Chrome target revalidation by collapsing duplicate semantic/raw representations of the same accessibility node and compatible nested wrappers while still rejecting genuinely distinct overlapping sibling targets.

## Regression coverage

- URL launch mutation + transition-settle contract.
- Read-only progress acceptance versus mutation verification.
- Mixed Facebook-style login/signup screens and primary registration forms.
- Raw accessibility mirrors, nested WebView wrappers, and true sibling ambiguity.

Mobile CI must pass repository/product guards, Android unit tests, lint, release-candidate assembly, provenance checks, and update-continuity verification before publication.

Physical Pixel 8 acceptance remains UNVERIFIED for this release.
