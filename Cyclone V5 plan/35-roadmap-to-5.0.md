# 35 — Everything left, run by run, to 5.0

**Status:** roadmap, 2026-09-27, after alpha.56; the App Manual (plan 36) added as runs 3–5 on 2026-09-28; the mission workspace (plan 37) added as runs 9–12 after alpha.65, ahead of fleet health. One row is one build run: a release through the fast lane, with its
own tests, guards, release notes and an end-to-end check. The order is a recommendation; the owner can reorder it.

## Where we are

- **Built and published:**
  - the phone Mind, planes and background (alpha.40–46);
  - web-only PC and one-click setup (47–48);
  - Drive (49–53);
  - Command Center C0 shell (51), C1 vault (54), C2 sealed delivery (55), C3 connections with pre-authorised leases (56);
  - the connector maker: any MCP and local servers (57), the API maker, result chaining and cards (58).
- **Planned, not built:** the mission workspace (plan 37), the mission desk (plan 21), the rest of the C4 coordinator, C6 hosted (plan 33), and the road to 5.0 (plan 9).

## The owner's tests (not build runs, but they gate "done")

These are all stated as UNVERIFIED in their release notes:
1. **Windows:** install with `Cyclone-Setup-…exe`, open Glass, `cyclone update`. The DPAPI sign-in store only runs
   here.
2. **Command Center on a real phone:**
   - C0: a routine on two phones;
   - C2: a vault login fills on a phone that never had the password;
   - C3: a Higgsfield video made, then posted behind the Share approval.
   - Send the Higgsfield tool list after signing in, so its connector card can be verified.
3. **Drive:** the car test (plan 32). This decides whether JEV is promoted.

## Build runs

| # | Release | What it delivers | Plan |
|---|---|---|---|
| 1 | **alpha.57: Connect any MCP** (built) | Any remote server: SSE fallback, API key/header, a pasted OAuth client, Client ID Metadata Documents. Tools sorted into reads and changes, **Allow all reads**, per-tool rules, auto-paired job checkers, pinning, **Try it**. **Local MCP servers on the PC:** config import, the plain-words setup card, pinned launchers, Job Object lifecycle, keys in DPAPI | 34 M1+M2 |
| 2 | **alpha.58: API maker and cards** (built) | OpenAPI → connector with no code; GET reads, writes ask; results chained into the next step (`{step.field}`); connector cards (export, import, curated set incl. Higgsfield once verified) | 34 M3+M4 |
| 3 | **alpha.59: App Manual M1: own words, lists and categories** (app dictionary, own words, lists and categories built in alpha.59; revealers, probes and the screenshots switch follow) | The app's own words kept (APK lexicon + chrome filter), never your content (canary guard). A generic UI pattern library; safe probes (reveal, switch category, scroll, one sample); lists recorded by how they work (order, groups, how to find one) and category sets with sub-categories; real names in Glass; model picker on the PC (default: the phone's model) and a screenshots switch | 36 |
| 4 | **alpha.60: App Manual foundation** (built) | Places, panels and doors named in the app's own words on the map; reveal doors open "+" and ⋯ panels and read what they offer; categories proven in one pass; "app word or yours?" for downloaded names (memory only until answered); places in the phone AI's glossary | 36 |
| 5 | **alpha.61: Command Center redesign and Pages (C5)** (built; the owner moved it forward) | A Notion-like workspace: pages and sub-pages, a block editor with / blocks and @ references to phones, skills, routines, tasks, accounts, connections and pages, plan boards (board, table, calendar) whose cards become tasks, live views, backlinks, templates, quick find, the trash. The top-left logo switches between the Command Center and Glass | 33 §9, §12.5 |
| 6 | **alpha.62: AI project manager in the Command Center (C4, first part)** (built; the owner moved it forward) | OpenRouter inside the dashboard: the owner's key (write-only), any tool-using model with prices, daily and monthly caps, private providers only. Ask AI beside every page (Ctrl J, / Ask AI): it reads the workspace with fixed tools, writes pages and cards as proposals (or directly, if allowed), and proposes tasks and routines the owner applies. Pages merge edits made elsewhere | 33 §7, §12.6 |
| 7 | **alpha.64: App Manual: describer, abilities, navigator** (built; alpha.63 went to the Driver-mode fix) | A one-line purpose per screen; "things you can do" with paths and other phrasings; list order; the self-quiz and targeted Map deeper; walks of an ability over the doors the mapper walked, with plain-code screen checks, and `abilities_find` / `go_to(ability)` / `how_to_find` for the phone's AI; the Glass Abilities tab, Export manual and a read-only `app_manual` agent tool; map-quality, dictionary, quiz and walk scores. JEV is parked | 36 |
| 8 | **alpha.65: Parallel sessions** (built; the Lab concurrency suite and per-task pills follow) | The phone runs up to 2 missions behind the front one, each on a background screen of its own (by memory), never touching the owner's screen and coming to the front when it needs it. One shared inbox, a notification per task with Stop and Approve through Task Kit, one Cyclone task per app. The Command Center sends a phone up to three tasks | 26 §6.1 |
| 9 | **alpha.66: Mission workspace** (plan 37 W1, built together with W2) | Every model call built from eight modules: rules, brief, your world, a journal of app stays, the current stay, a live state (plan, collected facts, where and the way back), the app's section of map and manual on every visit, one full screen. `switch_app`/`home`/`back_to`, diversions shown and asked when they change who, how, money or what is public. Behind a setting and the Lab `context` knob; suites `multiapp` and `long` | 37 |
| 10 | **Expect and done** (plan 37 W2; built in alpha.66, so alpha.67 goes to People and notes) | Each action says what it expects and the harness checks it (✓/✗/unchanged), "what changed" on every screen, surprises; a definition of done agreed before the first tap and checked at finish | 37 |
| 11 | **alpha.67: People and notes** (plan 37 W3; built as memory v2) | The owner's own encrypted store: people with relations and handles per app, app notes and lists; proposals saved only on the owner's OK; the goal's references resolved before the run; the send check against the handle on screen; the `people` suite | 37 |
| 12 | **alpha.68: Steer, queue, parallel and plan diversions** (built; the Glass inspector branch and re-derived done checks follow) | Sending while Cyclone works shows Steer / Queue / Parallel (Answer when a question is open); an empty bar gives a real Pause and a two-tap Stop. A steer versions the goal and forces a re-plan; the model decides diversions on its own (always on), and only serious actions (send, pay, delete, post) confirm, at their existing approval; plan versions with a branch drawn on the plan card and in Glass; a Lab `divert` suite | 38 |
| 13 | **alpha.69: Recipes, traps, speed; promotion** (plan 37 W4) | Recipes of past missions and learned traps per app (app words only), prefetch, cache breakpoints; the Lab promotion run makes the workspace the default if it wins every suite | 37 |
| 14 | **alpha.70: Fleet health and alerts** | Phone heartbeat (battery, heat, storage, network, versions), quarantine after 3 infrastructure failures, "phones behind on updates". Alerts to your phone or email: phone offline over 10 minutes, success rate under 80%, a login failing twice, a cap reached. Metrics per recipe, account and phone | 33 §10–11 |
| 15 | **alpha.71: Coordinator (C4), the rest** | The AI project manager is built (alpha.62, §12.6). Still to do: budgets in tasks per day, a daily or weekly report page the AI writes, the Lab coordinator suite, and Cyclone as an MCP server so Claude or Codex can create tasks behind the same approvals | 33 §7, §8 |
| 16 | **alpha.72: Smarter routines** | Richer schedules (monthly, "first Monday"), triggers (a notification, an email, a webhook, another task's result), approval batching for identical kinds, per-kind approval timeouts (the run pauses, never auto-approves) | 33 §5–6 |
| 17 | **alpha.73: Vault, complete** | Passkey unlock (Glass served at `localhost`, which WebAuthn allows). "Remember on this phone" with remote revoke. SMS and email codes read on the phone and filled, never stored. Password rotation as a recipe with 7-day rollback. Sign-up for accounts you own, with the new password sealed back to the vault. Encrypted export (Argon2id) | 33 §4.3–4.5 |
| 18 | **alpha.74: Mission desk + Drive finish** | The mission desk (write long text once, deliver it exactly). The Drive Lab voice suite. JEV promotion if the car test earned it | 21, 32 |
| 19–20 | **alpha.75–76: Hosted, multi-site (C6)** | Cloud Command Center (Postgres, a durable job engine), the PC as an outbound edge, members and roles, SSO. A second site's phones take tasks from the cloud; the vault stays zero-knowledge. Two runs | 33 §12 |
| 21–22 | **5.0.0-rc.1 → 5.0.0 / Glass 1.0** | Hardening: a full physical pass on the Pixel and Windows, plan 9's exit criteria (a secrets fill, a Gmail map, a Chrome-host map, an Ask using a live slot, overlay yield, the pay-block GATE). Also: a signed Windows installer (today `CI_UNSIGNED`), a stable channel next to development, and docs. One RC run, then fixes and 5.0 | 9 |

**Total:** about **22 build runs**, plus the owner's tests. Runs 1–2 and 4–11 are built (9 and 10 together as alpha.66); run 3 is partly built (the
screenshots switch is still to come). Run 7 finished the App Manual's first complete version (plan 36), to be tuned
against the owner's first real passes. Runs 9–12 (plan 37) rebuild how a run sees the world: structured context, checked
actions, the owner's people and notes, and memory that compounds, promoted only when the Lab shows they win. Runs 13–17
make the fleet dependable and complete. Runs 18–19 are the bigger product steps. Runs 20–21 ship 5.0. The owner's next
Command Center asks (action buttons that run automations, links between pages' blocks, synced blocks, page properties)
are listed under "Next for the Command Center".

## Next for the Command Center (owner's ask, not yet scheduled)

- **Links across pages:** link to one block; synced blocks; one plan across all pages' cards; page properties
  (status, date, tags, related phones, routines, accounts); "Mentioned in" with the sentence.
- **Action buttons:** a Button block whose steps run a routine, create a task, send a board's cards, call a connection
  tool or make a page, instantly and behind the same approvals; board automations (a card moved to Doing is sent to a
  phone; a finished task moves its card to Done).
- **Notion polish:** undo and redo for whole blocks, toggles and indented blocks, tables with your own columns, images and
  files, page history, comments, saved templates, favourites, live multi-window editing.

## Decisions still open

- **Pages:** decided and built in alpha.61 as an in-house block editor (it keeps the Glass guard). Real-time
  co-editing (Yjs) is not in it.
- **Hosted (runs 14–15):** where it runs and who pays for it, whether it is wanted at all, and SSO provider.
- **Windows signing (run 16):** a code-signing certificate is needed for a signed installer.
- **Stable channel (run 16):** when development builds stop going to your daily phone.

## What stays true through every run

- PhoneToolExecutor is the only thing that changes the phone.
- Approvals stay for pay, send, delete, permission and sign-in steps.
- No passwords, codes or keys in logs, learning stores or models.
- No generic shell for the model; local MCP servers run only after your approval of an exact, pinned command.
- Owned or authorised accounts only.
- Physical acceptance is stated as owed until you test it.
