# Cyclone V5 Alpha 56: Command Center C3, connections, plus prepared passwords

Developer alpha for owner testing. It builds on Alpha 55 (sealed delivery) and includes it.
- **Mobile:** `5.0.0-alpha.56.dev1` (version code 200).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.56.dev1.exe`.
- **Glass:** `1.0.0-alpha.31`.

This is the fourth Command Center release (plan 33, **C3: connections**), plus the one piece C2 left open,
**pre-authorised leases**.

With it:
- a task can make a video with Higgsfield (or any MCP server you sign in to) and then post it from a phone. You
  approve the credits first and the final Share last;
- a routine that signs in with a vault password can run while Glass is closed.

## What changed

**1. Connections (Command Center → Connections).**
- **Add a server:** paste its MCP address, or press **Use Higgsfield** (`https://mcp.higgsfield.ai/mcp`).
- **Sign in:** press **Sign in**. The server's own sign-in page opens in a new tab. When it says "Signed in", come
  back.
  - Cyclone registers itself with the server and signs in with OAuth 2.1 (PKCE).
  - The sign-in is kept on this PC, sealed with Windows DPAPI for your Windows user. Glass never sees it.
- **Tick the tools Cyclone may call.** None are allowed until you tick them; a server's "delete account" stays
  unticked.
- **Set the rules:**
  - **Calls a day:** the daily cap.
  - **When to ask:**
    - *Ask me before every call* (the default);
    - *Ask me only over the daily cap*;
    - *Never ask; stop at the daily cap*.
- **Recent calls** and **Files made** are listed below. Every file can be downloaded.

**2. Make, then post (Tasks → "First make a file with a connection").**
- **Pick** the connection and one of its allowed tools, and fill in its fields (prompt, model, length, aspect…).
  They are read from the server's own tool description.
- **If the tool works in the background** (Higgsfield does), pick the tool that checks the result. Cyclone asks it
  every 10 seconds, for up to 20 minutes.
- **Then either:**
  - **a phone posts it:** the file goes to the phone's gallery (Movies/Cyclone) and the phone's task posts it; or
  - **keep it:** only make the file.
- **What you approve:**
  - With *Ask me before every call*, the Approvals inbox shows the exact call (connection, tool, prompt and every
    field) with **Approve the call** / **Decline**.
  - While the phone posts, its **Share / Post / Publish / Upload** tap is a send. It waits for your OK, with the
    caption and destination shown, in Glass (**Approve and send**) or on the phone.
- Routines can carry the same "make first" step.

**3. Routines that sign in while Glass is closed (Routines → "Seal the password ahead for").**
- **Choose how many runs to prepare:** a routine with a vault login on one phone can prepare its next run, 3 runs
  or 7 runs.
- **Seal them once:** open the Vault tab while it is unlocked, and Glass seals each run's password ahead. The
  routine list shows "3 of 3 runs sealed ahead".
- **What each sealed copy is bound to:**
  - that run's own task;
  - that phone's key;
  - the account's app or site;
  - an expiry 30 minutes after the run is due (at most 8 days ahead).
- **Then close Glass:** the run starts on time with its password.
- **If no copy is ready:** the run waits and the Approvals inbox says "Unlock the vault in Glass…". That clears
  itself once the password is sent.

## Safety

- **The model never touches a connection.** Connections are called by the Command Center for your tasks, only with
  tools you allowed. The PC agent MCP servers still cannot reach Command Center routes (CI-guarded).
  - Only four fixed MCP messages are sent: initialize, the initialized notice, list tools and call a tool.
- **The cap stops a runaway loop:**
  - past the cap, a call is refused (or waits for you, if you chose that);
  - only one call per connection waits for your OK at a time, and at most two run at once;
  - a task refused by the cap fails with that reason instead of retrying.
- **Sign-in tokens:**
  - They never go in the Command Center database, the audit chain, Glass or a model (CI-guarded).
  - The only route without the Glass bearer is the sign-in redirect. It accepts only a single-use state that Glass
    started in the last 10 minutes, and the code is useless without the PKCE verifier the PC kept.
- **Files:**
  - They are only fetched over https from public addresses (never from your home network), re-checked on every
    redirect, up to 500 MB, and stored by SHA-256.
  - The phone checks the whole file's SHA-256 before it adds it to the gallery. Only video, image and audio are
    taken.
- **No secrets in calls.** Connection fields are plain values, screened for passwords, keys and codes on the PC and
  again in Glass.
- **Prepared passwords are still one use:**
  - Each one opens only for its own run: the phone checks the task id, its key, the app or site and the expiry.
  - A changed, paused or deleted routine revokes the ones not yet used.
  - Replayed or expired copies are refused as in Alpha 55.

## Validation and limits

Tests that pass:
- **Gateway** (`test_command_connections.py`, 18 tests, against a local MCP server with real OAuth, event streams
  and a polled video job):
  - sign-in state is single use and expires, and the token is in no file on disk;
  - only allowed tools run, and secrets are refused;
  - *always* asks, then polls and keeps the video by SHA-256;
  - decline;
  - the cap refuses the third call, and *over cap* asks one at a time;
  - token refresh;
  - make-and-post: approve, chunks to the phone, then start with the publish gate;
  - make-and-keep with no phone;
  - a task over the cap fails;
  - make-step validation and media checks;
  - pre-authorised leases: three runs prepared, the first starts with its own lease, the list tops up, a missing
    lease raises a login request, and editing or deleting revokes;
  - the callback page escapes what it shows.
  - The full gateway suite passes.
- **Phone:**
  - `CommandMediaTest`: chunks in order; wrong offset or corrupt file refused and never published; media types and
    size only.
  - Share is a send only during the posting mission.
  - `cc.start publish` turns the gate on for that mission only.
  - The full suite (2002 tests) passes.
- **Glass:** connections parsing; typed tool arguments; sign-in opens the server page; rules saved as ticked; the
  spend approval shows the exact call; the login request points to the vault; the make-first task form; the routine
  prepared ahead on one phone; prepared lease expiry and the 8-day limit. All 180 Glass tests pass.
- **CI guards:** fixed MCP methods, default-deny tools, one unauthenticated route, no tokens in schema, public data,
  audit or Glass, the phone's hash check and publish gate, and prepared leases bound to their run.
- **End to end:** the real runtime and Glass in Chromium, with a local stand-in for Higgsfield (OAuth, event
  streams, a job that needs two polls) and a scripted phone:
  - added the connection, signed in on the server's page in a new tab, allowed two tools, cap 1;
  - created a make-and-post task in the Glass form, approved the credits in Approvals, and the video was made,
    polled and kept;
  - the video was sent to the phone (SHA-256 matched, 68 KB);
  - the phone asked to Share with the caption shown, **Approve and send** in Glass, and the task was done;
  - the next make task failed on the cap;
  - created a vault and login in Glass, trusted the phone (fingerprint shown), and made a routine prepared 3 runs
    ahead. Glass sealed all three, each expiring 30 minutes after its run;
  - **with the browser closed**, the first run started with its own sealed password. The phone opened it, and its
    SHA-256 matched the canary;
  - the canary password, the vault passphrase and both OAuth tokens were in **none** of the runtime's files or its
    log.

Limits:
- **Physical: UNVERIFIED.**
  - Posting a real video from a real phone (gallery import, the app's own Share button behind the send gate) has not
    run on a device.
  - Nor has the Windows DPAPI grant store run on Windows; CI runs on Linux, where grants are kept in memory only.
- **Higgsfield itself was not called.** Its tool names and result format are not published, so this was tested
  against a local stand-in that behaves like an asynchronous video server. Pick the generate tool and the
  "check result" tool from the list Higgsfield shows after sign-in.
  - If Higgsfield does not accept a sign-in redirect to this PC (`http://127.0.0.1…`), or does not let apps register
    themselves, the connection says so and cannot be used yet.
- **Caps count calls, not credits.**
- **Prepared passwords need Glass open, unlocked, once** to seal them. Runs further than the prepared number wait
  with the login request.
- **Sign-in is kept only on Windows.** Elsewhere (Linux or macOS dev runs) you sign in again after the runtime
  restarts.
- **"Remember on this phone"** and SMS/email code relay are still not in this release.
