# Cyclone 4.0.3 — Layer 2 workspaces + Root features

This release finally adds the Layer 2 workspaces, global mutate lock, verified switch, thin round-robin queue and Root features wizards that were requested but missing from v4.0.2. v4.0.2 remains the one-button background install release; that installer is preserved unchanged in this APK.

Settings now has a Root features card beside Background tasks. The five-step wizard checks existing root, links to Shelter / Island / OEM Dual Apps, registers labeled app/profile workspaces with an installed-app picker, tests a switch, and shows the lock owner with pause/release controls. Setup works without root. No Magisk installation or boot patching.

The durable registry holds N display-0 workspaces. PhoneToolExecutor owns the same single global mutation monitor across existing foreground/background paths and Layer 2. A switch invalidates the old lease and observation, launches the selected app/profile, then verifies package and Android user before granting a new generation. Stale jobs, wrong packages, unverified users and nonzero displays fail closed. Pending GATE review blocks switch and queue; approval is never synthesized.

The queue is time-sliced and pull-driven: arm one ephemeral goal per workspace, claim `workspace.next`, observe, execute one decision slice through existing phone tools, then claim next. This does not start multiple autonomous agents or provide simultaneous phone input. Workspaces persist; armed jobs and mutation leases do not survive process restart. True parallel execution is deferred.

Cross-profile launching uses Android's accessible LauncherApps profiles. Root is used only for bounded identity probes, never arbitrary model shell commands. Hidden OEM profiles or ambiguous resumed-activity output remain unavailable. Same-package profiles require root identity verification; non-root distinct-package apps are supported when Android can establish profile scope. Compatibility is not guaranteed for every OEM or clone provider.

Phone API: `workspace.list/register/switch/pause/release/arm/next` and `phone.workspace_switch`. Session Kernel provides `switchWorkspace`. MCP adds `phone_workspace` through the existing trusted gateway. Use `session_id=default-foreground`, display 0; after switch, pass returned `workspaceId` and `workspaceGeneration` inside every `phone_act.params`, with a fresh observation. Existing named Shizuku display sessions retain their identity rules. PC-side source changes require updating the installed gateway/MCP checkout.

Development channel; Android 4.0.3 / versionCode 74. LEGACY_UPDATE_COMPATIBLE_DEV_KEY, with certificate equality checked against v4.0.2. No keystore is committed. The protected Cyclone Mobile Release Signing workflow is not used.

NO phone verification, no Pixel, no USB, no adb smoke tests. Physical Pixel 8 and UI acceptance: UNVERIFIED. Automated validation status and exact source/run provenance are appended by the publishing workflow and included in sidecars.
