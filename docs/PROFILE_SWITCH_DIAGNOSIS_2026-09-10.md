# Profile switch and current-profile diagnosis — 2026-09-10

Source inspected: agent/430-profile-rescue at 40e7ab3e2cc85d1ac745ed158c3cc9be94401957, plus previously preserved local integration changes. Published base: v4.2.9 ae52303b834d88e9e40983a7415c1b3bc1558896.

## Device access: NOT CONNECTED

This executor is a remote Linux workspace, not the owner's Windows computer. No adb binary, no /dev/bus/usb, no Windows user mount, no listening local ADB server, and no exposed Cyclone phone/One connector. No physical command, user switch, screenshot, or installed-version check was performed. The exact cause on the owner's installed APK remains unverified. A superuser toast proves permission grant, not execution or completion of am switch-user.

## Confirmed source defects

CycloneProfilesPage.buildProfileClusters unions only saved registry user IDs and app workspace user IDs. An owner with neither can disappear. The default Active tab also excludes a normal foreground task unless it has a matching Layer 2 workspace. The UI has no verified current-human-profile field, so a visible Profile B card is not evidence that Android is currently on B.

ProfileSetupRuntime.currentUserId is the application process user, not a query for Android's active human user. It must not be used as a live current-profile badge. openProfile checks root then validates profile identity, prepares the destination on this candidate branch, and calls am switch-user. Root grant alone cannot establish which of these subsequent steps succeeded. The existing UI says only Opening throughout. The root runner is bounded per command; preparation can involve many such commands. Do not label this a confirmed switch-command failure without device evidence.

## Repair plan

1. Always include the main/owner and process profile in the profile inventory, with no invented alternate registry records.
2. Query Android's actual active user for the current badge; refresh on activity resume. If unavailable, show no verified badge, never infer B from saved selection.
3. Attribute foreground task activity to its actual process profile while retaining exact workspace identity.
4. Expose switch stages and a truthful failure, retain exact ownership checks and verified completion. Do not report success merely because root was granted.
5. Device smoke test still required: capture installed version, current user, safe user inventory, and bounded switch result. No arbitrary root shell exposed to the model.

## Checkpoint status

Earlier pushed candidate 40e7ab3 passed Mobile CI (tests/lint/unsigned assembly): https://github.com/premiumcentraal-boop/Cyclone/actions/runs/34472901345 . This does not validate this new diagnosis or later edits on a device.

The preceding GitHub write was rejected by automatic approval review because of a usage limit. This diagnosis is being committed locally first; no alternate transport will be used to bypass that rejection. Publication has not occurred.

## Source checkpoint after diagnosis

Implemented a deterministic profile presentation policy: owner/process users remain visible without workspaces, saved parent users remain discoverable, and Current requires an Android-reported active user. Foreground runtime ownership contributes to Active for the process profile. The header reports the verified current profile independently of the selected card. Existing activity-resume refresh triggers identity requery. Profile names remain canonical; the owner's wording “Profile 2” was not used to silently rename Profile A or change Android IDs.

Open profile now reports access checks, destination preparation, switch dispatch and verification separately. Duplicate taps are ignored while a switch is in progress. This improves diagnosis; it is NOT proof that the owner's physical switching failure is repaired. Exact ownership/GATE checks remain intact.

Added five ProfilePresentationPolicyTest cases for absent owner records, alternate process, unknown current identity, foreground activity association and nonzero owner. Android JVM execution remains BLOCKED locally: Gradle 8.9 distribution download fails with Network is unreachable. Existing Python CI guard suite: 72 passed. git diff --check passed. No phone testing performed.

Next: expose the USB-connected Windows host/Cyclone One as a callable connection, then read installed version/current user/user inventory and reproduce Open profile while collecting only switch-stage diagnostics. Do not claim root toast establishes switch success. Push local commits when the GitHub usage-limit approval block is resolved; no release was published here.

## Pushed recovery and profile hub follow-up

GitHub access recovered on the next user request. Remote checkpoint 151a73a1dffe98442cab962cf59cdb7f1ee9072a preserves local b1183367, e7a2cfe7 and fb4d3520 together without losing their source or diagnosis.

Profiles now opens All profiles by default, sorts the verified current profile first, exposes the existing pending-request queue with Steer/Stop, and offers New task in current profile. Profile details offer New task only when the selected user is both the process user and Android's verified current user, and no switch is underway. The action opens the existing AI composer; it does not create another task engine or silently retarget another profile. Alternate-profile task creation still requires opening that profile first. Existing Add profile and Manage apps/profile continue through the canonical setup runtime.

Added task-entry policy regression assertions for mismatched profile, unknown current user, stale process and switch-in-progress. Physical profile switching and bootstrap remain unverified. CI must validate this exact remote head before beta distribution.
