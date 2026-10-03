# 34 — Connector maker: connect any MCP, yourself

**Status:** M1 + M2 built in **alpha.57** (§9); M3 + M4 next (alpha.58). Builds on plan 33 C3 (alpha.56).

**The owner's ask:**
> "A self serve MCP connector maker to automatically connect custom MCPs." Local MCP servers on the PC are allowed,
> "make it clear in setup what this means, very short and human". Read and write. "It needs to be able to send
> requests to external services and get results back."

**In one sentence:** you paste a server address, a server config, or an API description, and Cyclone works out the
rest: how to reach it, how to sign in, which tools it has and which of them change things. You confirm, and tasks and
routines can use it to read and write in outside services and bring the results back.

---

## 0. Decisions (owner, 2026-09-27)

- **Local MCP servers on the PC are allowed.** Setup says plainly what that means (§3.1).
- **Read and write.** Connectors may both read and change things in outside services. Reading tools can be allowed in
  one click. Tools that send, post, delete, pay or change permissions still wait for your OK. This is the same
  approval boundary as everywhere else in Cyclone.
- **Outside services, both ways.** Connectors send requests to external services and bring the results back to the
  Command Center: text, data and files, as artifacts.

## 1. What the owner gets

- **Add a connection** takes any of three things:
  - a **server address** (`https://…/mcp`);
  - a **server config** copied from a README (the usual `mcpServers` JSON: `npx …`, `uvx …`, `docker …`);
  - an **API description** (an OpenAPI/Swagger URL or file) for services that have no MCP server.
- **Cyclone checks it step by step** and says each result in plain words: "Reached it", "It wants you to sign in",
  "It wants an API key in X-Api-Key", "12 tools: 8 read, 4 change things".
- **One screen to confirm:**
  - the tools, grouped as **Reads** and **Changes things**;
  - **Allow all reads** in one click;
  - changes are allowed per tool, each with its rule (ask every time, ask over the cap, or no asking up to the cap);
  - sends, deletes, payments and permission changes always ask;
  - a **Try it** button that runs one reading tool and shows the real answer.
- **Use it anywhere:** in the "First make or fetch with a connection" step of tasks and routines, and later from the
  coordinator (C4). The coordinator may suggest a connection but never add one.

## 2. Remote servers: automatic connect (M1)

- **Transport:** Streamable HTTP first, then the older HTTP + SSE transport.
- **Sign-in, chosen from what the server says:**
  - OAuth with self-registration (built in C3);
  - OAuth with a Client ID Metadata Document (the newer MCP auth rule; Cyclone hosts its document on the loopback
    gateway);
  - OAuth with a client ID and secret you paste, for servers that don't allow self-registration;
  - an **API key or custom header** (`Authorization: Bearer …`, `X-Api-Key: …`);
  - none.
- **Where keys live:** keys, headers and client secrets are sealed in the same DPAPI store as OAuth tokens (C3). They
  never go in the database, Glass after you type them, the audit, logs or a model.
- **Tool classes:** from the tool's own hints (`readOnlyHint`, `destructiveHint`), then its name and description
  words (delete, remove, send, post, publish, pay, transfer, grant, share). Unknown means *changes things*.
- **Per-tool rules:** a new `connection_tool` table (connection, tool, allowed, rule, class, pinned hash) replaces the
  per-connection approval rule. The per-connection daily cap stays as a total.
- **Auto-pairing:** a tool that returns a job id is paired with a reading tool that takes one, so background jobs
  (Higgsfield) are checked without choosing by hand.
- **Pinning:** the approved tool list and each tool's description and schema are hashed. If the server adds or changes
  a tool, that tool is switched off until you look at the change and approve it again. This guards against a server
  quietly swapping what a tool does.

## 3. Local servers on the PC (M2)

### 3.1 What setup says

Before the first run, one card, no scrolling:

> **Run this program on your PC?**
>
> `npx @modelcontextprotocol/server-filesystem@2.1.0`
>
> It works like any app you install: it can **read and change files** on this PC and **use the internet**.
> Cyclone runs only this exact version and stops it when Cyclone stops.
>
> Only add programs you trust.
>
> **[Run it]  [Cancel]**

- If the command or version changes later, the same card shows again with the change highlighted.
- **Keys:** the config's secret values (API keys in `env`) are asked for separately on a masked field. They go into
  the DPAPI store, never into the saved config.

### 3.2 How it runs

- **Launchers:** `npx`, `uvx`, `docker run`, and `node` or `python` with a file path. Any other launcher is refused.
- **No shell:** the command is an argument list only, so `&&`, pipes and redirects are refused.
- **Pinned version required:**
  - `pkg@1.2.3` for npm;
  - `pkg==1.2.3` for Python;
  - an image digest, or a tag you confirm, for Docker.
  - The approved command is stored with its hash.
- **Process:**
  - The program is started by the gateway with its own working folder (`runtime/connectors/<id>/`).
  - It runs inside a Windows Job Object, so it closes with the runtime.
  - It is restarted at most 3 times in 10 minutes.
  - stdout carries MCP messages only; stderr is kept as a redacted log with a size limit.
- **Network:** it may reach external services. That is what it is for, and the card says so.
- **Who can add or launch one:** only you, from Glass. The model, the agent MCP servers and the coordinator can never
  add, change or launch one. CI guards this, as it does for `/v1/cc` today.
- **Why this doesn't break "no generic shell for the model":**
  - the model never supplies a command;
  - a program runs only after the owner's explicit approval of that exact, pinned command;
  - the program is then used only through its MCP tools, under the same allowlist, caps and approvals.

## 4. APIs without an MCP server: the maker (M3)

- **Import:** an OpenAPI 3 / Swagger 2 URL or file. Each operation becomes a tool, with its parameters and body as
  the tool's schema. You tick which to include.
- **Declarative:** the gateway sends the HTTP request itself. No code is generated or run.
- **Classes:** GET and HEAD are *reads*; POST, PUT, PATCH and DELETE *change things*. You can override per tool, for
  example a POST search that only reads.
- **Auth:** from the spec's security schemes: API key (header or query), bearer, basic, or OAuth2 authorization code
  (the C3 flow).
- **Results:**
  - JSON answers are kept as the call's result, shown in Glass and available to the next step;
  - files (media, PDF, CSV) become artifacts.
- **Where requests go:** only the spec's own `servers` hosts, pinned, over https, never a private network address.
  This uses the same checks as artifact downloads.

## 5. Results back from outside services

- **Every call returns:** its text and structured result, kept on the call (bounded, secret-shaped values hidden), and
  any files as artifacts.
- **Chaining:**
  - A step's result can fill the next step's inputs, with `{step.field}` placeholders checked against the result.
  - Example: "fetch today's orders from the shop API, then have the phone reply to each order message".
- **Treated as outside content:** results and tool descriptions come from outside Cyclone. When a model sees them
  (the phone's Mind in a goal, or the coordinator in C4), they are framed as quoted, untrusted content, as run text is
  today. They never become instructions or approvals.

## 6. Connector cards (M4)

- **Export** any working connector as a card: the address, config or spec, the sign-in *method*, tools, classes,
  rules, pairings and the pinned hash. A card holds no keys or tokens.
- **Import** a card, sign in or paste the key, and it's ready. A local-server card still shows the §3.1 card.
- **Curated cards** ship with Glass: Higgsfield (after a live check), plus a few common services with reading tools
  pre-selected.

## 7. Safety summary (CI-guarded where marked)

- Nothing is enabled without your click. Sends, deletes, payments and permission changes always ask. *(guard)*
- Keys and tokens only in the DPAPI store. *(guard)*
- Local servers:
  - only you can add or launch them *(guard)*;
  - allow-listed launchers, argument lists, pinned versions, and re-approval on any change *(guard)*.
- Changed tools are switched off until re-approved.
- Requests from API connectors go only to the spec's hosts, and never to private addresses.
- Outside results are data, never instructions.

## 8. Releases

| Release | Contents | Exit criteria |
|---|---|---|
| **M1: Connect any remote MCP** | Probe steps, SSE fallback, API key/header, manual OAuth client, Client ID Metadata Documents, per-tool classes and rules, Allow all reads, auto-pairing, pinning, Try it | Five servers with different sign-ins connect by pasting the address; a changed tool is switched off until re-approved |
| **M2: Local servers on the PC** | Config import, the setup card, launchers, pinning, Job Object lifecycle, keys in DPAPI, restart limits, redacted logs | A pinned filesystem server and one internet-using server run from an approved config on Windows; a changed command asks again |
| **M3: API maker** | OpenAPI import, declarative calls, auth schemes, result chaining | A routine reads from an API, a phone task uses the result, and a POST asks first |
| **M4: Cards** | Export, import, curated cards | A card exported on one PC works on another after sign-in |

M1 and M2 can ship together as one milestone. Physical acceptance (Windows, real servers) is stated as owed until the
owner tests it.

## 9. M1 + M2 as built (alpha.57)

- **Transports** (`command/mcp.py`): a shared `Session` (the same four messages) over Streamable HTTP
  (`McpClient`), the older HTTP + SSE (`LegacySseClient`: a GET stream, a same-host POST address) and stdio
  (`command/local.py`, `StdioClient`).
  - A 400/404/405/406/415 from Streamable HTTP falls back to SSE.
- **Sign-in:**
  - **Detection:** a 401 plus protected-resource or authorization-server metadata means OAuth (`offers_oauth`);
    otherwise it wants a key.
  - **Keys:** `set_key` stores `{header, value}` in the grant store.
  - **Pasted client:** `set_client` stores a manual client; `needs_client` is raised when there is no
    registration endpoint, and Glass shows the redirect address.
  - **Not done:** Client ID Metadata Documents, because a loopback PC can't host a document the service can fetch.
- **Tools:** the `connection_tool` table (class, allowed, rule, hash, approved hash, previous).
  - **Class:** `classify()` uses `destructiveHint`, then sensitive name words, then `readOnlyHint` or a read verb.
    A description can only raise the risk.
  - **Rules:** sensitive tools are forced to `always`; reads default to `cap`; changes default to the
    connection's rule.
  - **Pinning:** `tool_hash()` covers the name, description, input schema and annotations. A change sets
    `allowed = 0` and keeps `previous`.
  - **Pairing:** a change tool is paired with a read that has a required `*_id` field.
  - **Results:** calls keep `result`, bounded to 32 KB and screened, so the next step can use it in M3.
- **Local servers** (`command/local.py`):
  - config parsing and the launcher allowlist, pinning rules, refused env names and Docker flags;
  - the argument list with shell-special characters refused;
  - a minimal env without the gateway token;
  - its own folder, a Windows Job Object, a 3-in-10-minutes restart limit, a 15-minute idle stop, and a redacted
    stderr log.
  - **Approval:** the connection holds the launch (without env values) and runs only when `approvedHash == hash`.
    The setup card is `LOCAL_CARD` in Glass.

## 10. M3 + M4 as built (alpha.58)

- **API connectors** (`command/openapi.py`), a connection kind `api`:
  - **Reading:** `load_text` (JSON, or YAML via a `SafeLoader` subclass that refuses aliases; 5 MB), then
    `normalize` (OpenAPI 3.x / Swagger 2) keeps a small normalised copy in `connection.api`:
    - servers (variables filled, relative resolved against the spec URL, or the owner's `baseUrl`), checked with
      `mcp.check_url`;
    - one sign-in scheme (`header`/`query`/`bearer`/`basic`/`oauth2` authorization code), the first supported one the
      requirements name;
    - operations, each with `inputs` (`arg`, wire `name`, `in` path/query/header/body, required, schema);
    - an object body is flattened into fields (`body_x` on a clash), any other body is one `body` input;
    - form bodies, cookie/multipart/file operations skipped with a reason; `$ref` local only, 20 hops; descriptions
      screened with `hide_secrets`.
  - **Tools:** `tools(api)` gives MCP-shaped tools, so pinning (`tool_hash`), rules, results and artifacts are
    unchanged.
  - **Classes:** `openapi.classify(op)`: DELETE sensitive; GET/HEAD read unless the first name word is a sensitive
    verb; others change, sensitive on any sensitive word.
  - **Calls:** `ApiSession` has the Session shape (`list_tools` from the kept copy, `call_tool`).
    - `build()` types and checks arguments: unknown/missing inputs, enums, numbers, JSON; path values
      `quote(safe="")` with `.`/`..` refused; header values one line; the final URL must stay under `base`.
    - `_sign()` reads the grant at call time: `{header, value}`, `{query, value}` or an OAuth token via
      `ConnectionStore._token`.
    - `request()` resolves the host, requires every address public (or loopback when the base is), and connects
      `_PinnedHTTPS`/`_PinnedHTTP` to that address with SNI and certificate checks for the name. No redirects; 25 MB
      answers.
    - `_result()`: JSON becomes `structuredContent` (a list is kept under `items`); PDF/CSV/media become a resource
      blob, so `_keep` makes an artifact (`FILE_TYPES`); 4xx is `isError` with a plain sentence; 401 raises
      `NeedsSignIn`, so the status turns `needs_key` or `needs_sign_in` by auth.
  - **Store:** `ConnectionStore.add_api` / `_insert_api` / `_refresh_api` (no network: steps say what it needs).
    - `set_key` also takes `{query, value}` (API only).
    - `_api_oauth` builds the OAuth server from the description: no discovery, no DCR (so `needs_client`), and no
      resource indicator (`mcp.authorize_url` / `token_request` omit an empty `resource`).
    - Links in API results are not downloaded unless it is a final `post` step (`_wants_file`).
    - API tools are never auto-paired, and `_poll` stops on a `FINISHED` status.
  - **Owner override:** `connection_tool.override` via `settings({classes})`. `_cls()` is the effective class; a
    sensitive base class is never lowered. A changed tool clears its override.
- **Chaining** (`command/steps.py`, `center.py`):
  - `task.make` / `routine.make` store `{steps, then}`. `plan_of()` reads C3 one-step rows, and `public()` keeps the
    first step's fields at the top for C3 readers.
  - `task.step_at` is the next step; the migration sets it to 1 for C3 tasks that already made their file.
    `tool_call.step` records the step.
  - `_plan_spec` accepts `make` (C3) or `steps` + `then` (`post`/`keep`/`phone`), 1–5 steps.
    `check_arguments` allows only earlier-step refs, and `_check_goal` allows goal refs only when results go to a
    phone.
  - `_start_make` fills arguments with `chain.fill` from the done calls' kept results (a whole-value placeholder keeps
    its type) and calls with `step=`.
  - `_make_finished` advances `step_at`, or finishes:
    - `post` needs a media artifact;
    - `keep` succeeds with or without a file;
    - `phone` schedules the phone.
  - `_goal_with_results` → `chain.quote_for_phone`: `‹data N›` in the goal, then `DATA_HEADER` and a
    `<<<DATA … DATA>>>` block of one-line JSON values; the last result is included when the goal names none. A goal
    over `PHONE_GOAL` (2,000) fails the task.
- **Cards** (`ConnectionStore.card` / `import_card` / `_apply_card`; routes `GET /v1/cc/connections/{id}/card`,
  `POST /v1/cc/connections/import`):
  - **Export:** `{cyclone: "connector-card", version: 1, name, kind, remote|local|api, signIn{method, header, query},
    tools[{name, hash (approved), class, allowed, rule}], dailyCap, approval, pairings, hash}`.
    - `card_hash` is SHA-256 of the canonical JSON.
    - The API part is `to_openapi(api)`, re-read by `normalize` on import; the round trip keeps tool hashes.
    - Local env values are exported empty.
  - **Import:**
    - checks keys, version and the hash (optional, for curated cards);
    - refuses env values;
    - creates the connection with `card` pending (cap and rule from the card).
  - **`_apply_card`** runs from `_sync_tools` once tools are listed:
    - same hash → allowed (and the class override);
    - changes get `always`;
    - a read without a hash (curated) is allowed by name;
    - then `card` is cleared.
- **Glass:**
  - the API description mode and the Connector cards box (paste, file, curated from `services/cards.ts`);
  - `queryKeyForm` / `basicForm` (`basicAuth`), Export card, and the "counts as" select per tool;
  - `createMakeEditor` is now the steps editor: a `Plan` is `{make}` for one step, else `{steps, then}`. Field
    references are checked with `stepRefs`, and goals with `checkGoalRefs`;
  - Try it state is kept in `tries` across redraws;
  - the Command Center tab strip scrolls at phone width.
- **Tests and checks:** `tests/test_command_api_maker.py` (20, `tests/fake_api.py`), `tests/apimaker.test.mjs` (8),
  3 guards in `test_command_center_guard.py`. PyYAML is a gateway dependency and a PyInstaller hidden import.
