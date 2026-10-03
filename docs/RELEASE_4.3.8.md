# Cyclone Mobile 4.3.8 — OpenRouter access and exact intelligence

Mobile version 4.3.8 / Android versionCode 99, based on published v4.3.7.

## OpenRouter model access

Cyclone now treats account availability as key-scoped state. The authenticated OpenRouter `/models/user` result is bound to a fingerprint of the current API key. If the key changes, the account-filtered check fails, or availability has not yet been verified, Cyclone fails closed instead of treating the public catalog as runnable access.

The full catalog remains searchable. Models that are unavailable for the current account are labeled as unavailable; models whose access is unknown are labeled as not verified. Previously selected stale entries stay removable without becoming runnable. The active model and model pickers contain only models verified available for the current key.

## Exact per-model intelligence

The Intelligence surface is now generated from the selected model's OpenRouter reasoning metadata instead of hard-coded Low / Medium / High values.

- No advertised efforts: Cyclone shows **Model controlled** and sends no reasoning override.
- One to three advertised efforts: Cyclone renders the exact values as pills.
- More than three advertised efforts: Cyclone renders one Intelligence selector containing every advertised effort plus the model default.
- Values such as `max`, `xhigh`, `high`, `medium`, `low`, `minimal`, or `none` are kept as exact OpenRouter effort tokens. Cyclone does not round, rename for transport, or map them onto a three-level scale.
- Choosing **Model default** removes Cyclone's reasoning override.

The selected intelligence value is stored per model, so switching models does not force one model's reasoning vocabulary onto another.

## Routing contract

Reasoning selection does not change model routing. Completion requests keep the exact selected OpenRouter model ID and the existing provider routing/fallback policy. Cyclone adds `reasoning.effort` only when the selected value is explicitly advertised for that model. Unsupported or absent reasoning choices are never substituted with another effort.

Text chat, foreground phone tasks, and background task requests use the same catalog-backed request contract, so the visible model/intelligence selection and the request serializer share one source of truth.

## Validation

Regression coverage locks the key-fingerprint access policy, fail-closed unknown/unavailable states, exact reasoning serialization, preservation of model/provider routing, the 0 / <=3 / >3 Intelligence UI threshold, and the existing model-picker/chat contracts.

Cyclone Mobile CI is the authoritative build gate for unit tests, Android lint, release APK assembly, repository guards, and artifact packaging. Publication is authorized for 4.3.8 only after the exact release-branch source passes that CI and the full-release workflow verifies artifact provenance and update-compatible signing continuity.

Physical-device acceptance remains **UNVERIFIED**. A real OpenRouter account and target phone should still be used to exercise key replacement, full catalog refresh, unavailable-account models, switching between models with different reasoning vocabularies, model default, a text reply, and a phone task.

This document is the publication checkpoint for `release/cyclone-mobile-v4.3.8`; the release pipeline must publish only the exact commit that passes the matching push CI run.
