# MRZ Studio in Glass Connections

Glass has a dedicated **MRZ Studio · Employee ID** entry in Command Center → Connections. The PC runtime checks for Studio at startup and every 30 seconds, including while Glass is closed. It never launches Studio, its worker or an MCP program just because it found settings.

## Two setup paths

1. Start MRZ Studio, open Glass → Command Center → Connections, and press **Connect MRZ Studio**. The runtime uses Studio's saved Employee ID recipe. For a local program, review the pinned script and press **Run it** once; then choose the tools, call cap and approval rules on the connection card.
2. Use Studio's `/settings/mcp` page to copy its Glass JSON, then paste it into **Program on this PC** in Glass. **Use pasted Glass JSON** fills the same form from discovered settings. A matching pasted recipe is associated with the dedicated entry automatically, and repeating either path reuses the connection and its permissions.

The connector exposes `employee_id_health`, `employee_id_schema`, `employee_id_generate` and `employee_id_status`. The installed Cut 2 fallback uses Node and `employee-id-mcp/dist/server.js`. It remains an external program configured by Studio; it is not bundled into the Cyclone package. The fallback uses Studio on port 8787. Changing Studio's API address alone does not retarget that program: use a connector that supports the new address. The recipe also supports a local HTTP MCP endpoint when Studio is configured for `remote_http`.

The Employee ID contract belongs to the MCP: a photo is required; caller-supplied signature fields are absent; it derives a 420 × 123 signature from `first_name` only. Generation remains governed by the owner's allowed tools and approvals. Finding the API does not mean Photoshop jobs can run. Glass reports API, worker, Photoshop and template readiness separately and identifies dry-run placeholder output.

## Discovery and updates

- The settings source is `%USERPROFILE%\MRZ-Studio-Local\control\mcp-connections.json`. `CYCLONE_MRZ_SETTINGS` can override that path for a different local installation.
- If the file is absent, the runtime asks `http://127.0.0.1:8787/api/mcp-connections`. A corrupt or disabled saved recipe is shown for correction, not overwritten.
- Discovery accepts literal loopback HTTP only. Probes take at most two seconds each, read at most 64 KB, ignore system proxies and refuse redirects.
- The associated connection and recipe fingerprint live in the existing Command Center database. Updating Glass or restarting the runtime preserves the connection and its existing tool permissions. A new Windows profile still needs its own setup and program approval.
- A changed recipe is shown for review. Finish pending jobs, remove the previous connection, then connect the new recipe. Discovered settings never silently replace an approved command.
- An allowed call can recover an errored, already-approved connection when Studio returns and its recipe is unchanged. Recovery lists tools; it does not replay a failed generation request. Normal script hash verification still applies.
- Generated files from a linked stdio connector may be saved from the configured Studio origin's `/api/jobs/{id}/files/{name}` routes. Every redirect is checked against that same restriction. Generic localhost downloads remain refused.

## Acceptance recorded on this PC

Implementation was moved from the earlier Alpha 76 checkpoint onto the published `v5.0.0-alpha.86.dev1` source (Glass `1.0.0-alpha.47`). The production Glass bundle was tested with the real Command Center routes and an isolated database on port 8799. The installed Studio API was found; Connect created an unapproved program connection; the paste action filled the saved recipe. The actual installed Node MCP listed all four tools and completed health and schema calls using isolated test permissions. No badge jobs or production permissions were changed.

At acceptance, Studio's API, Photoshop installation and template were present; the worker heartbeat was offline. Actual badge generation and a phone-to-PC job were therefore not verified. Local discovery does not publish Studio onto the internet; away-from-home operation still depends on Cyclone's separately configured authenticated remote transport and the PC remaining online.

Regression coverage includes offline recovery, startup without an open Glass tab, runtime restart persistence, duplicate prevention, changed/disabled recipes, owner authentication, program and tool approval boundaries, bounded probes, safe output download, system-proxy bypass for local outputs and redirect refusal. Glass's build, all 261 unit tests and architecture guard pass. The gateway suite has 691 passing tests and one unrelated routine scheduling failure on this Windows timezone. That failure was reproduced on the clean Alpha 85 release source (the scheduling implementation and test are unchanged in Alpha 86); see `test_a_routine_prepares_its_next_runs_and_uses_them_while_glass_is_closed`.
