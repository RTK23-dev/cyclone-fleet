# Cyclone V5 Alpha 62: an AI project manager in the Command Center

Developer alpha for owner testing. It builds on Alpha 61 (the Command Center, redesigned) and includes it.
- **Mobile:** `5.0.0-alpha.62.dev1` (version code 207). No phone changes beyond the version.
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.62.dev1.exe`.
- **Glass:** `1.0.0-alpha.38`.

You asked for the release planned as "Alpha 63": a project-managing AI you can instruct and pick the model for, with
OpenRouter inside the dashboard. It ships as Alpha 62 because that is the next version number. The links and action
buttons planned as "Alpha 62" come next.

## What changed

**1. OpenRouter inside the dashboard (sidebar → AI).**
- **Your key, once.**
  - Paste your OpenRouter key. Cyclone checks it with OpenRouter, shows your credit left, and keeps it on this PC
    (protected by Windows).
  - It is never shown again, logged, or given to a model. You can test it, replace it or forget it.
- **Any model.**
  - Every OpenRouter model, with its price per million tokens, its context size and whether it can use tools. You
    can search, and show free models only.
  - Only models that use tools can be picked, because the AI works through tools.
  - Pick a default; each conversation can switch.
- **Spending limits:** a daily and a monthly cap (default $2 a day and $30 a month), with bars showing what is spent.
  The AI stops at a cap and says so.
- **Private providers only** (on by default): OpenRouter only routes to providers that do not keep or train on what
  you send.
- **What it may do:**
  - "Ask me before every change" (the default);
  - or "Let it edit pages and cards". Tasks and routines still wait for you.
- **Standing instructions**, read at the start of every conversation ("Plan in weeks starting Monday…").

**2. Ask AI, beside every page (Ctrl J, the sidebar, ✨ Ask AI on a page, or / Ask AI in the editor).**
- **A panel like Notion's.** On a wide screen the page moves over to make room.
  - Opened from a page, it reads that page first ("About: Shop week"); press that label to ask about the whole
    workspace instead.
  - Empty conversations offer suggestions ("Turn this page's to-dos into plan cards", "What failed today, and why?").
- **It reads before it answers.** It looks at your pages, tasks, routines, recent results, phones, accounts,
  connections and what is waiting for you.
  - "▸ 6 steps" shows exactly what it looked at and did.
  - Mentions in its answers are chips that open where the thing lives.
- **Everything it wants to change is a card:**
  - "Add 5 blocks to Shop week" with a preview;
  - "Add the card Film the mug video" (linked to the routine and the phone);
  - "Start a task on Pixel 8: Post the mug video".
  - Press **Apply** (or **Start it**) or **Discard**.
  - An applied edit appears in the open page straight away. An applied task runs like any other, and the phone still
    asks you before it sends, pays, deletes or signs in.
- **Each conversation** shows its model (switchable) and its cost, and today's spend against your cap. Earlier
  conversations are kept (🕘); you can reopen or delete them.
- **Stop** ends an answer at once.

**3. Pages stay in step when something else edits them.**
- **An open page picks up changes made elsewhere** (the AI, another window) within seconds, in place, keeping your
  caret.
- **If you were typing at the same moment,** your edits and theirs are merged block by block, and nothing is lost.
  Before, the page reloaded and your last edit was dropped.

**4. Fixed at phone width:** a page with a plan board, and Home, no longer scroll sideways (the board scrolls inside
itself). This came from Alpha 61.

## Safety

- **The AI runs on this PC's Cyclone, not in Glass.** Glass never calls a model and never holds the key; it only sends
  your words and your choices. CI checks this: the Glass guard and a new Command Center guard.
- **Fixed tools, no shell.**
  - It can read the workspace, edit pages and cards, and propose tasks and routines.
  - It has no tool to delete, approve, read the vault, change accounts or add connections, and it cannot control a
    phone directly.
- **You decide.** Phone work is always a proposal you apply, and page edits are too unless you allow them.
- **No secrets.**
  - Messages, instructions and anything the AI writes are refused if they look like a password, code or key.
  - Pages keep refusing secrets as before.
  - Everything the AI writes becomes typed blocks, checked like your own typing.
- **Outside content is information.** Page text, task results and connection data reach the model as tool results,
  and it is told never to follow instructions inside them.
- **What is kept** is what the model saw and did (messages, tool calls and results, cost), never hidden reasoning. The
  audit chain records saving the key, settings, proposals, and each apply or discard, never the key itself.
- **Budgets and limits:** caps per day and month, at most 10 model calls per answer, at most 8 tool calls at a time,
  and a stop button.

## Validation and limits

Tests that pass:
- **Gateway: `test_command_ai.py`, 12 tests (against a scripted OpenRouter):**
  - the key is refused if malformed or rejected by OpenRouter; once saved it is in no database file, status, audit
    or response;
  - the model list: tool-using models only, prices per million, cached for an hour;
  - a full turn: reading tools, a proposal, apply, the result told to the model on its next turn;
  - direct workspace edits while tasks and routines still wait;
  - 7 kinds of bad calls answered as errors the model can fix (an unknown tool, bad JSON, a secret, an invented id,
    an unknown phone, an extra argument, a missing block);
  - a secret in a message refused, and hidden reasoning never stored;
  - the daily cap and the step limit;
  - out-of-credits and data-policy errors explained;
  - a restart marking an interrupted answer, and Stop;
  - Markdown to blocks and back;
  - every route behind the bearer.
  - The full gateway suite passes (623 passed, 2 skipped).
- **Glass: `ai.test.mjs`, 5 new tests:**
  - parsing and prices, and answers as typed pieces (markup stays text);
  - the AI settings screen: the key sent once and cleared, models, choices, limits;
  - the setup card;
  - the panel: page context, send, working, steps, mentions, apply and discard.
- **Glass: the merge test** (both sides' edits survive) replaces the old reload test.
  - All 223 Glass tests pass; typecheck, build and the Glass guard are clean.
- **CI guards:** a new one for the AI (write-only key, fixed tools without delete/approve/vault/shell, exactly two
  places that change things, no reasoning kept, private providers, Glass only talks to the local runtime). All 184
  guard tests pass. Versions are coherent.
- **End to end in Chromium** (the real runtime and Glass, a scripted OpenRouter, a scripted phone), 24 checks:
  - saved the key, checked the credit and picked a model;
  - asked "Plan Friday for the shop" from a page. The AI read the page, the routines and the phones, and proposed
    5 blocks, a linked card and a task;
  - applied the edit and the card; the open page showed them without a reload, with real mentions, and the routine
    now lists the page;
  - started the task, which ran on the scripted phone;
  - a follow-up, "What failed today?";
  - Ctrl J, / Ask AI, history, and phone width.
  - **Canary scan:** the key was in no runtime file and not in the server log, and went only in the Authorization
    header.
  - Alpha 61's workspace walk-through and editor checks, and Alpha 58's API connector end to end, were run again and
    passed.

Limits:
- **Not tested against the real OpenRouter.** Every test used a scripted stand-in. Your first real conversation is the
  first real call; if a model misbehaves, switch models from the panel.
- **Physical: UNVERIFIED.** No phone change in this release. Windows is still owed its test; on Windows the key is
  kept with DPAPI, while elsewhere it is kept only until Cyclone restarts.
- **One answer at a time per conversation.** The AI does not yet act on a schedule by itself (no daily report yet).
- **Not in this release:** action buttons and board automations, block links and synced blocks, page properties
  (plan 35, "Next for the Command Center").
