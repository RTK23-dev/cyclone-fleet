# Cyclone V5 Alpha 57: connect any MCP

Developer alpha for owner testing. It builds on Alpha 56 (Command Center C3) and includes it.
- **Mobile:** `5.0.0-alpha.57.dev1` (version code 201). No phone changes beyond the version.
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.57.dev1.exe`.
- **Glass:** `1.0.0-alpha.32`.

This is the first build run of plan 34 (M1 + M2): paste almost any MCP server, or a server program's config, and
Cyclone works out the rest. It reads and writes in outside services and brings the results back.

## What changed

**1. Paste an address, Cyclone works it out (Command Center → Connections → Server address).**
- **Its steps, in plain words.** Each connection lists what Cyclone found: "Reached it", "It wants you to sign in",
  "It wants a key (API key or token)", "3 tools: 1 read, 2 change things".
- **Transports:**
  - Streamable HTTP first;
  - the **older event-stream transport** (HTTP + SSE) if the server only speaks that. It shows as "older transport".
- **Sign-in, whichever the server wants:**
  - OAuth with self-registration (as in Alpha 56).
  - **An API key or token in a header.** Paste it once, with the header name from the service's docs (`X-Api-Key`,
    `Authorization`, …). "Send it as Bearer" adds the prefix for you.
  - **A client ID you paste,** for services that don't let apps register themselves. Glass shows the redirect
    address to give the service.

**2. Programs on this PC (Connections → Program on this PC).**
- **Paste the server's config** from its README (the usual `mcpServers` JSON). Nothing runs yet.
- **One short card first:**
  > **Run this program on your PC?**
  > `npx -y @modelcontextprotocol/server-filesystem@2025.8.21 C:\Notes`
  > It works like any app you install: it can read and change files on this PC and use the internet. Cyclone runs
  > only this exact version and stops it when Cyclone stops.
  > **Only add programs you trust.**
  > **[Run it] [Cancel]**
- **Keys** the program reads from its env are typed on the card, in masked fields. They are kept sealed on this PC.
  Glass does not keep them, and the database only holds their names.
- **Once running:**
  - the program reaches outside services and the answers come back to the Command Center;
  - **Show its log** shows what it wrote, with keys hidden;
  - if its command, version or script changes, the card shows again with what changed.

**3. Tools in three groups, each with its own rule.**
- **Reads** only look. **Allow all reads** turns them on in one click, and they run without asking, up to the
  daily cap.
- **Changes things:** each has its own rule (ask every time, ask over the cap, or no asking up to the cap). New ones
  ask every time.
- **Sends, deletes, pays or grants — always asks you.** These always wait for your OK, whatever you choose.
- **Try it** runs an allowed reading tool now and shows what came back.
- **A background job and the tool that checks it** are paired by themselves, so "make first" steps pick the checker
  for you.

**4. Changed tools switch off.** Cyclone remembers each tool's name, description and inputs as you allowed them. If a
server changes a tool later, that tool is switched off. Glass shows **Before** and **Now** until you allow it again.

## Safety

- **Nothing is on until you tick it.** Sends, deletes, payments and permission changes always ask first. CI guards
  this.
- **Keys and tokens** live only in the sealed store on this PC (Windows DPAPI for your user; memory only on other
  systems). They never go in the database, Glass after you type them, the audit, logs or a model. CI guards this.
- **Local programs:**
  - **Launchers:** only `npx`, `uvx`, `docker`, `node` or `python`, named without a path.
  - **Pinned:** a version is required (`pkg@1.2.3`, `pkg==1.2.3`, a Docker tag or digest, never `latest`). A
    script is pinned by its SHA-256.
  - **No shell:** the command is an argument list; `& | < > ^ % "` and new lines are refused, because Windows runs
    `npx.cmd` through cmd.exe.
  - **Refused:** env names that change how programs load (`PATH`, `NODE_OPTIONS`, `PYTHONPATH`, `LD_PRELOAD`, …)
    and Docker host powers (`--privileged`, `--cap-add`, `--device`, `--pid`, `--network host`, …).
  - **How it runs:**
    - its own folder;
    - a small environment that never includes the gateway's token (tested);
    - a Windows Job Object so it ends with Cyclone;
    - at most 3 restarts in 10 minutes;
    - stopped after 15 idle minutes.
  - Only you, from Glass, can add, approve or start one. The model and the agent MCP servers cannot reach these
    routes (CI-guarded since C0).
- **Results** are kept bounded (32 KB), with secret-shaped fields hidden. They are data, never instructions.

## Validation and limits

Tests that pass:
- **Gateway:**
  - **`test_command_connectors.py`, 11 tests against local servers:**
    - a key-only server: the wrong key is refused, the right one works, and it is in no file;
    - an older-SSE server connects and answers;
    - a service without self-registration is signed in with a pasted client;
    - reads, changes and sensitive tools are sorted, with auto-pairing and "allow all reads"; a sensitive rule
      can't be lowered;
    - a changed tool switches off, and Before/Now is shown;
    - a local program runs only after its exact hash is approved. It reaches an outside service with its key, and
      the result comes back. Writes ask first; its log hides the key and it never got the gateway token;
    - a changed command or script needs approval again;
    - eleven configs Cyclone will not run are refused;
    - a program that keeps dying stops restarting;
    - an empty env placeholder is not treated as a saved key.
  - The 18 Alpha 56 connection tests still pass, and so does the full gateway suite.
- **Glass:**
  - **`connectors.test.mjs`, 7 tests:**
    - a pasted config is sent as data;
    - the setup card's exact words, and approving that exact hash;
    - the masked key form and the Bearer prefix;
    - the client-ID form with its redirect address;
    - tool groups, "allow all reads", and Before/Now on a changed tool;
    - Try it, and env values never parsed.
  - All 187 Glass tests pass.
- **CI guards:** launchers, argument lists, pinning and no gateway token; approval by exact hash; sensitive tools
  always ask; changed tools switch off; the setup card's words; only the setup card approves.
- **End to end:** the real runtime and Glass in Chromium, with a key-only server, an older-SSE server, and a local
  Python server program:
  - pasted the key server's address, typed the key, allowed the reads, and **Try it** returned the answer;
  - the older-SSE server connected by itself;
  - pasted a local config and saw the card as quoted above. Typed its key and pressed **Run it**, and it started.
    Its read reached an outside weather service with that key and returned `{"place": "Utrecht", "temp": 21}`. The
    log showed the key as `[hidden]`;
  - a write waited for approval;
  - the API key and the env key were in **none** of the runtime's files or its log.

Limits:
- **Physical: UNVERIFIED.** Local programs have run here on Linux only. On Windows, `npx.cmd` / `uvx`, the Job
  Object and DPAPI key storage are still owed a real test.
- **Client ID Metadata Documents** (the newest MCP sign-in rule) are **not** supported. The service would have to
  fetch a document from this PC, which it can't reach. Self-registration and pasted clients cover the rest.
- **Runs with ordinary rights:** a local program is a normal program with your user's rights, as the card says.
  Cyclone does not sandbox its files or network.
- **Tool classes** come from the tool's own hints and its name. A tool can be misnamed, so check the groups before
  you allow changes.
- **Not in this release** (plan 34 M3/M4, alpha.58): building a connector from an API description (OpenAPI),
  passing one step's results into the next, and connector cards.
