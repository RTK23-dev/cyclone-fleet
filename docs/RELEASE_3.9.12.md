# Cyclone 3.9.12 — Background Intelligence

Base: published v3.9.11, 5a145c806b4db7a1b7c42b7e7e8e01a8631c7493. Android versionCode 66. PC companion remains optional and unchanged.

## Implemented

Ask Cyclone routes named installed apps to the existing isolated workspace service. Ambiguous targets open the existing app picker. Explicit live whole-display control sharing retains the foreground flow; ordinary read-only screen sharing does not grant foreground execution; background failure never starts a foreground agent. One background task is retained at a time; follow-ups are saved and can be started explicitly after releasing the previous workspace.

The existing composer shows a rounded running card and View progress, with a square Stop action. Mic/model controls hide until the editor is focused. Drag-to-dismiss keeps its existing continuous animation and leaves the service running. Internal agent progress is mapped to short fixed user-facing phrases.

A secure dedicated progress activity displays only the exact session's live frames in a reduced phone-shaped panel. Stale/missing frames show an unavailable placeholder; no foreground screenshot fallback. Notifications are pinned while working and use task/session-bound routes into that same activity.

Take control revokes input before moving the original Android task to display 0. It retains the service, session and agent checkpoint. Continue waits for suspension, moves the same task back, restarts the exact frame source, checks readiness and resumes the existing agent. Human pause time does not consume the agent's execution deadline. Stop cancels execution and releases the display, including creation/stop races.

Consequential controls classified by the existing GATE system pause execution. The progress page presents Modify and the appropriate confirmation action alongside the live prepared page. Local confirmation is one-use, expires after 60 seconds and binds action, node, page fingerprint and gate class within the existing workspace. PhoneToolExecutor rechecks it; policy denials remain authoritative. Other human/policy boundaries hand off for review in the original app.

Verified completion shows a result and evidence-based steps, retains the prepared page, and offers Open app from the result/notification. Nothing is reopened from its launcher for handoff.

## Checkpoints

- 0fda482 — exact-task return and workspace frame lifecycle
- 72f2eea — retained orchestration, task state, progress activity and exact notification routing
- 36c42bb — running composer and authority/routing regression checks
- 230bad4 — exact-page confirmation, readiness, paused deadlines and follow-up handoff
- Final integration commit: CI fixes and 3.9.12 identity

## Validation and limits

Repository product/security/version guards and focused Python CI checks run locally. Android compilation, JVM tests, lint and APK assembly run in Mobile CI; Gradle distribution networking is unavailable locally. Early checkpoint CI identified missing Compose imports and a nullable notification state; corrected in final integration.

DEVICE TESTING: USER WILL VERIFY

Background work requires Android 15+, authorized Shizuku, Accessibility and an app that supports isolated displays. Apps already present on display 0, unsupported OEM task layouts, secure surfaces, unsupported text input and cross-app transitions fail closed or require handoff. The existing background text injector accepts printable ASCII into empty editable fields. Process death revokes execution and does not restore autonomous authority. A removed/replaced Android task cannot be resumed. Some policy boundaries must be completed in the original app. Preview is sampled, not continuous provider video. Prepared content is reviewed in the live page rather than copied into a transcript. One saved follow-up is supported; it requires explicit start.

No physical behavior is represented as verified by CI. Release publication must use the successful exact-source CI artifact and existing signer-continuity/protected-environment workflow.
