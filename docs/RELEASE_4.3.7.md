# Cyclone Mobile 4.3.7 — OpenRouter catalog and model selection

Mobile version 4.3.7 / Android versionCode 98, based on the published v4.3.6.

## Settings workflow

1. Open Settings → Model & API and save an OpenRouter API key.
2. Cyclone loads OpenRouter's full model catalog. Search by model name, provider, or exact model ID using the field below the key editor.
3. Tap a chat model to toggle its green check. The check means **included in your picker**, not paid access or a successful inference test.
4. The app and overlay pickers contain only these selections. There is no selection-count limit. Unchecking the active model chooses from the remaining selections; unchecking everything leaves the picker empty.

Selection and public catalog metadata survive app restarts. Upgrading retains the previously active model and manually added custom models. Refresh updates catalog metadata without replacing the user's choices. Removed catalog entries remain visible as selected entries so they can be unchecked. Non-chat models are shown but cannot be added to Cyclone's chat/task picker.

## API changes

- Authenticated `GET /api/v1/models?output_modalities=all` requests the full catalog, without pagination limits.
- Authenticated `GET /api/v1/models/user?output_modalities=all` checks the catalog against account provider preferences, privacy settings, and guardrails. Unavailable entries are labeled. If this check fails, the full catalog stays visible with an explicit warning; access is not assumed verified.
- Saving a key, refreshing, and changing checkboxes never sends a paid completion request. API keys remain in the existing secure store and are not persisted with catalog metadata. Catalog requests do not follow redirects with the bearer token.
- Requests use the chosen OpenRouter ID, including newly added and `:free` model variants. Live catalog image-input metadata is used where available.
- Completion requests no longer pin routes to an unauthenticated public endpoint list. OpenRouter can choose an eligible route for the account; Cyclone preserves its provider-fallback policy and never changes account privacy settings or substitutes another model.
- Visual fallback uses the selected model. A text-only model cannot receive a screenshot; the user gets a specific explanation when visual evidence is required.
- Output limits respect catalog completion limits. Explicit short qualification requests are no longer inflated to a 4,096-token minimum.
- Error reporting distinguishes authentication, access, account credit, privacy routing, rate limits, and content/guardrail blocks. Error bodies inside HTTP 200 responses are handled as failures. Provider explanations are sanitized before display.

A catalog listing is not a guarantee that every completion will be accepted. An actual OpenRouter account restriction, insufficient balance, or request-level guardrail can still reject a request. Cyclone reports that reason; it cannot grant access denied by the provider.

## Validation

Regression coverage exercises full-catalog and filtered-account HTTP contracts, search and capabilities, sanitized errors, exact model IDs across storage/configuration, more than 20 selections, removal of the active/last model, portable routing, and existing contributor privacy identity.

The required Android unit tests, lint, unsigned release APK assembly, repository guards, and artifact packaging run through Cyclone Mobile CI on `codex/cyclone-4.3.7-openrouter`. Local Gradle execution is blocked by the workspace's inability to download the Gradle distribution. See the exact commit's CI result for build status.

Physical-device acceptance is still required: key save/replace/remove; loading and searching a real account's catalog; selecting two and twenty models; app restart; both pickers; one text completion and one image-capable phone task; empty selection; and an account-denied model. No real user key or target phone was available during implementation. This branch does not itself publish a signed GitHub Release.

## API references

- [Full model catalog](https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties)
- [Models filtered by user settings](https://openrouter.ai/docs/api/api-reference/models/list-models-filtered-by-user-provider-preferences-privacy-settings-and-guardrails)
- [Errors and debugging](https://openrouter.ai/docs/api_reference/errors-and-debugging)
