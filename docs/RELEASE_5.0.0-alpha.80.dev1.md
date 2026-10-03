# Cyclone V5 Alpha 80: Tables that link

Developer alpha for owner testing. It builds on Alpha 79 (Cyclone Tables) and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.80.dev1` (version code 225). The phone app is unchanged except for its version.
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.80.dev1.exe` (runtime `5.0.0-alpha.80.dev1`).
- **Glass:** `1.0.0-alpha.42`.

This is the second build of plan 43 (T2): cross-referencing. Tables now point at each other. In the example from your
ledger, an order links to its bank; click the bank and you see its customer, every order that used it, and the
totals.

## What changed

**Relations.** Add a **Relation** property and choose where it points:
- **Another table.** It is two-way by default: the other table gets the way back, named after this table. Linking an
  order to a bank also puts the order on the bank's row, and unlinking removes it from both.
- **Cyclone's own records:** Accounts, Routines, Tasks or Phones. A brand can link to its Instagram account and to
  the phone it lives on.

**Linked rows read by name.** Click one and it opens on the side, from its own table. You see:
- its properties and its own page;
- its links (an order's bank, a bank's orders), each clickable again.

A picker with search links rows. Links must point at rows that exist, and deleting a row, or the relation itself,
cleans up both sides.

**Rollups** calculate over a relation:
- count all, or count the values;
- sum, average, min or max;
- earliest or latest date;
- show the original values;
- percent checked.

For example, a bank's total is the sum of its orders' estimates, and its order count is a count.

**Formulas** in a small language:
- `prop("Estimate") * 1.21`;
- `if(prop("Paid"), "paid", "open")`;
- `concat(prop("Bank"), " | ", round(prop("Estimate")))`;
- `dateBetween(now(), prop("Charge"), "days")`.

They are worked out on the PC, never by a model and never with eval. When you rename a property, the formulas that
use it follow. You can filter and sort by rollups and formulas like any property.

**Four new views**, next to Table and Board:
- **Timeline:** each row is a bar from its start date to its end date, coloured by its status, with ‹ Today › to move
  through the weeks.
- **Calendar:** a month, with rows on their days; **+** on a day adds a row on that date.
- **Gallery:** cards.
- **List:** compact rows.

Timeline and Calendar lay rows out by a date property you choose (**Date**, in the toolbar).

## Not yet (the next plan 43 builds)

- Action buttons on rows (run a routine, a skill or a prompt on a phone), and table tools for AI agents.
- Profiles as part of Phones, the Accounts rebuild, Account Setup mode and the Verification desk.
- Linking rows by typing `@` in page text.
- Encrypting personal columns at rest.

## Tests

- Gateway, `test_command_tables_links.py` (new, 7):
  - two-way links, with both sides' history;
  - links must exist, and deleting cleans up both sides;
  - every rollup kind, with filtering and sorting by one;
  - formulas: arithmetic, `if` over a status, `dateBetween`, renames, and errors refused;
  - relations to Cyclone's accounts and phones;
  - timeline and calendar dates;
  - the formula language refusing anything that isn't a formula.
- Glass, `tables-links.test.mjs` (new, 7):
  - calendar and timeline dates;
  - how rollups, formulas and links read;
  - a linked bank opening from its own table with its orders and total;
  - linking a row through the picker;
  - timeline bars;
  - the calendar and the gallery;
  - making a relation.
- Full suites:
  - gateway: all pass;
  - Glass: 241 of 241 pass;
  - CI guards: 244 pass.

## Physical acceptance

UNVERIFIED. Not yet used in a real Glass on the owner's PC.
