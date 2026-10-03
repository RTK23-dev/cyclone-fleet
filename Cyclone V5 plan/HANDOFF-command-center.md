# Handoff: finishing Cyclone V5, run by run

**For:** the next Claude agent. **From:** the session that built the Command Center (alpha.51–57).
**State at handoff (2026-09-27):**
- `5.0.0-alpha.57.dev1` is published and verified, from commit `86b9fb03` on `claude/cyclone-v5-handoff-review-9qrs40`.
- Versions: Glass `1.0.0-alpha.32`, Android version code 201.
- **Update:** run 2 (`5.0.0-alpha.58.dev1`, API maker and cards; Glass `1.0.0-alpha.33`, code 202) is built. See plan 34
  §10 and `docs/RELEASE_5.0.0-alpha.58.dev1.md`. Next is run 3 (alpha.59, parallel sessions).
  - `GrantStore` also holds `{query, value}`.
  - `task.make` is now `{steps, then}` (read it with `steps.plan_of`).
  - PyYAML is a gateway dependency.
- **Update:** another session shipped `5.0.0-alpha.58.dev2` (code 203), `5.0.0-alpha.59.dev1` (code 204, the app
  dictionary) and `5.0.0-alpha.60.dev1` (code 205, Glass `1.0.0-alpha.36`, the App Manual foundation); see plan 36
  and plan 35's new order.
- **Update:** the owner moved Pages forward. Alpha.61 (`5.0.0-alpha.61.dev1`, Glass `1.0.0-alpha.37`, code 206) is
  the Command Center redesign and C5 Pages; see plan 33 §12.5.
  - Glass has two faces, switched by the top-left logo.
  - Workspace code lives in `apps/glass/src/workspace/`; the pages store is `command/pages.py`.
  - Plan 35 is the source of truth for the order of the next runs (the rest of the App Manual, then parallel sessions).
    The run list below keeps its original numbers.
- **Update:** alpha.62 (`5.0.0-alpha.62.dev1`, Glass `1.0.0-alpha.38`, code 207) is the AI project manager (C4, first
  part; plan 33 §12.6), which the owner asked for.
  - `command/ai.py` holds the OpenRouter key (write-only, in `connections.grants` under `ai:openrouter`), the model
    list, caps, conversations, the fixed tools and proposals.
  - `command/pagetext.py` converts pages to and from Markdown.
  - In Glass, `workspace/aiPanel.ts` and `workspace/aiSettings.ts` never name a provider in code; the name reaches them
    as data (the Glass guard).
  - Tests use a scripted OpenRouter (`tests/test_command_ai.py`); nothing calls the real one in CI.

Read this whole file once. Then work the plan in plan 35, one release per run, the way it is described here.

---

## 1. The owner and how to work with them

- **How the owner asks:** short sentences, such as "Start building alpha 57", "Continue with …", "release when truly
  solid". A request to build means **build the whole run and release it**:
  - code on the phone, the gateway and Glass;
  - tests, CI guards and an end-to-end check;
  - release notes, version bumps and a push;
  - verifying the publish.
  Don't stop halfway to ask. Ask only when a decision truly belongs to the owner (see §11).
- **Owner decisions so far:**
  - Local MCP servers on the PC are allowed, behind a very short, human setup card.
  - Connectors read **and** write, and they talk to external services and bring results back.
  - Owned or authorised accounts only.
  - Shizuku stays the privileged helper: never rebuild Cyclone's own self-granting engine.
- **Replies to the owner:** plain words, and honest.
  - Say what shipped, the commit, what was verified, and what is **not** verified (physical phone and Windows tests
    are always owed).
  - Say what's next, and keep it short.
  - Don't write marketing language, and don't claim "done" before the publish is verified.
- The owner reads on a phone. Put links and the one thing they must do first.

## 2. Read first, in this order

1. `AGENTS.md` (product invariants, ownership, the release lane, validation commands).
2. `Cyclone V5 plan/35-roadmap-to-5.0.md`: **the master plan**, about 13 build runs to 5.0.
3. `Cyclone V5 plan/34-connector-maker.md`: the current workstream. M1 and M2 are done; M3 and M4 are next.
4. `Cyclone V5 plan/33-command-center.md`: the Command Center plan, with §12.1–12.4 describing what was built for
   each release.
5. The latest release notes: `docs/RELEASE_5.0.0-alpha.57.dev1.md` (and 56, 55, 54, 51 for the Command Center).
6. The code you'll touch (§5).

## 3. Rules you never break

These come from `AGENTS.md` and the owner, and CI guards enforce many of them.

- `PhoneToolExecutor` is the only thing that changes the phone.
- Approval boundaries stay for pay, send, delete, permission and sign-in steps. Nothing times out into an approval.
- Task buttons go through Task Kit (`TaskCommands`); surfaces never call an engine directly.
- Never persist or expose passwords, OTPs, API keys, payment data or typed secret values:
  - not in Brain, learning stores, diagnostics, logs, the audit chain, the Command Center database, Glass (after
    typing) or any model;
  - secrets live in the vault (zero-knowledge, the browser only) or in the gateway's DPAPI grant store.
- No generic shell or root access for the model. Local MCP servers run only after the owner approves an exact, pinned
  command. The agent MCP servers (`tools/*`) must never call `/v1/cc/*` (guarded).
- Don't recreate signing keys. The APK signer is always `e78c6e0b32d66da05839243c65e9987ba54722d402543f00408b57b1585dbf60`.
- No model identifiers in commits, PRs, code or docs. Don't open PRs unless the owner asks.
- Develop and push **only** on `claude/cyclone-v5-handoff-review-9qrs40`.
- Commit trailers (exactly these):
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: <your session link>
  ```
- Don't reproduce the safety-stopped fleet report. No bulk third-party sign-ups, SMS farms, CAPTCHA solving,
  fingerprint spoofing or engagement manipulation.

## 4. The release lane: exact mechanics

**What triggers a publish:** `.github/workflows/v5-publish.yml` runs when `release/version.toml` changes on the dev
branch. It builds and smoke-tests Windows, waits for Mobile CI on that commit, signs the APK, zips Glass and
publishes `v<product_version>` with `release-manifest.json`.

**Version bumps for every release:**
1. `release/version.toml`:
   - `product_version`, `python_version`, `components.mobile`, `components.device_gateway` and `components.mcp`:
     all `5.0.0-alpha.N.dev1`;
   - `android_version_code` +1;
   - `components.glass` +1 **only if `apps/glass` changed** (it almost always does);
   - update line 1's comment and `candidate_generation`.
   - `pc_companion` stays `1.6.0-alpha.43`.
2. `apps/mobile/app/build.gradle.kts`: `versionCode` and `versionName`.
3. The three `pyproject.toml` files: `apps/device-gateway`, `tools/codex-phone-mcp`, `tools/cyclone-agent-mcp`.
4. `apps/glass/package.json` and **both** version entries at the top of `apps/glass/package-lock.json`.
5. `docs/RELEASE_<mobile version>.md` (format in §9).

A one-liner that worked:
`sed -i 's/5\.0\.0-alpha\.57\.dev1/5.0.0-alpha.58.dev1/g' release/version.toml tools/*/pyproject.toml apps/device-gateway/pyproject.toml apps/mobile/app/build.gradle.kts`.
Then bump the code, Glass and the lock by hand. Check with `python scripts/ci/release_versions.py --check` and
`python scripts/ci/mobile_product_guard.py`.

**Before pushing:**
- `git status --short`. Check that **every new source file** appears. A `.gitignore` rule (`secrets/`) once hid new
  phone files, alpha.55's first push failed Mobile CI, and a second publish commit was needed. Run
  `git check-ignore -v <file>` on any new file under a suspicious folder.
- `git fetch origin claude/cyclone-v5-handoff-review-9qrs40`, then `git log HEAD..origin/...`. Another session
  (Drive) has pushed to this branch before.
- Check that no workflow run is in progress on the branch (GitHub MCP `actions_list list_workflow_runs` with
  `branch` and `status: in_progress`). Mobile CI cancels in-progress runs per branch, so never push while a publish
  is running.

**After pushing:**
- Schedule a check-in about 15 minutes out (`send_later`), then end your turn.
- When it fires, list the runs for the commit: Mobile CI, Glass CI and "Publish V5 …" must all be success.
- Verify the release:
  ```bash
  B=https://github.com/premiumcentraal-boop/Cyclone/releases/download/v5.0.0-alpha.N.dev1
  curl -sSfL -o release-manifest.json $B/release-manifest.json   # then every file named in .sha256
  python3 -c "import json,hashlib;m=json.load(open('release-manifest.json'));print(m['source_sha']);[print(n, hashlib.sha256(open(n,'rb').read()).hexdigest()==h) for n,h in m['sha256'].items()]"
  /opt/android-sdk/build-tools/34.0.0/apksigner verify --print-certs Cyclone-5.0.0-alpha.N.dev1.apk | grep SHA-256
  ```
- `source_sha` must equal your commit, every file must match its hash, and the signer must be `e78c6e0b…`.
- Doc-only pushes (plans) don't trigger a publish, but don't push them while a publish is running.

## 5. Map of what exists (Command Center and connections)

**Gateway (`apps/device-gateway/cyclone_device_gateway/`):**
- **`command/center.py`: `CommandCenter`.**
  - Storage: one SQLite file (`runtime/command/command.db`, WAL, `isolation_level=None`, one `RLock`) and a
    SHA-256 audit hash chain (`_audit`).
  - Tables: accounts, tasks, routines, runs, approvals. Migrations are `ALTER TABLE ADD COLUMN` guarded by
    `PRAGMA table_info`.
  - The job loop `tick()` runs every 5 s: fire routines, find ready phones, follow runs (`cc_status`), then
    dispatch.
  - Routines: one phone per account at a time (the account lock); missed routine runs are not replayed.
  - Task states: `scheduled, making, waiting_device, running, needs_you, succeeded, failed, cancelled`.
  - A task may carry a `make` step (a connection call before the phone) and a `vault_item_id` (sealed password).
  - Routines hold `make`, `vault_item_id` and `preauth` (0–7 prepared runs through `routine_slot` with reserved
    task ids).
  - Approvals from the phone mirror Owner Moments. Gateway approvals (`run_id = ''`) have kind `spend` (a connection
    call) or `login` ("unlock the vault", never answerable).
- **`command/vault.py`:** ciphertext only. The browser encrypts with WebCrypto.
- **`command/delivery.py`:** sealed delivery.
  - Device keys and trust; `lease` rows hold HPKE envelopes the gateway **cannot** open (no crypto code there;
    guarded).
  - AAD is `{deviceKey, expiresAt, leaseId, place, slot, taskId}` (JSON, sorted keys).
  - Pre-authorised leases submit against reserved slot task ids and expire due + 30 min, at most 8 days ahead.
- **`command/mcp.py`:** a `Session` base with the only four MCP messages (initialize, notifications/initialized,
  tools/list, tools/call; guarded), in three transports:
  - `McpClient`: Streamable HTTP;
  - `LegacySseClient`: HTTP + SSE. Its `close()` shuts the socket down first, otherwise it hangs;
  - `StdioClient` is in `local.py`.
  - Also here: OAuth 2.1 (discovery, DCR, PKCE, resource, refresh), `offers_oauth`, and `fetch_file` (https,
    public hosts only, redirects re-checked).
- **`command/connections.py`: `ConnectionStore`.**
  - Connections: remote (http or sse) and local (stdio). Auth is `none`, `oauth` or `header`. Statuses are `ready`,
    `needs_sign_in`, `needs_key`, `needs_client`, `needs_approval` and `error`.
  - `probe` holds the plain-words steps.
  - `connection_tool`: class `read`, `change` or `sensitive`; allowed; rule `always`, `over_cap` or `cap`; hash;
    approved hash; previous. `classify()`, `tool_hash()`, auto-pairing (`pollTool`).
  - Tools that change are switched off. Sensitive tools are forced to `always`.
  - Calls: `tool_call`, with result bounded to 32 KB and screened, plus artifacts (`artifacts/<sha256>`). Polling
    covers background jobs.
  - `GrantStore` is DPAPI on Windows, memory elsewhere, and holds OAuth tokens, `{header, value}` keys, client
    secrets and `env:<id>` env values.
  - `spawn` and `sleep` are injectable; tests pass `spawn=lambda fn: fn()`.
- **`command/local.py`:** local MCP servers.
  - `parse_config` and `check_launch`: the launcher allowlist, pinning, refused env names and Docker flags, and
    shell-special characters refused.
  - `LocalServers`: its own folder, a minimal env (never the gateway token), a Job Object, a 3-in-10-minutes
    restart limit, a 15-minute idle stop, and a redacted log.
- **`command/api.py`:** every `/v1/cc/*` route needs the bearer (guarded). The **only** exception is
  `create_oauth_callback_router` (`/v1/cc/connections/oauth/callback`).
- **`desktop_runtime/v5_contract.py`:** typed phone ops (`CC_OPS`: `cc.start/status/answer/key/media`) with
  response validators. `cyclone_bridge/protocol.py` lists the allowed ops.
- **Packaging:** new gateway modules imported inside functions must be added to the `hiddenimports` in
  `packaging/pc-companion/pyinstaller/CyclonePCRuntime.spec`.

**Phone (`apps/mobile/app/src/main/java/com/cyclone/mobile/`):**
- `gateway/GatewayV5CommandAdapter.kt`: `cc.*`. Seams for JVM tests: `overlayReady`, `busy`, `start`, `live`,
  `moment`, `send`, `deviceKey`.
- `gateway/CommandMedia.kt`: `cc.media` chunks, SHA-256 check, then the gallery.
- `policy/PublishGate.kt`: Share, Post and Upload become SEND gates during a posting mission.
- `secrets/`: `Hpke.kt`, `DeviceKey.kt`, `SealedDelivery.kt`. The `.gitignore` has a `!` re-include for this
  folder.
- New ops must be added in **three** places: `GatewayProtocol.kt` (the list), `GatewayRuntime.kt` (dispatch) and the
  adapter.

**Glass (`apps/glass/src/`):**
- `pages/commandPage.ts`: the tabs Approvals, Tasks, Routines, Results, Accounts, Vault and Connections.
- `pages/vaultView.ts`, `pages/connectionsView.ts` (the setup card, key and client forms, tool groups, Try it, and
  the make-first editor `createMakeEditor`), `services/command.ts` (parsers, API, `LOCAL_CARD`), `services/vault.ts`,
  `services/delivery.ts`, `services/hpke.ts`, `styles/command.css`.
- Glass rules (`scripts/ci/glass_guard.py`): no localStorage, no runtime dependencies, no innerHTML or eval, and no
  model calls. Use `el()` and `setChildren()` from `ui/dom.js` and components from `ui/components.js` (`card`,
  `chip`, `actionButton`, `segmented`, `emptyState`). CSS uses design tokens (`var(--space-3)`, `--accent`, and so
  on).

**Guards:** `scripts/ci/tests/test_command_center_guard.py` (unittest style; CI runs `unittest discover`). Add a
guard for every new safety rule, asserting on exact source strings, and keep them honest.

## 6. How to build one run (the rhythm that worked)

1. `git fetch`, then fast-forward only. Read the plan section for the run. Make 4 tasks: gateway, phone, Glass, and
   release (guards, e2e, docs, versions).
2. **Gateway first, test first against real local servers.**
   - Fakes are real HTTP servers in `tests/` (`fake_mcp.py`: OAuth, key-only, older SSE, no registration, a polled
     video job), plus `tests/fake_stdio_mcp.py`.
   - Use `CommandCenter(tmp_path/"cc.db", contract, devices, clock=Clock(), connections={"spawn": lambda fn: fn(),
     "sleep": lambda s: None})`. The contract is a small fake class with the `cc_*` methods.
3. **Phone:**
   - Write the adapter and ops with seams; JVM tests in `app/src/test/...`.
   - Run targeted tests with `./gradlew :app:testDebugUnitTest --tests '*Name*' -q --offline` (in the background:
     it takes a few minutes), then the full suite.
   - Count results from `app/build/test-results/testDebugUnitTest/TEST-*.xml` (last full run: 2002 tests, 0
     failures).
4. **Glass:**
   - Parsers are defensive (`oneOf`, `str`, `num`, `obj`).
   - Forms are drawn once, so polling never wipes typing. A list is not redrawn while an input, select or textarea
     inside it has focus (a focused **button** doesn't count; that bug is fixed).
   - Tests: `npm test` runs `node --test` on `tests/*.test.mjs` with `helpers/mini-dom.mjs` and
     `helpers/fakeGateway.mjs`.
   - Typecheck with `npx tsc --noEmit -p .`; then `npm run build` and `python scripts/ci/glass_guard.py`.
5. **Guards:** add them, then run `python -m unittest discover -s scripts/ci/tests`.
6. **Full suites:**
   - `python -m pytest apps/device-gateway/tests -q -x -p no:cacheprovider`. pyproject already sets `-q`, so
     there's no summary line: trust the exit code, or drop `-q` to see counts.
   - `python -m unittest discover -s tools/codex-phone-mcp/tests` and
     `python -m pytest scripts/ci/tests/test_pc_web_only.py -q`.
7. **End to end in Chromium** (every release did this and it caught real bugs; see §7).
8. **Docs:** the release notes, the plan's "as built" section, plan 35's row marked built, and the
   `Cyclone V5 plan/README.md` index row.
9. **Versions, pre-push checks, commit, push, check-in, verify** (§4). Then report to the owner (§1).

## 7. The end-to-end harness (copy this pattern)

**Server script** (keep it in your scratchpad, not the repo):
```python
os.environ.update(CYCLONE_DEVICE_GATEWAY_TOKEN="tok_visualcheck_0123456789abcdef", CYCLONE_DESKTOP_PAIRING_BOOTSTRAP="1",
                  CYCLONE_DEVICE_GATEWAY_RUNTIME=RT, CYCLONE_GLASS_DIST="/home/user/Cyclone/apps/glass/dist", CYCLONE_DEVICE_GATEWAY_PORT="8806")
from cyclone_device_gateway.cli import build_serve_app
from cyclone_device_gateway.config import Settings
app = build_serve_app(Settings.from_env()); runtime = app.state.desktop_runtime
runtime.command._contract = ScriptedPhone()          # cc_start/cc_status/cc_answer/cc_key/cc_media
runtime.command._devices = lambda: [{"deviceId": "phone-a", "paired": True, "state": "ready", "name": "Pixel 8 (scripted)"}]
runtime.command._clock = lambda: real_ms() + offset_from_file()   # to jump to a routine's due time
runtime.command._tick_seconds = 1.0
uvicorn.run(app, host="127.0.0.1", port=8806)
```
- The scripted phone can hold a real P-256 key and open HPKE with the Python `cryptography` package; see how C2 did
  it in `docs/RELEASE_5.0.0-alpha.55.dev1.md`.
- Point it at the fakes in `tests/fake_mcp.py` by adding `apps/device-gateway/tests` to `sys.path`.
- Build Glass first (`npm run build`).

**Playwright script (.mjs):**
- `import { chromium } from "/home/user/Cyclone/apps/glass/node_modules/playwright/index.mjs"`, and launch with
  `executablePath: "/opt/pw-browsers/chromium"`. Never run `playwright install`.
- Get a launch code with `POST /v1/glass/launch-code` (with the bearer), then open `/glass/#code=<code>`. Click
  "Got it" if it's shown.
- Navigate with `location.hash = "#/command/<tab>"`. Segmented controls are `role="tab"`, not buttons. Inputs are
  found by `aria-label` (`getByLabel`).
- Take screenshots into the scratchpad and **look at them** (Read the PNG). Visual bugs found this way were fixed.
- There are no real paired phones in Glass, so a phone picker shows none: use "Any ready phone", or create through
  the API.
- **Canary scan at the end:** put unique canary strings in passwords, keys and env values, then scan every file under
  the runtime folder and the server log. There must be zero hits. Report this in the release notes.

**Process-management traps** (they killed the shell several times):
- `pkill -f <pattern>` or `pgrep -f` inside a Bash command whose own text contains the pattern kills your own
  shell.
- Instead, write a `start.sh` in one call and run it in another. Inside it, use
  `for pid in $(pgrep -f "python .*a57/serve"); do kill $pid; done`.
- Run long servers with `nohup … &` from that script.

## 8. Gotchas and insights (hard-won)

**Gateway and Python:**
- **The SQLite clock is fixed in tests,** so `ORDER BY created_at` ties. Add `, rowid DESC`.
- **Inline `spawn` in tests runs calls synchronously under the Command Center's `RLock`.** It works because the lock
  is reentrant; in production calls run on threads and take the lock when they finish. When a call finishes a task
  (`_make_finished`), re-check the task's state after `connections.call()` returns.
- **`tool_hash` and `classify`:**
  - Descriptions can only *raise* risk.
  - Match word stems ("sends" must count as send).
  - `destructiveHint`, or a sensitive name word, means `sensitive`.
- **Older SSE:** a GET stream announces the POST address (it must be the same host), and answers come on the stream.
  `close()` must `sock.shutdown()` first, or it deadlocks against the reader thread.
- **stdio:** one JSON-RPC message per line. Requests *from* the server (sampling, roots) get error `-32601`. stderr
  goes to the redacted log.
- **Windows `.cmd` launchers run through cmd.exe.** That's why `& | < > ^ % "` and new lines are refused in
  arguments.
- **An empty env placeholder in a README config is not a saved key.** Store only non-empty values.
- **`reject_secret_payload` / `INLINE_SECRET`** (`v5_contract.py`) are the shared secret screens; reuse them.
- **Pasted `.cmd` / `.exe` / paths as a command are refused.** Use the bare launcher name.

**Glass:**
- `select.value` doesn't default to the first option in mini-dom (a real browser does). Set it explicitly after
  filling options.
- **mini-dom quirks:**
  - `input[type=checkbox]` selectors don't work, so filter by `.type`;
  - there's no `prepend`, `firstChild` or `contains` on elements (guard with `?.`);
  - a test that throws leaves timers running and hangs node, so always use `try/finally { page.destroy() }`.
- Glass shows sign-in pages with `window.open(url, "_blank", "noopener,noreferrer")` and never sees tokens.

**Phone:**
- New files under `secrets/` were ignored by `.gitignore`. Check with `git status --ignored`.
- The bridge line limit is 1 MiB. Media goes in 256 KB chunks (base64 about 350 KB).

**Honesty in notes:**
- State what was **not** tested. For example: Higgsfield's real tool names are unknown (it was tested with a
  stand-in); Client ID Metadata Documents are unsupported (a loopback PC can't host the document); local programs
  have only run on Linux.

## 9. Formats

- **Release notes** (`docs/RELEASE_5.0.0-alpha.N.dev1.md`):
  - A title ("Cyclone V5 Alpha N: …"), versions, and one paragraph on what it is.
  - "What changed": numbered, owner-facing, with where to click in Glass.
  - "Safety" and "Validation and limits": tests per layer with counts, the end to end, the canary scan, then Limits
    with **Physical: UNVERIFIED** first.
- **Plan "as built" section:** terse and technical, naming files and functions.
- **Commits:**
  - The release commit is "Release 5.0.0-alpha.N.dev1 (code): <title>", with a short bullet body and the trailers.
  - Doc commits are short too.

## 10. The plan: what's next, run by run

From plan 35 (row 1 is done). Each row is one release. Refine each run from its plan section before building.

- **Run 2, alpha.58: API maker and cards** (plan 34 M3 + M4; see §4–6 there).
  - `command/openapi.py`: import OpenAPI 3 / Swagger 2 from a URL or file.
    - Each operation becomes a tool, with parameters and body turned into an `inputSchema`.
    - Security schemes (apiKey header or query, bearer, basic, OAuth2 authorization code) reuse `GrantStore` and the
      C3 OAuth flow.
    - Calls go only to the spec's `servers` hosts, pinned (https, public; reuse `mcp.check_url` / `public_host`).
    - GET and HEAD are reads, others are changes; the owner can override per tool. The gateway sends the HTTP
      itself; no code is generated.
    - Model it as a new connection kind `api` in `ConnectionStore._session` (a small `Session`-like adapter whose
      `list_tools` / `call_tool` map to operations), so rules, pinning, results and artifacts all apply unchanged.
  - **Result chaining:**
    - A task or routine can hold several steps: `steps: [{connectionId, tool, arguments}]`, then the phone goal.
    - Arguments may use `{step1.field.path}`, resolved from the previous call's `result` (already stored, bounded
      and screened).
    - Refuse unknown paths, and never let a result become instructions: when a result goes into a phone goal, wrap
      it as quoted, untrusted data.
  - **Connector cards:** export as JSON (address, config or spec, auth *method*, tools, classes, rules, pairings,
    hash; **no secrets**). Import, and ship curated cards in Glass. A local-server card still shows the setup card.
  - **Glass:** an "API description" mode in Add connection, a steps editor (extend `createMakeEditor` into a list),
    and export/import buttons.
  - **Exit:** a routine reads from an API, a phone task uses the result, and a POST asks first; a card exported
    here works after import and sign-in.
- **Run 3, alpha.59: parallel sessions** (plan 26 §6).
  - `MindMissions` becomes a host of missions, each with its own worker, inbox, task card, plane session and
    leases: up to 2 in the background plus the owner's screen.
  - Also: per-session notifications and pill, and a Lab concurrency suite. The Command Center then dispatches more
    than one task per phone (`busy` becomes a count).
  - The biggest phone run. Keep `PhoneToolExecutor` as the only way to change the phone, and Task Kit per session.
- **Run 4, alpha.60: fleet health and alerts** (plan 33 §10–11).
  - A phone heartbeat op (battery, heat, storage, network, versions).
  - Quarantine after 3 infrastructure failures (owner releases it), plus "behind on updates".
  - Alerts: offline over 10 minutes, success under 80%, a login failing twice, a cap reached. Delivered to the
    phone (a notification op) or by email.
  - Metrics.
- **Run 5, alpha.61: the coordinator, C4** (plan 33 §7–8).
  - A runtime service on the owner's OpenRouter key with fixed JSON tools only: accounts, phones, recipes, tasks,
    routines, runs, artifacts, connections.
  - It **cannot** read vault values, approve, add connections, or run a shell.
  - Budgets, a daily or weekly report page, the Lab coordinator suite, and Cyclone as an MCP server behind the same
    approvals.
  - Treat all run and result text as untrusted, quoted data.
- **Run 6, alpha.62: smarter routines.** Monthly and "first Monday" schedules; triggers (notification, email,
  webhook, another task's result); batching identical approvals; per-kind approval timeouts that pause and never
  auto-approve.
- **Run 7, alpha.63: the vault, complete** (plan 33 §4.3–4.5).
  - Passkey unlock: serve Glass at `http://localhost` (WebAuthn allows localhost, not 127.0.0.1).
  - "Remember on this phone" with remote revoke; SMS and email codes read on the phone.
  - A rotation recipe with 7-day rollback; sign-up for owned accounts with the new password sealed back; Argon2id
    export.
- **Run 8, alpha.64:** the mission desk (plan 21) and finishing Drive (the Lab voice suite, JEV promotion if the car
  test earned it; plan 32).
- **Run 9, alpha.65: Pages, C5.** The owner must decide first: an in-house block editor (recommended, keeps the Glass
  guard) or a vetted dependency.
- **Runs 10–11: hosted, C6.** Only if the owner wants it and decides where it runs and who pays.
- **Runs 12–13: 5.0.0-rc.1, then 5.0.0 / Glass 1.0.** A full physical pass; plan 9's exit criteria; a signed
  Windows installer (a certificate from the owner); a stable channel.

## 11. Owed by the owner (remind them, don't block on it)

- **Windows:** the setup `.exe`, `cyclone update`, DPAPI grants, and local MCP programs (`npx.cmd`, the Job Object).
- **A real phone:**
  - C0: a routine on two phones;
  - C2: a vault login on a phone that never had it;
  - C3: a Higgsfield video posted behind the Share approval.
- **Send Higgsfield's tool list after signing in,** so its curated card can be verified.
- **The Drive car test.**
- **Open decisions:** the Pages editor (run 9), hosting (runs 10–11), the Windows signing certificate and the stable
  channel (run 12).

## 12. First moves for you

1. Read §2's files.
2. Run the checks from §6 steps 6 and 4 to confirm a green baseline on the current HEAD.
3. When the owner says go, build run 2 (alpha.58) exactly as in §6, release as in §4, and report as in §1.
