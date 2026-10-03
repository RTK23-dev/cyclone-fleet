# Cyclone V5 Alpha 86: Lab tools for agents on the PC

Developer alpha for owner testing. It builds on Alpha 85 (sign-up mapping you can see and stop) and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.86.dev1` (version code 231). No phone code changed since Alpha 85; the phone build carries
  Alpha 85's sign-up fix.
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.86.dev1.exe` (runtime `5.0.0-alpha.86.dev1`).
- **Glass:** `1.0.0-alpha.47` (unchanged).

## What changed

**Cyclone Lab from any agent on the PC.** A test pass on the owner's PC found that `CycloneAgentMCP.exe` offered 27
tools and none of the Lab ones. The Windows package runs the `cyclone_phone_mcp` server, and the Lab tools lived only
in the other, generic server. The server the package runs now offers:
- `phone_lab_missions`: the Lab missions;
- `phone_lab_start`: an experiment of missions × variants × repetitions on one phone;
- `phone_lab_report`: the experiment list, or one experiment's rates, A/B comparisons and every failed run with its
  cause;
- `phone_lab_stop`: stop a running experiment.

Arguments are checked before anything reaches the gateway (mission ids, known variant knobs only, 1–20 repetitions).
`cyclone-agent-mcp verify` in the package lists them too.

Claude Code on the PC: `claude mcp add --scope user cyclone-phone -- "%LOCALAPPDATA%\Cyclone One\CycloneAgentMCP.exe" serve --stdio`.

## Checks

- MCP: `unittest` (191 tests, including the new Lab tool tests); gateway: `pytest`.
- Mobile CI (unit tests and the APK build) on this commit.

Physical phone and Windows PC acceptance: **UNVERIFIED** until tested.
