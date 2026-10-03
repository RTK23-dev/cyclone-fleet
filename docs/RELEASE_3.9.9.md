# Cyclone 3.9.9 — release audit and implementation

Baseline: published v3.9.8, source 18d2b0ea58de8e4a79491c02237a81d6f628ec7f, versionCode 62.
Branch: agent/399-composer-and-model-compat. Preserve the published APK.

## Audit of 3.9.8 against the requested behavior

- Partial: floating composer has microphone/send, but no plus button and settings occupy the header. Fixed-height sheet clips expanded settings/content. This does not meet the supplied compact Gemini reference.
- Partial: downward swipe triggers minimization, but the panel does not follow the finger.
- Implemented in source: session-scoped background pixels, bounded preview, pause/handoff, notifications and a non-touchable active border. Physical acceptance was explicitly left unverified by the previous agent; a successful build does not establish it.
- Implemented conservatively: chronological history, task-scoped browser typing and at-most-once shutter ledger. Saved-photo proof depends on already-authorized MediaStore access; an uncertain capture hands back rather than retries.
- Partial: provider diagnostics preserve errors, but model qualification, planning and optional learning have different request shapes. Qualification forces temperature and 300 max tokens, planning omits reasoning despite a universal slider, and learning sends model-specific effort strings with JSON mode. Contributor access was never account-verified.
- Missing from floating composer: attachment selection. Main chat contextual actions do not satisfy this requirement.
- Missing from ordinary display-0 tasks: an ongoing Android task notification matching the isolated workspace experience.
- Documentation drift: README still advertises 3.9.5.

## 3.9.9 implementation

1. One compact composer: plus/settings left, unboxed text center, microphone/send right; continuous drag with settle/dismiss; scrollable settings above it.
2. OS picker/camera-thumbnail attachments for the next request. Attachments are untrusted reference data, not instructions or authorization.
3. Shared portable OpenRouter request shape and live endpoint discovery, preserving exact model/privacy identity and exposing actionable same-account access errors. Cyclone does not invent generic reasoning tiers or silently weaken Contributor privacy settings.
4. Failed tasks clear LIVE styling instead of continuing to look active.
5. Ordinary Ask Cyclone tasks now publish a private ongoing notification with bounded progress, a human-handoff state, and the final verified result. Optional cloud refinement is queued asynchronously and is not required for the notification lifecycle.
6. Single-photo tasks retain at-most-once shutter semantics. Cyclone records the pre-shutter MediaStore IDs, requires a newly saved non-pending `DCIM/Camera` image taken after the shutter request, and never retries the shutter when proof is unavailable. Existing deterministic tests cover old-gallery, missing-witness and verified-save cases.
7. Version updated to 3.9.9 / versionCode 63 with an exact-source CI and release workflow.

## Evidence limits

OpenRouter public endpoint evidence was captured for all eight registry models. Public availability is not proof of this user's account entitlement. No account API key or physical Android device is available in CI; do not claim an end-to-end Muse or Contributor success without a live authorized probe.

Physical Pixel 8/OEM layout, drag behavior, camera save acceptance, and isolated-background execution remain device verification items. CI proves source tests/lint/build and release provenance, not those physical behaviors.
