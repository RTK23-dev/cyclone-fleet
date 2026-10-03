# Live Phone sprint

Base: Cyclone One 1.1.2 (`66b6240`) with the exact Mobile 4.2.2 app subtree (`5042e4c`). Shared PC gateway/MCP remains the released One implementation; no wholesale conflicting cross-release merge.

ON PC AI keeps native CycloneAgentMCP for Codex/Grok/Claude/Cursor. LIVE PHONE is a separate typed cloud-connector facade, pinned to default-foreground/display 0. BACKGROUND PHONE retains named VD and time-sliced Layer 2 identities. PhoneToolExecutor remains sole Android mutation authority.

Checkpoint 1: bounded command contract; latest released app baseline. No release or device acceptance claimed. Publication remains disabled.

## Checkpoints pushed

- `9c3697c` foundation, released One + Mobile baseline.
- `66edf3c` structured observation and fresh bounded screenshots.
- `7da6161` typed actions on the existing verified MCP path.
- `ec28072` One and Mobile mode separation, Pause/Stop.
- `9e75a2e` authenticated private Windows pipe, owned runtime supervision, bundled CLI.
- `6a20ebc` contract tests and CI gates.
- `5e3a1ff` preserve native screenshot behavior; Windows IPC restart integration test.

## Operator use

Enable LIVE PHONE in Cyclone One's Connections page. Android's PC gateway must be paired and phone control ready. The current installed One release does not contain this new adapter; use the green sprint CI installer plus matching sprint APK before acceptance.

The PC connector invokes only `CycloneLivePhone.exe <operation> --json`. Start with `devices`, select its exact device ID, then `observe --device ID`. The response contains structured UI, `observation_id`, and `screenshot_path`. Have the PC connector read that local image. `locate --device ID --goal "Chrome search box"` returns current element IDs and a new observation ID.

For example: `type --device ID --element ELEMENT --observation-id OBSERVATION --goal "Search NS train news" --text "NS train news" --user-authorized --json`. This uses Android's existing type authorization and GATE. `tap`, `long-press`, `clear-text`, `scroll`, `swipe`, `back`, `home`, and `open-app --package com.android.chrome` use the same bounded contract. Every mutation requires a fresh observation ID (30-second expiry), uses the existing verified PhoneTools action, and returns a new structured observation and screenshot. Uncertain mutations are never replayed.

`swipe` deliberately means semantic forward/backward scroll; arbitrary coordinates remain outside the frozen MCP contract. This cannot perform arbitrary gesture-only interactions on surfaces with no semantic scroll route.

The CLI has no shell/exec/ADB command, custom endpoint, token, or execution-plane option. It sends bounded JSON bytes (not pickle) to a stable user-scoped authenticated Windows named pipe. One owns the broker inside CyclonePCRuntime, the changing gateway credentials, and runtime supervision. Pipe authentication uses a separate DPAPI-protected random secret. Restart invalidates cached observation/element authority; observe again without changing client configuration.

ON PC AI retains CycloneAgentMCP and its native client configuration. This mode does not establish a cloud Remote MCP tunnel and does not change Codex's path. The caller still uses their PC connector to invoke the typed executable; connector-level approvals remain that connector's responsibility.

## Validation and remaining acceptance

Local: 157 native MCP tests passed (including 11 Live Phone tests); 64 generic adapter tests passed; 83 PC UI tests and production TypeScript/Vite build passed. Gateway suite and repository guards checked separately. Windows private-pipe restart test runs on Windows CI and skips on Linux. No physical phone acceptance is implied by these tests.

Required actual-device checks remain: Chrome NS news search, Play Store Snapchat installation, Settings SIM information, supplied signup fields, and runtime restart through the typed adapter. Never invent signup details or claim a store installation succeeded from a transport response. SIM information and typed credentials must not be copied into this handoff or diagnostics. No release tag is created; publication remains false and baseline release tags stay unchanged.

## Verified build handoff

- Final implementation commit: `c494d86eee01baae07800d72291a662394cdcbc1`.
- Mobile CI **34299766570** passed on that exact commit: repository guards, gateway/MCP contracts, Android JVM tests, lint, assembly and candidate upload. https://github.com/premiumcentraal-boop/Cyclone/actions/runs/34299766570
- Windows CI **34299725847** passed on `bda79a24b0db44d58bfeb6f38c908a1c82e3058a`: gateway/MCP contracts, 158 native MCP tests including actual Windows named-pipe restart/DPAPI integration, frontend tests/build, frozen adapter/runtime builds, NSIS installer and candidate upload. https://github.com/premiumcentraal-boop/Cyclone/actions/runs/34299725847
- The sole implementation change after the green Windows source makes an Android gateway function internal; PC source is identical.
- All six requested checkpoints and subsequent fixes were pushed immediately. Publication remains disabled; no release tags were changed.

The PC connector was reachable and read-only inspection confirmed the installed One has no CycloneLivePhone.exe yet. No phone mutation or acceptance scenario was attempted through raw ADB. Install the sprint Windows candidate and a properly versioned/signed matching mobile candidate before physical acceptance. The CI APK is unsigned and retains baseline 4.2.2/code 83; do not present it as a new published update.

Remaining acceptance: Chrome search, Snapchat installation/signup using user-supplied fields, Settings SIM information, actual One/runtime restart while attached to the phone, and native Codex on the real PC. Windows IPC restart passed in CI; this is not physical-phone restart acceptance. Pause/Stop blocks subsequent Live Phone requests and invalidates cached observations; it cannot undo an action already dispatched. Swipe remains semantic scroll rather than an arbitrary gesture.
