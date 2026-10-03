# Cyclone Mobile 4.2.7 — Structural Reliability

Mobile 4.2.7 / versionCode 88 is the follow-up reliability release to 4.2.6. It keeps the 4.2.6 visual architecture and focuses on execution routing, task cleanup, overlay recovery, and rooted multi-profile operation.

## Included

- Ordinary Ask Cyclone requests now default to the real foreground phone session. Naming an app no longer silently routes the request into an isolated background workspace.
- Explicit background requests keep the existing isolated-workspace safety gates and exact session identity checks.
- Simple “open app” requests can use the verified native phone launcher path without an unnecessary model round trip.
- Task stop/close now recovers exact task identity after service recreation, waits for cleanup, clears the active task safely, and preserves unrelated queued work.
- Layer 2 task closure revokes only the matching mutation lease instead of clearing other armed jobs.
- Background cleanup no longer detaches the Cyclone launcher; overlay recovery reuses the existing controller and restores the launcher when needed.

## Rooted profiles

- Cyclone-owned secondary profiles now use full Android secondary users rather than depending on a managed/work-profile slot.
- An unrelated existing managed profile such as a legacy “Rooted Clone” is not adopted and does not block creation of a Cyclone-owned secondary user when Android still allows another full user.
- Cyclone itself is installed and verified in each new profile together with the user-selected apps.
- Installed trusted support apps are also made visible in the new user through a fixed allowlist for Shizuku, Magisk, KernelSU / KernelSU Next, and APatch. No arbitrary package cloning surface is exposed.
- Profile creation is restricted to the verified main Android user (Profile A). Cyclone will not create nested profiles from B/C.
- “Return to Profile A” performs a verified Android user switch instead of only clearing workspace selection.
- Saved profile journals form a persistent collection so failed provisioning resumes the exact journaled identity instead of creating another profile.

## Supported usage and safety

Cyclone continues to enforce the Session Kernel, GATE/review behavior, one global mutation authority, exact workspace generation checks, and fail-closed profile identity verification. Multiple profile jobs remain time-sliced; this release does not claim simultaneous Android input.

A request issued from Profile A that explicitly names another profile is still fail-closed instead of migrating an already-running agent process across Android users. For 4.2.7 testing, switch to the desired profile in Cyclone first, then Ask Cyclone normally there. Ordinary foreground Ask follows the Android user that is actually active.

## Validation and publication

The structural branch was reviewed with `git diff --check`, and no TODO/FIXME/XXX markers were found in the affected mobile runtime/UI/test paths. The authoritative release gate remains GitHub Mobile CI, which runs unit tests, lint, and release assembly for the exact release SHA.

The user explicitly authorized publication on September 9, 2026 for immediate physical testing. Physical Pixel / USB / ADB acceptance for this exact build remains **UNVERIFIED before publication**. The full-release workflow must still verify the exact CI provenance and checksum, sign with Cyclone’s existing update-compatible development signer, verify certificate continuity with 4.2.6, and publish only if every gate succeeds.

No Windows component versions are advanced by this release. Cyclone One remains 1.1.2 and device gateway / MCP remain 4.1.0.
