# Cyclone 3.9.8 implementation checkpoints

Baseline: `70d2b8aa9dab1e7df63d44c1b355c87e8ec55a97`, published Cyclone 3.9.7 / Android versionCode 61.
Implementation branch: `agent/398-runtime-and-task-ui`.

## Scope and checkpoints

1. Preserve this baseline and the 3.9.7 compiler-memory settings; inspect the supplied three-run review.
2. Provider: preserve Muse Contributor identity and consent; improve request compatibility and sanitized failure evidence. Verify official requirements. Live account/device success requires actual authorized credentials and hardware.
3. Runtime: standardize chronological action history, scope ordinary typing authorization to the user task, bound one-photo capture verification, prevent repeated shutter effects and irrelevant learned-route replay, and keep optional learning from changing task outcomes.
4. UX: Ask Cyclone composer with screen-sharing/background-task, settings and dictation controls, no live conversation voice mode, swipe-down dismissal, ongoing task notification, live progress/preview with intervention, and an active-screen border. Reflect actual foreground/background capability.
5. Version 3.9.8 with a new versionCode; run tests, lint, product/security/version guards and release assembly. Preserve the published 3.9.7 artifact.

Each implementation checkpoint will be pushed to this branch with its validation state. A checkpoint is not a release or physical-device acceptance claim.

## Evidence available at start

- Supplied `Cyclone-3.9.7-lessons-and-agent-prompt.md` reviewed in full.
- Six attached visual references reviewed.
- 3.9.7 Mobile CI run `34022430771`: success.
- 3.9.7 development publisher run `34022430744`: success.
- The three raw device diagnostic files named by the supplied review were not included in this attachment set.
- No connected Android device or authorized provider account has been established in this session.

## Comparison with earlier work in this chat

The durable earlier work is commit `05f7d6ea88572d4d0170e93778d94b0c758cbc24` (standalone reliability/duo audit). The subsequent uncommitted live/background experiment described in chat is not present in this restored workspace. The 3.9.7 implementation at `49c41d1` is therefore the source baseline for live/background work; do not rebuild or overwrite it from chat snippets.

## Status

Four visual design checkpoints and runtime regression fixes are pushed. Android tests, lint and unsigned release assembly passed for `c01691cb9d7b30668385f0a8e437d9b3f497eae6` (3.9.8 / versionCode 62). Final signing/publication is left to the user.

## Runtime checkpoint (implementation, validation pending)

- Chronological action ledger makes newest outcomes visible to the planner.
- Provider failures retain HTTP status, safe message/code, request ID and exact selected model; Contributor routing remains explicit with fallback disabled.
- The canonical consolidator obeys local-only preference and distinguishes verification from Android acceptance; optional cloud requests/usage are recorded separately.
- Browser typing authorization comes from the real task and observed address field; named-site completion retains the requested browser.
- Single-photo effect ledger prevents a second shutter after an uncertain result and checks new camera MediaStore evidence when already authorized. Without media access it hands back for review rather than claiming success or retaking.
- Added history, typing and photo regression tests. Android dependencies are still being provisioned; this checkpoint is not a tested release.

## Design checkpoint 1 — Ask Cyclone

Dark charcoal composer, original orbit mark, quiet blue/mint accents, larger editorial empty state, model menu, settings, speech dictation and contextual screen/background menu. Existing submission/preflight/policy paths retained. The provider request guard now checks the preceding runtime checkpoint's explicit routing restrictions. Python guards pass; Android CI is enabled for this branch and will validate this checkpoint. Device visual acceptance remains unverified.

## Design checkpoint 2 — Floating assistant

Charcoal floating sheet with restrained aurora edge, original orbit identity, swipe/tap minimization handle and 48 dp controls. Primary chrome has an explicit Stop task action during execution; Exit is tucked into settings. Dictation and policy confirmation retain their existing handlers. Constant animated color fields behind text were replaced by a static edge treatment. Android/device validation pending.

## Design checkpoint 3 — Workspace and notifications

Matching background task setup, charcoal progress card, contextual pause/resume/review actions and private lock-screen notification content. Progress is indeterminate and follows real agent callbacks. Expanded progress reads only existing session-scoped live pixels at bounded resolution; hiding the preview leaves execution alone. Freshness failure clears the image. No synthetic progress, example task content or fabricated live frame is used. Preview is protected from screen capture. CI and physical validation pending.

## Design checkpoint 4 — Active-screen cue and build identity

A thin, static aurora border follows active foreground control and disappears on pause/exit. Its separate accessibility window cannot take focus or touch. Expanded workspace progress adds explicit Take control in app through existing ownership revocation/handoff. Version identity is now 3.9.8 / 62. Fixed the inherited MediaStore class-as-value compilation error found by CI. Earlier design checkpoints remain independently recoverable; final Android gate is in progress. No 3.9.8 release tag or signed publication is implied by these source checkpoints.

## Validation follow-up

All new UI source compiled in CI. First complete-tree Android test run exposed a real inherited navigation matcher defect (article URLs treated as simple host navigation) and one stale composer source assertion (one minimum line versus two). Restricted automatic completion to root hosts, accepted the original "on Google Chrome" phrasing, excluded common app names from named-site interpretation, and added a browser-scope regression for the user's exact Telegraaf wording. Full Android gate rerun required. The upload correction commit restored omitted unchanged files without resetting the branch; final source trees are verified against the complete local tree before each ref update.


## Device acceptance still required

- Muse Contributor: confirm the configured account can make a real request. Diagnostics and routing improvements do not establish provider account access.
- Camera: take one photo with Astra, verify one saved image, and confirm completion does not continue into unrelated actions or repeat the shutter.
- Chrome: run “go to Telegraaf on Google Chrome”; verify Chrome displays the destination before completion. Article paths must not qualify as homepage completion.
- UI: inspect composer/keyboard, dictation, screen-sharing consent, floating-sheet dismissal, background preview freshness, notification pause/resume and Take control on supported hardware. Confirm preview dismissal keeps the task running and handoff relinquishes input. No physical-device or screenshot-rendered visual acceptance has been performed in this session.
- Final APK: sign using the existing release process, verify package/version/signature and install/upgrade behavior, then publish. CI candidate assembly alone is not a signed release.

## Final build verification

- Tested source: `c01691cb9d7b30668385f0a8e437d9b3f497eae6`; complete source tree `38c0b01c66d8d38b3749409cf5ea074634ad94ff`.
- Branch CI: https://github.com/premiumcentraal-boop/Cyclone/actions/runs/34038790882 — Android unit tests, lintDebug, assembleRelease, provenance and candidate upload passed.
- Pull-request CI: https://github.com/premiumcentraal-boop/Cyclone/actions/runs/34038793898 — successful, including release assembly.
- Product/version/security guards and 39 CI guard tests passed. Gateway/MCP contract checks and Windows setup dry run passed.
- APK metadata verified by CI: `com.cyclone.mobile`, versionName `3.9.8`, versionCode `62`. Artifact `Cyclone-Android-3.9.8` contains the unsigned candidate and provenance.
- This final handoff change only updates documentation; no source changes follow the tested commit. Signed publication and physical-device acceptance remain outstanding as requested.
