# Cyclone V5 Alpha 61: the Command Center, redesigned

Developer alpha for owner testing. It builds on Alpha 60 (the App Manual foundation), Alpha 59 (the app dictionary) and
Alpha 58 (API maker and cards), and includes them.
- **Mobile:** `5.0.0-alpha.61.dev1` (version code 206). No phone changes beyond the version.
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.61.dev1.exe`.
- **Glass:** `1.0.0-alpha.37`.

The Command Center is now its own workspace, modelled on Notion. You write pages, nest them, and mention your phones,
skills, routines, tasks, accounts, connections and other pages anywhere. Planning boards and live views of what your
phones are doing sit inside the pages. The Cyclone logo at the top left switches between this workspace and the
familiar Glass screens. This is Command Center C5 (Pages), which you asked for ahead of parallel sessions.

## What changed

**1. Two faces, one logo (top left).**
- **The logo shows where you are:** the face you are on is in front and the other peeks out behind it. In the
  Command Center it is the Command Center mark with Cyclone behind; in Glass, Cyclone's teal arcs with the
  Command Center behind.
- **Press the logo** to switch. You land where you left that side.
- **Each face has its own sidebar.**
  - The Command Center: Search, Home, Inbox, your databases (Tasks, Routines, Results, Accounts, Connections,
    Vault), your pages, and the Trash.
  - Glass keeps Home, Devices, Apps (with the Dictionary tab from Alphas 59 and 60), Runs, Lab, Marketplace, Knowledge, Phone and
    Settings.

**2. Home.**
- A greeting and today's date.
- **Start a page from a template:** Empty page, Weekly plan, Daily app check, Content calendar.
- **Then:** what is waiting for you, your recent pages, what is running and queued, the routines coming up, and your
  phones.

**3. Pages (sidebar → + New page, or + on a page to add one inside it).**
- **Name it** in the big title, and pick an icon.
- **Write in blocks:**
  - **/** opens the block menu: text, three heading sizes, to-do, bulleted and numbered lists, quote, callout,
    divider, plan boards and live views.
  - **Shortcuts:**
    - `#`, `##`, `###` and a space: headings;
    - `-`: a list; `1.`: a numbered list;
    - `[]`: a to-do;
    - `>`: a quote;
    - `---`: a divider.
  - **Editing:**
    - Enter starts a new line, and a list continues; Enter on an empty list item ends the list.
    - Backspace at the start of a line joins it to the line above.
    - **Ctrl+B / I / E** make text bold, italic or code.
    - Pasting several lines makes several blocks.
  - **Rearrange:** drag a block by its **⋮⋮** handle to move it, or press the handle to turn a block into another
    kind, duplicate, move or delete it.
- **Reference anything with @:** phones, skills (of the chosen phone), routines, tasks, accounts, connections and
  other pages.
  - A mention is a chip in the text; click it to open that thing.
  - Each page lists the pages inside it, and **"Mentioned in"**: the pages that mention it.
- **Your pages in the sidebar:**
  - Open and close them.
  - Double-click a name (or **⋯ → Rename**) to rename it.
  - Drag a page onto another to put it inside; drag it onto **Pages** to bring it to the top.
  - **⋯ → Move to trash** (restore it, or delete it for good, from **Trash**).
- **Saving:** pages save by themselves a moment after you stop typing ("Saving…", "Saved"). If the same page changed
  in another window, this one reloads instead of overwriting it.

**4. Planning that works (/ → Plan board, or Plan calendar).**
- **Cards live in To do, Doing and Done.** See them as a **board** (drag cards between columns), a **table** or a
  **calendar** (drag a card onto a day, or press **+** on a day).
- **Open a card** to set its status, date and notes, and to link phones, routines, skills, accounts or pages.
- **Send to a phone:** turns the card into a Command Center task.
  - It runs on the phone the card links to (or any ready phone), with the linked account.
  - It keeps all the usual approvals.
  - The card then shows the task's state.

**5. Live views (/ → Tasks, Tasks board, Routines, Approvals, Phones, Results, Sub-pages).**
- **What they show:** the Command Center's own data, always current, as a list, table, board, calendar or gallery.
- **Tasks views** filter by open, finished or all, and by phone.
- **Every row opens where it lives.** A view never changes anything by itself.

**6. Quick find (Ctrl K, or Search).** Search pages by title and text, and jump to any phone, skill, routine, task,
account or connection.

**7. The databases, calmer.**
- **Inbox, Tasks, Routines, Results, Accounts, Connections and Vault** each have their own clean page. Their create
  form waits behind **New task / New routine / New account**.
- **Tasks can be shown as a table, a board by status, or a calendar.**
- Everything from Alpha 58 (connections, API connectors, steps, cards) works as before.

## Safety

- **Pages never hold secrets.**
  - Text, titles, labels and card notes that look like a password, code or key are refused by the PC. The block is
    marked while you type, and "Not saved" says why. The next edit saves normally.
  - References carry an id and a short label, never a value. CI guards this.
- **Nothing in a page is markup.**
  - Blocks are typed (text pieces, mentions, cards, views) and checked field by field on the PC.
  - Glass reads what you typed back into typed pieces and builds the page with DOM APIs; no HTML is ever stored or
    injected. CI guards this.
- **The workspace never commands a phone directly.** A card reaches a phone only as an ordinary Command Center task,
  with the same approvals for sending, paying, deleting and signing in. Live views only read. CI guards this.
- **Saves are versioned.** A save made on an old copy of a page is refused (409), never merged silently.
- **Deleting is two steps:** the trash first, then **Delete for good**.
- **The audit chain records creating, renaming, moving, trashing, restoring and deleting pages,** by id only (no page
  text).

## Validation and limits

Tests that pass:
- **Gateway: `test_command_pages.py`, 5 tests:**
  - nesting, versioned saves and a refused stale save;
  - references indexed and removed again, and backlinks for every kind;
  - typed blocks, with 9 kinds of bad input refused (unknown block, markup fields, secrets, bad ids, unknown views,
    duplicate ids, too many blocks);
  - moving, depth limits, search (with `%` and `_` taken literally), and the trash with sub-pages;
  - the audit chain stays intact;
  - every route behind the bearer, and a stale save answers 409.
  - The full gateway suite passes (611 passed, 2 skipped).
- **Glass: `workspace.test.mjs`, 14 tests:**
  - text pieces: split, join, delete, mentions;
  - markdown shortcuts, the / menu, the @ directory, lists, the tree, boards and calendar grids;
  - routes;
  - **the logo switch** (each face and its sidebar, and returning where you were);
  - the page tree: nest, add, rename, trash;
  - typing: headings, to-dos, lists and dividers; the / menu with a plan; @ mentions that open where they live;
    Backspace and the block menu;
  - a plan board: add, status, close by clicking outside, **Send to a phone** with its phone link, calendar;
  - autosave with versions and a reload on conflict; a refused save with a secret is not retried;
  - the home; the databases in the workspace.
  - All 217 Glass tests pass; typecheck, build and the Glass guard are clean.
- **CI guards:** a new one for pages (typed blocks, no secrets, versioned saves, trash first, no markup, no direct
  phone commands). All 183 guard tests pass. Versions are coherent.
- **End to end in Chromium** (the real runtime and Glass):
  - pressed the logo (Glass → Command Center), started a Weekly plan, typed `## Monday`;
  - mentioned **@Answer shop orders** (a routine), which became a chip;
  - used **/to-do**;
  - opened a card, linked the routine, and pressed **Send to a phone**. The task was created and ran on the scripted
    phone;
  - added a page inside, renamed it, and found it with Ctrl K;
  - Tasks as a board; pressed the logo back to Glass.
  - **Editor behaviour checked with a real keyboard:**
    - Enter splits at the caret, and Backspace joins with the caret kept;
    - Ctrl+B;
    - pasting two lines;
    - arrows between blocks;
    - `> ` makes a quote;
    - dragging a block;
    - a typed `password: …` is refused and the next edit saves;
    - no sideways scrolling at phone width;
    - dark mode.
  - **Canary scan:** the typed secret was in **none** of the runtime's files or the server log.
  - Alpha 58's API connector end to end (description, key, steps, approval, card export and import) was run again
    inside the redesign, and passed.

Limits:
- **Physical: UNVERIFIED.** No phone change in this release. A card sent to a real phone has not run on a device;
  Windows is still owed its test.
- **One person at a time.** Pages save with versions, but there is no live co-editing. A second window reloads
  instead of merging.
- **Undo inside a block is the browser's own.** Undoing a whole-block action (move, delete) is not yet possible.
- **Skills can be mentioned from the chosen phone only,** and the phone must be ready. Phones and skills open on the
  Glass side (Devices, Knowledge).
- **Not in this release:**
  - nested (indented) blocks;
  - tables with your own columns;
  - images or files in pages;
  - sharing a page;
  - saved filters per view beyond status and phone.
- **Next:** the rest of the App Manual (alpha.62), then parallel sessions (alpha.63); see plan 35.
