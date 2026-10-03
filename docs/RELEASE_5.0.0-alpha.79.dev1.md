# Cyclone V5 Alpha 79: Cyclone Tables

Developer alpha for owner testing. It builds on Alpha 78 (voice mode that stays voice mode) and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.79.dev1` (version code 224). The phone app is unchanged except for its version.
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.79.dev1.exe` (runtime `5.0.0-alpha.79.dev1`).
- **Glass:** `1.0.0-alpha.41`.

This is the first build of plan 43 (T1, Tables core). The Command Center gets real tables, like Notion's databases.
You design them yourself, fill them in, and look at the same rows in several views. It is the foundation for what
comes next: cross-references, action buttons, profiles, and Account Setup.

## What changed

**Make a table.** In any Command Center page, type `/table`.
- **New table** makes one with a Name and a Status column, plus a Table view and a Board view.
- Or pick a table you already have, so the same table can show on several pages.

**Properties (columns)** are typed, and you choose them with **+** in the header:
- text, number, currency (€ and others), percent;
- date, with an optional end date and time ("May 28 → Jun 7");
- select, multi-select, and status (grouped To do / In progress / Complete), with your own coloured options;
- checkbox, email, web address, phone;
- created time and edited time.

Each column's menu can:
- rename it or change its type (cells are converted where that makes sense);
- edit its options and their colours;
- choose a currency;
- mark it **personal**, which leaves it out of exports;
- sort or filter by it, hide it, or delete it.

**Views.** A table has as many saved views as you like. The view tabs work like "All Accounts / Board / Pipeline by
Status":
- **Table:** a grid you edit in place. Click a cell to change it; a choice cell lets you pick an option or create a
  new one as you type.
- **Board:** cards grouped by a status, select or checkbox. Drag a card to another column to change it, and use
  **+ New** in a column to start a card there.
- Each view keeps its own **Filter** (match all or any), **Sort**, **Group** and visible **Properties**.
- **Search** and **New** are always at hand.
- The PC computes every view, so a big table stays fast in the browser.

**Every row is a page.** **Open** on a row, or a card, shows the row on the side:
- all its properties, editable;
- its own page underneath, for notes and any blocks;
- its history (who changed what, and when), with **Undo last change**.

**Safety:**
- A table never keeps a secret. Text that looks like a password, code or key is refused, and no column may be a
  password: the vault keeps those.
- Deleting a row or a table moves it to the trash first.
- **Export this view as CSV** leaves personal columns out, and never lets a cell start a spreadsheet formula.

## Not yet (the next plan 43 builds)

- Relations between tables (click a bank to see its customer and orders), rollups and formulas.
- Timeline, calendar and gallery views.
- Cyclone's own records as tables: phones, profiles, apps, accounts, routines, skills.
- Action buttons on rows (run a routine, a skill or a prompt on a phone), and table tools for AI agents.
- Encrypting personal columns at rest. Today they are marked and kept out of exports only.
- Profiles, Accounts rebuilt, Account Setup mode and the Verification desk.

## Tests

- Gateway, `test_command_tables.py` (new, 12):
  - typed cells and refused secrets;
  - views that filter, sort and group on the PC, with a date range matching the days in it;
  - moving a card and undo;
  - row pages;
  - type conversion;
  - options and properties removed cleanly;
  - trash first;
  - CSV without personal columns or formulas;
  - a page showing a table;
  - routes behind the bearer, with conflicts reported.
- Glass, `tables.test.mjs` (new, 9):
  - dates, money and input parsing;
  - board groups and moving a card;
  - editing a cell in place;
  - a secret never sent;
  - a drop that moves a card;
  - New in a board column;
  - making a table from an empty block.
- Full suites:
  - gateway: all pass;
  - Glass: 234 of 234 pass.

## Physical acceptance

UNVERIFIED. Not yet used in a real Glass on the owner's PC:
- building a table;
- the views;
- dragging cards;
- the row peek.
