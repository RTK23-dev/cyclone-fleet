# Plan 43: Cyclone Tables, action buttons and Account Setup

**Status:** build plan (2026-09-30, at alpha.77). Owner request: Notion-grade tables in Glass's Command Center, with
cross-references, several views of the same data, and action buttons that run routines, skills or new prompts on a
chosen phone and profile. Accounts are rebuilt around phones → profiles → apps. A dedicated **Account Setup** mode
learns an app's sign-up flow once, turns it into a table and creates accounts from its rows. A **Verification desk**
hands identity checks to the account's own holder, through MCP when needed, without stopping Cyclone's other work.

**Split of the work:**
- **Glass manages:** tables, views, buttons, forms, the queue.
- **Cyclone Mobile has the intelligence:** mapping a sign-up, filling it, noticing drift, knowing when a verification
  needs a person.
- **The PC runtime** stores the tables and runs the queue, like the rest of the Command Center (plan 33).

---

## 0. What exists and is reused (checked in code)

| Need | Already there | File |
|---|---|---|
| Pages with blocks, `@` references, trash, quick find | Command Center pages (C5) | `apps/glass/src/workspace/*`, `services/pages.ts`, gateway `command/pages.py` |
| Live views (table / board / list / calendar / gallery) | Fixed sources only (`tasks`, `routines`, `accounts`, …) | `workspace/views.ts` (`ViewSource`, `LAYOUTS_FOR`) |
| A small plan board | `board` block with `PlanItem` (title, status, due, refs, task) | `services/pages.ts`, `workspace/plan.ts` |
| Tasks, routines and the job engine | CC tasks become Mind missions on a phone; schedules | `command/center.py`, `schedule.py`, `steps.py` |
| Accounts as metadata | `CcAccount` (service, handle, owner basis, 2FA, allowed devices, vault items) | `services/command.ts`, `command/center.py` |
| Secrets | Vault with sealed delivery; new sign-up passwords generated on the phone and sealed back | plan 33 §4.2–4.4, `command/vault.py` |
| Approvals, including `handover` | The approvals inbox → Task Kit → the owning mission | plan 33 §6 |
| MCP in both directions | Connections (OAuth, allowlists, caps) and CC as an MCP server | `command/connections.py`, `command/mcp.py` |
| A remote link | Remote MCP tunnel, live phone view and control | `pages/remotePage.ts`, `pages/phonePage.ts`, `api/stream_api.py` |
| Profiles | Real Android users made by Cyclone, a registry with label, colour and emoji, Carry | plan 40 |
| App knowledge | Atlas maps, App Manual abilities and checked walks, scenarios ("Sign in") | plans 22, 36 |
| Fast execution of a known plan | The Pilot (plan 41) and Instant / Flash / Mind (plan 42) | `mind/pilot`, `mind/modes` |

**The gap:**
- user-defined tables with typed properties;
- relations and back-relations;
- saved views per table;
- buttons;
- profiles as a first-class filter;
- apps and accounts per profile;
- sign-up maps;
- the verification desk.

---

## 1. Cyclone Tables (Notion-grade, in the Command Center)

### 1.1 The model

A **table** (a database) has typed **properties** and **rows**. Every row is also a page (it opens full-size with the
block editor underneath, as in Notion). A table lives on a page as a block and can be shown on any other page as a
**linked view**.

**Property types:**

| Type | Notes |
|---|---|
| Title, text, number, currency (€ by default), percent | Numbers right-aligned; currency formatting per property |
| Date, date range, created time, edited time | Ranges drive the timeline ("May 28 → Jun 7") |
| Select, multi-select, status | Coloured options you name and reorder; status has groups (To do / In progress / Done) |
| Checkbox, email, phone number, URL | Email and phone are shown masked in shared views when marked personal |
| **Relation** | To another table (or a system table, §2); one or many; two-way, and the back-relation appears on the other side |
| **Rollup** | Over a relation: count, sum, average, earliest or latest, "show values", percent checked |
| Formula (simple) | Arithmetic, `if`, dates, text join, over this row and rollups; evaluated on the PC, never by a model |
| Phone, profile | Pick from the fleet; a profile always carries its phone (§3) |
| Files | Stored in the runtime's artifact store |
| **Button** | One or more actions (§4) |
| Run | The latest run a button started: status chip, and a link to the run |

**Personal and secret data:**
- **Secrets never live in a cell.** A "password" property is a link to a vault item, and the value is generated or
  typed only through the vault (plan 33 §4). CI guards that no cell type can hold a secret.
- **Personal data** (names, dates of birth, addresses) is ordinary row data, marked `personal`:
  - encrypted at rest in the runtime store;
  - masked in exports and in views shared with agents unless the agent's task needs that field;
  - never written to runs, diagnostics or learning stores; only the phone types it into the field it belongs to.

### 1.2 Views

Every table has any number of **saved views** over the same rows. Each view has:
- a layout:
  - **table**;
  - **board**, grouped by a select, status, phone or profile;
  - **timeline**, over a date range;
  - **calendar**;
  - **gallery**;
  - **list**;
- filters (and/or groups);
- sorts;
- a group by;
- visible and ordered properties;
- column widths;
- card previews;
- a name and an icon.

This is the "All Accounts / Timeline / Board / Daily / Today's To-Do / Pipeline by Status" row from the screenshots.
A **linked view** on another page keeps its own filters, while the rows stay in one place.

### 1.3 Editing

- Inline cell editing, keyboard navigation and multi-select rows.
- Drag cards between board groups (this sets the group's property); drag bars on the timeline.
- "New" opens the row as a side peek, a centre modal or full page (per view).
- **Forms:** any view can be shared as a form that adds a row (used by Account Setup, §6).
- **History:** every change is kept per row (who: you, an agent or a phone run; what; when) with undo. Deleted rows go
  to the Command Center's trash.

### 1.4 Storage and API

- **Where:** the PC runtime, in the same store as the Command Center (SQLite): `tables`, `properties`, `rows`
  (cells as typed JSON), `views`, `relations` (edge rows, both directions) and `row_history`.
- **Access:** only the existing authenticated `/v1/cc/*` routes; Glass stays a client with no intelligence.
- **Routes:**
  - `GET/POST /v1/cc/tables`;
  - `/v1/cc/tables/{id}/rows?view=…`, which returns the rows with filters, sorts and rollups computed on the PC;
  - `/rows/{row}`;
  - `/views`;
  - `/properties`.
- **Scale target:** 10k rows per table, a filtered view under 150 ms. Rollups are recomputed when a related row
  changes (incrementally, not on every read).

---

## 2. Cross-referencing

A relation is two-way, and every related row is one click away. Clicking a relation chip opens that row as a peek,
with its own properties and back-relations.

In the screenshot's example:
- clicking **Bank: Wise ES | 002** opens the bank row;
- the bank row shows the customer it belongs to, the orders that used it (a back-relation), and a rollup (orders
  count, total).

**System tables** are Cyclone's own records, shown as tables you can relate to and filter on but not delete from:
- **Phones**;
- **Profiles** (§3);
- **Apps** (per profile);
- **Accounts**;
- **Routines**;
- **Skills**;
- **Tasks**;
- **Runs**;
- **Connections**.

Example: a row in your "Brands" table relates to its Instagram **Account**, which is on **Pixel 8 › Profile B**. A
rollup shows its last run, and a button runs "Check DMs" there.

`@` references in page text resolve to rows as well, and a mention shows up on the row's page as a "Mentioned in"
back-link.

---

## 3. Phones and sub-profiles

- **The phone reports its profiles:** a new read-only route relays the profile registry from the phone, with the
  stable profile id, label, colour and emoji, state (ready, stopped, locked) and whether Cyclone is set up inside.
- **A tree:** in Glass the **Phones** system table becomes Pixel 8 › Profile A, Profile B. A profile row always has
  its phone:
  - it is created only from the phone's registry and can't be re-parented;
  - if the phone is removed, its profiles go with it.
- **Filters:** "Phone is Pixel 8" includes all its profiles; "Profile is Pixel 8 › B" narrows to one. Board views can
  group by phone or by profile.
- **Targeting:** every task and button has a `profileId` next to its `deviceId`.
  - The phone runs it in that profile. It switches with plan 40's proven switch, or waits when the owner is using
    another profile and the setting says "don't switch while I'm using the phone".
  - A task never runs in a profile Cyclone did not create (`ProfileRecovery.validOwned`).

---

## 4. Action buttons

A **Button** property, or a page button, holds an ordered list of actions. Each action is one of:

| Action | What it does |
|---|---|
| **Run routine** | A Command Center routine or a phone routine, synced both ways. The list comes from the phone's saved skills and routines and the runtime's routines. A routine removed on the phone shows as missing, never silently rebinds |
| **Run skill** | A saved skill (plan 23), with its parameters filled from row properties |
| **Prompt** | A new request, written as a template: `Check WhatsApp for messages from {{Customer.Name}} and summarise them`. It goes through plan 42's router: Instant, Flash or the Mind, whatever fits |
| **Create account** | Runs Account Setup for this row (§6) |
| **Update row** | Sets properties ("Status → Planned payment", "Refund date → today") |
| **Open** | A URL, a page or a related row |

**Where it runs:** a fixed phone and profile, or a phone or profile property of the row ("Run on: this row's
Profile").

**After the run:**
- Optionally map the result into the row: "on success set Status → Checked; write the summary into Notes".
- The **Run** property shows live state (queued, running, needs you, done ✓, failed) and links to the run in Runs.

**Rules** (Task Kit, plan 32 boundaries):
- A button makes an ordinary Command Center task. The phone's approvals still ask for pay, send, delete, permission
  and sign-in steps.
- A button press never approves anything by itself. "Approve" stays a separate action in the Approvals inbox.
- Templates fill only from the row's own properties, never from secrets.
- Personal properties are passed only when the template names them.

**Agents use the same buttons.** The coordinator (plan 33 §7) and outside agents (Claude or Codex through the Command
Center's MCP server) get table tools:
- `tables.list`, `tables.query(view, filter)`;
- `rows.create/update`;
- `buttons.press(row, button)`;
- `views.create`.

They run under the same approvals and caps, and every agent change is in the row history with the agent's name.

Making buttons is easy: "+ Button" → pick an action → pick a routine, skill or prompt from a searchable list → choose
where it runs → done. "Test on this row" runs it once before you save it.

---

## 5. Accounts, rebuilt: phones → profiles → apps → accounts

The Accounts tab stops being a flat list. It becomes a drill-down:

1. **Phones.** Only connected phones, with their state (today, your Pixel 8).
2. **Profiles** of that phone.
3. **Apps installed in that profile.** The phone's app inventory, per Android user; Atlas and App Manual status is
   shown per app (mapped, sign-up mapped, abilities).
4. **Accounts in that app,** as a table: handle, profile, owner basis (mine / company / client), status, 2FA,
   linked vault item, created by (you or a Cyclone run), last seen signed in, and buttons.

**How Cyclone knows which accounts exist:**
- **From its own runs:** a sign-up or sign-in mission records the handle.
- **Optionally, an "Account scan" button** per app. It opens the app's account switcher or profile page and reads
  the handles it shows. This is read-only; the App Manual knows where the switcher is.
- Nothing is guessed. An account row always links to the run or scan that proved it.

The old `CcAccount` rows migrate into the new **Accounts** system table (service → app; allowed devices → phone and
profile).

---

## 6. Account Setup mode (the phone's intelligence)

### 6.1 First time: map the sign-up

"Map sign-up" on an app (in Accounts → app) starts a **mapping run** with a real first sign-up for an account you
want. The owner stays reachable. Page by page, the Mind:

1. **Walks the flow as it really is,** with the owner's values for this first account (asked through the check-in
   card, plan 37).
2. **Records every page** into a **Sign-up Map**:
   - the page's identity (Atlas page key);
   - its fields: label, kind (name, date, email, phone, username, password, choice, checkbox, photo), required,
     format hints the app shows, and choices for pickers;
   - the control that continues;
   - branches ("Sign up with email" vs "with phone");
   - the page's checks (the username is taken, age limits).
3. **Marks verification steps** instead of passing them: email code, SMS code, CAPTCHA, selfie or ID, a phone call.
   Each becomes a **verification point** with its kind.
4. **Stops at the final submit for the owner's approval** (it creates an account in your name). After the approval it
   submits and records the result: handle, and the password sealed to the vault (plan 33 §4.4).

**Where the map lives:** on the phone, in the App Manual as a `sign_up` ability with a checked walk (plan 36). A copy
of its schema (fields only, never values) goes to the PC. The map is versioned with the app version.

**What Glass does with it:** it turns the map into a table, **"Instagram sign-ups"**:
- one property per field, typed from the map (First name → text, Date of birth → date, Gender → select with the
  app's own choices, Username → text);
- plus phone, profile, owner basis, a password link to the vault ("generate on the phone"), a verification contact
  (who does the checks, §7), status and run;
- default views:
  - a **board** by status: Draft, Ready, Queued, Creating, Needs verification, Created, Failed;
  - a **table**;
  - a **form**.

### 6.2 Every next time: fill a row, press Create accounts

1. **New account** (on the board, the table or the form) adds a row. You fill the columns.
2. **Checks before sending,** so a run never starts broken:
   - required fields are filled;
   - formats match the app's own hints;
   - the chosen profile has the app installed and is not already signed in to this app;
   - the owner basis is set.
3. **Create accounts** is one clear button in the accent colour (Glass purple). It sends every Ready row as a Command
   Center task to the row's phone and profile.
4. **The run is Account Setup mode.** The Sign-up Map is the plan and the row's values are the only values:
   - Page by page it checks the screen still matches the map, fills the fields and continues. It uses the Pilot or
     Flash for speed: the map is exactly the "full plan" plan 41 wants.
   - **Drift** (the app changed a page): the Mind handles that page. The run marks the map "changed on page 3" and
     offers to re-map. Values are never guessed.
   - **A verification point:** the run pauses on that page and raises a **verification handover** (§7). Other rows
     carry on.
   - **The final submit** runs on the owner's approval. Approving the batch counts, as one exact-payload approval per
     row (plan 33 §6 batching rule).
5. **While it runs:**
   - Each card shows its stage ("Filling · page 3 of 6", "Waiting for verification", "Created ✓").
   - **Pause** and **Cancel** work per row and for the whole batch, up to the final submit. After the submit, Cancel
     is gone (the account exists) and the row offers "Delete this account", which is a normal approved delete run.
   - When a run ends, the row gets the handle, the vault link, the account row in Accounts (§5) and a link to the run.

**How the phone knows to use this mode:**
- plan 42's router sends "create an account on Instagram for Brand X" or a Create-account button to Account Setup
  when a Sign-up Map exists for that app;
- without one, it offers to map it first (Glass shows "Map sign-up");
- the Mind uses it too, when its own mission needs a new account and the owner approves one.

### 6.3 Boundaries (code, not a model)

- **Only accounts you own or manage,** for you, your company or your clients (owner basis required). Each sign-up is a
  real account for a real brand or person who agrees to it.
- **Cyclone never gets around a check.** Email or SMS codes are read only from the owner's own inbox or number with
  permission (plan 29 direct actions). CAPTCHA, selfie, ID and liveness checks always go to a person (§7), never to a
  model or a solver.
- **Not for regulated identity apps.** Banks, payment services, money transfer, crypto exchanges and other apps with
  legal identity checks (KYC) are off-limits for Account Setup and batch creation, like the Pilot's `keepOff` list.
  Opening such an account is something the person does themselves.
- **No evasion.** It respects the app's own limits and pace: no device-identity spoofing, no rotating fake details, no
  bulk creation beyond what the app allows for one person or business. A row whose values match an existing account
  is refused.
- **Values:**
  - personal values go only into the fields they belong to;
  - passwords are generated in the vault and never shown in the table;
  - nothing personal reaches learning stores or diagnostics.

---

## 7. The Verification desk (handover to a real person, over MCP when needed)

Some steps need the person themselves: a selfie or ID check, an SMS to their number, a CAPTCHA, a call.

1. **The run pauses on that page** and raises a `handover` approval of kind `verification`, with the row, app,
   profile, the kind of check and a screenshot of the page (marked personal).
2. **In Glass,** the board has a **Needs verification** lane, and the Verification desk lists every open check with a
   **Hand over** button.
3. **Hand over** creates a **scoped session:**
   - It is a single-use link that expires in 10 minutes and is bound to that one handover.
   - It streams that one profile's screen and takes touch input for that one app. Leaving the app, or the task
     finishing the page, ends the session.
   - Cyclone's own input is paused on that screen; the phone shows "A person is verifying".
   - Recording is off, and the session start, end and who opened it are audited.
   - The session is sent to **the account's own holder** only: the row's verification contact.
4. **Through MCP:** the Command Center's MCP server gets `verification.list`, `verification.open(id)` (which returns
   the scoped link) and `verification.done(id)`. A service you connect (your own portal or support tool) can then give
   the person the link without Glass. The same scope, expiry and audit apply; the tools can't widen the scope.
5. **Done:** the person finishes, or presses "Done" on the page. The run re-observes the screen, checks the step
   passed, and continues. If it didn't pass, the handover opens again, and after two tries it asks you.

**Without interrupting Cyclone:**
- the verification holds only that row's run;
- other rows keep going on other phones, and on the same phone on another profile or background plane when the app
  allows it (plans 25 and 26);
- one screen of one profile belongs to the person until they are done.

---

## 8. Build order

| # | Milestone | Delivers | Tests and guards |
|---|---|---|---|
| **T1** | Tables core | Store and `/v1/cc/tables` routes; properties (text, number, currency, date/range, select, status, checkbox, email, URL); table and board views with filters, sorts, groups and saved views; row pages; history and undo | Gateway store tests; Glass `tables.test.ts`; guard: no secret cell type |
| **T2** | Relations and more views | Two-way relations, rollups, simple formulas; timeline, calendar, gallery and list; linked views; system tables (Phones, Apps, Accounts, Routines, Skills, Tasks, Runs); `@` rows | Rollup and formula tests; 10k-row timing test |
| **T3** | Buttons | Button property and page buttons; run routine / skill / prompt / update / open; Run property; result mapping; "Test on this row"; agent table tools on the CC MCP server | Guard: a button only creates tasks, never approves; template fill tests |
| **T4** | Profiles | Phone → profiles relay; the Profiles system table under Phones; `profileId` on tasks; the phone runs a task in its profile | Phone tests for profile targeting; guard: only Cyclone-owned profiles |
| **T5** | Accounts rebuilt | Phones → profiles → apps → accounts drill-down; per-profile app inventory; Account scan; migration of `CcAccount` | Migration test; scan is read-only (guard) |
| **T6** | Sign-up mapping (phone) | Mapping run; Sign-up Map in the App Manual; verification points; schema to the PC; Glass makes the table | Mapping tests on fake flows; guard: maps never hold values |
| **T7** | Account Setup runs | Pre-send checks; Create accounts; the run with the map as plan (Pilot / Flash); drift; batch approval of final submits; pause and cancel; results into Accounts | Run tests (fake phone) incl. drift and cancel before and after submit; guards: `keepOff` and regulated apps, no values in diagnostics |
| **T8** | Verification desk | `verification` handover; scoped single-use session; MCP tools; audit | Scope tests (the session can't leave the app, expires, single use) |
| **T9** | Lab and release | Two real apps mapped and filled end to end on a test profile; timings; release notes | Physical acceptance stated as UNVERIFIED until seen |

**Suggested releases:**
- tables first, T1–T3;
- then T4–T5;
- then Account Setup, T6–T8;
- then T9.

That makes three alphas after B1, or B1 moves again if you want this first.

---

## 9. Owner decisions

1. **Order:** build this before plan 39's B1 (so T1–T3 become alpha.78), or after it?
2. **Where the table data lives:** on the PC runtime, like the Command Center (recommended; the phone keeps only what
   it needs to act), or synced to the phone as well?
3. **Personal fields:** encrypted at rest and masked for agents unless a task names them (recommended), or plain?
4. **Which apps Account Setup may be used for:** everything except the regulated list (recommended), or only an
   allowlist you keep?
5. **The verification link:** Glass only at first, and MCP (§7.4) once the scoped session is proven (recommended), or
   both at once?

## 10. As built

**T1, Tables core (alpha.79)** (alpha.78 went to the other agent's voice fixes):

- **Gateway (`command/tables.py`):**
  - tables, properties, rows, views and row history in the Command Center's SQLite, under its lock and audit chain;
  - every T1 property type, with typed cell checks;
  - no property may be a password, and `INLINE_SECRET` text is refused;
  - views computed on the PC: filters with and/or, up to 5 sorts, board groups for status, select, multi-select and
    checkbox, and search;
  - undo reverts the last cell change;
  - type changes convert cells;
  - removing an option or a property cleans the rows and views that used it;
  - trash first;
  - CSV export without personal columns, and safe against formulas;
  - pages hold a `table` block (`tableId`, `viewId`) that is indexed for backlinks.
- **Routes:** `/v1/cc/tables…` behind the bearer; a stale save returns 409 `ROW_CHANGED`.
- **Glass:**
  - `services/tables.ts`: types, parsers, the API and pure helpers;
  - `workspace/tableView.ts`: the chooser, view tabs, the toolbar, the grid with inline editors, the board with
    drag-and-drop, property and option menus, and the row peek with its page and history;
  - `styles/tables.css`: Notion's option colours, in light and dark.

**Not built yet:**
- personal columns encrypted at rest (today they are marked and left out of exports);
- column resizing by dragging (widths are stored, but there is no handle yet);
- everything from T2 on.

**T2, cross-referencing (alpha.80):**
- **Relations** (`command/tables.py`):
  - they point at a table (two-way by default, with the way back made in the target, and both sides kept in step on
    every create, update, undo and delete) or at `sys:accounts|routines|tasks|phones`;
  - links must exist;
  - deleting a relation drops its way back and the rollups over it;
  - a table deleted for good drops the relations that point at it;
  - relations, rollups and formulas can't change type.
- **Rollups:** ten kinds, each with a `resultType`, so they filter and sort as numbers, dates or text.
- **Formulas:**
  - `command/formula.py` is a tokenizer and recursive-descent parser;
  - it has no eval, at most 1,000 characters and a depth limit, and treats a circle of formulas as empty;
  - the functions are `if`, `concat`, `round`, `floor`, `ceil`, `abs`, `min`, `max`, `length`, `lower`, `upper`,
    `contains`, `empty`, `format`, `toNumber`, `dateBetween` and `now`;
  - renames are rewritten into expressions.
- **Rows** come back with `links` (labels and where each id lives); `GET …/properties/{id}/candidates` feeds the
  picker.
- **Views:** `timeline`, `calendar`, `gallery` and `list`; `dateProp` defaults to the first date property.
- **Glass:**
  - link chips open the linked row from its own table (the peek names that table);
  - a relation picker with search;
  - relation, rollup and formula setup;
  - the timeline (a 42-day window of status-coloured bars), the month calendar, the gallery and the list.


**T5 + T6, Accounts rebuilt with sign-up mapping (alpha.81; profiles skipped at the owner's request):**
- **Phone** (`mind/signup/`):
  - `SignupMap` / `SignupRecorder`: pages, field labels, kinds, required, format hints, picker choices and the step a
    person must do (email or SMS code, CAPTCHA, selfie, ID document, phone call);
  - the recorder refuses anything typed in the mission (typed text and owner-filled values), anything that reads like
    a value (an address, a long number, a secret) and extra keys; at most 15 pages, 20 fields, 40 choices;
  - a mapping mission (`cc.start` with `signupMap`, only for an installed app, never with sealed secrets or a post)
    gets `signup_page`, `signup_final` and `signup_done`; `signup_final` asks the owner in code before the control
    that creates the account;
  - maps live in `Cyclone Brain/Signup/<package>.json`; `signup.maps` and `signup.forget` share them with the PC.
- **Gateway** (`command/signup.py`):
  - keeps the last maps per phone (validated again: strict keys, no values) and serves them when the phone is away;
  - starts mapping tasks (recipe `signup_map:<package>`, whose account: mine, company or client);
  - turns a map into a Cyclone Table: one column per field (passwords, codes and photos never), statuses Draft →
    Created / Failed, Whose account, Phone and Cyclone account relations, Notes; one table per phone and app.
- **Glass:** Accounts is phones → installed apps (accounts and sign-up state first) → an app's accounts, its map page
  by page, Map the sign-up, and the sign-up table inline; the list of all accounts stays below.
- **Not yet:** the Verification desk (T8), profiles (T4).

**T7, Create accounts (alpha.82).** At the owner's request (2026-09-30), none of §6.3's pre-send checks, app block
list or pacing caps for now; special cases are planned later. What stays: a person's step (code, CAPTCHA, selfie, ID)
pauses the row for a person and is never solved; no device spoofing or evasion; passwords only in the vault.
- **Gateway** (`command/signup.py`):
  - `prepare` makes each Ready row's Cyclone account (or reuses the one from an earlier try);
  - `create` starts one task per row (recipe `signup_run:<package>`, the row's phone, its account and vault item);
  - at dispatch the phone gets `signupRun {package, values}` (the row's current values by field key, never a password);
  - `sync` (every engine tick) writes Status and a Progress column from the task and the phone's `setup` progress;
  - on success: the handle onto the account, and the row's Cyclone account link;
  - `pause` (Paused; Ready starts again from the beginning, same account) and `cancel` (Failed), per row or table.
- **Phone:**
  - `AccountSetupPlan` puts the map and values in the prompt (password: `vault_fill`);
  - `setup_page` (page, changed, check) and `setup_done` (created, handle, why) are offered only in this mode;
  - `cc.status.setup` carries the progress;
  - the final control is pressed without asking again: the press in Glass approved it.
- **Glass** (`pages/createAccounts.ts`):
  - Create accounts lists the Ready rows; confirming with the vault passphrase generates one password per account
    into the vault (encrypted in the tab) and starts the runs;
  - the tab seals each password to its phone when that task asks, then drops the key (after 15 minutes at most);
  - Pause all, Cancel all.
- **Not yet:**
  - branches in a map;
  - offering a re-map after drift (it is shown in Progress);
  - the Verification desk (T8).

**T3, action buttons (alpha.83):**
- **Gateway** (`command/buttons.py`):
  - a `button` property (computed, can't change type): a label, a colour and up to 6 actions: `routine`, `prompt`
    (a template: `{{Property}}`, `{{Relation.Property}}`), `skill` (a saved skill by name, optional inputs
    template), `update` (cells, with `@today` / `@now` for dates) and `open` (an http(s) link);
  - at most one phone action per button;
  - `runOn`: any ready phone, one phone, or the row's relation to Phones;
  - `then`: cells on success or failure, and the run's summary into a text property;
  - checked against the table when saved (the properties, routine and template names exist; values fit);
  - a press (`POST …/rows/{row}/buttons/{prop}`) runs the updates, starts one task or routine run, and records a
    `button_run`; a running button can't be pressed again;
  - every tick, `sync` follows the tasks and writes the result back;
  - the cell is the latest run (`state`, `label`, `taskIds`, `summary`); the row peek has `buttonRuns`.
- **Agents:** the Command Center AI gets `list_tables`, `query_table` (personal columns hidden), `create_row` and
  `update_row` (workspace edits; personal columns are the owner's), and `press_button` (a proposal the owner applies).
  The PC agent MCP servers still don't reach Command Center routes (existing guard).
- **Glass:**
  - `workspace/buttonEditor.ts`: label, colour, actions, where it runs, and after the run;
  - the table cell is a coloured button with its latest run;
  - `open` links open in a new tab (http(s) only).
- **Guard** (`test_table_buttons_guard.py`): buttons never answer approvals, never touch the vault or sealed secrets,
  and start work only through tasks and routines.
- **Not yet:**
  - page buttons outside tables;
  - a "Create account" action;
  - a separate "Test on this row" (pressing on one row is the test).

**T4, profiles from the PC (alpha.84):**
- **Phone** (`runtime/workspaces/ProfileApps.kt`, `gateway/GatewayV5ProfilesAdapter.kt`):
  - `profiles.list`: Profile A plus Cyclone's profiles (label, emoji, colour, ready, in front, in trash);
  - `profiles.apps`: a profile's third-party apps and, for a Cyclone profile, the ones Profile A could give it;
  - `profiles.switch`: prepare Cyclone there, `am switch-user`, wait for Android to confirm. It refuses while a task
    runs or a request waits for approval, and works whichever profile is in front;
  - `profiles.app`: `install-existing` from Profile A, or `pm uninstall --user` for that profile only;
  - two new fixed verbs: `LIST_PROFILE_APPS` and `UNINSTALL_FOR_PROFILE`;
  - every call re-checks `ProfileRecovery.validOwned` against Android's user list and the registry;
  - nothing creates or deletes a profile, and Profile A's apps stay the owner's on the phone;
  - a switch from the PC doesn't carry memory; only the phone's own switch does (Cyclone Carry guard).
- **PC:**
  - the contract checks every result and request;
  - routes `GET /v1/devices/{id}/profiles`, `GET …/profiles/{p}/apps`, `POST …/profiles/{p}/switch` and
    `POST …/profiles/{p}/apps {package, action}`.
- **Glass:**
  - Accounts shows the phone's profiles, the one in front, **Switch the phone to …**, and an app manager for a
    Cyclone profile;
  - the app list follows the chosen profile.
- **Guard:** `test_profile_apps_guard.py`.
- **Not yet:**
  - tasks and buttons that name a profile (a mission in profile B may need profile B's own Cyclone; to check on a
    device);
  - Profiles as rows under Phones in tables;
  - filtering work by profile.

**Fix, sign-up mapping you can see and stop (alpha.85):**
- **Glass** (`pages/accountsView.ts`, `services/signup.ts`): while a mapping runs, Accounts says what it is doing
  (waiting its turn, waiting for the phone and why, working, or waiting for your answer in Inbox) and offers
  **Cancel mapping** and **Open Inbox**; after a cancelled or failed try it shows the cause and **Map it again**.
- **Phone** (`MindMissions.startAssigned`): a mapping or Account Setup run never starts behind another task on a
  background screen; it takes the front or answers `ASK_BUSY`, and the PC waits and retries.

**Fix, Lab tools on the packaged MCP (alpha.86):** `CycloneAgentMCP.exe` serves `tools/codex-phone-mcp`
(`cyclone_phone_mcp.mcp_server`); the Lab tools now live there too (`tests/test_lab_tools.py`).
