# Cyclone V5 Alpha 83: Action buttons

Developer alpha for owner testing. It builds on Alpha 82 (Create accounts) and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.83.dev1` (version code 228). The phone app is unchanged except for its version.
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.83.dev1.exe` (runtime `5.0.0-alpha.83.dev1`).
- **Glass:** `1.0.0-alpha.45`.

This is plan 43's T3. Every table row can now have buttons. One click sets a phone to work for that row: run a
routine, ask something new, run a saved skill. A button can also update the row or open a link.

## How it works

**Add a button:**
1. In any table, open **+** (new property) and choose **▶ Button**.
2. Give it a label and a colour.
3. Add what it does, up to six actions:
   - **Ask a phone (prompt):** write the request as a template filled from the row. For example,
     `Check WhatsApp for messages from {{Customer}} and summarise them`, or through a relation:
     `Check the payment at {{Bank.Name}}`.
   - **Run a routine:** one of your Command Center routines, now, on its own phones.
   - **Run a saved skill:** by name, with optional inputs from the row.
   - **Set a property:** for example Status → Checked, or a date to Today or Now.
   - **Open a link:** opens in a new tab.

   A button runs at most one phone action (a prompt, a skill or a routine); add another button for more.

**Where it runs:**
- any ready phone;
- one phone you pick;
- the phone linked in the row (a relation to Phones), so each row can run on its own phone.

**After the run:**
- set the Status to one option on success and another on failure;
- write the phone's result into a text property such as Notes.

**Press it.** The cell shows the button and its latest run: Queued, Running, Needs you, Done ✓ or Failed. The run's
summary shows when you hover it. While a run is going, the button waits, so it can't start twice.

**Safe by design:**
- A press only starts an ordinary Command Center task.
- The phone still asks you before paying, sending, deleting, changing permissions or signing in.
- A button never approves anything, and never uses the vault.

This is checked in CI.

**Agents use the same tables.** The Command Center's AI can:
- list your tables;
- read a table's rows, with personal columns hidden;
- add and change rows;
- propose pressing a button. You apply the proposal, as with any phone work it suggests.

## Not yet

- Buttons on pages, outside tables.
- A "Create account" action (use Create accounts on the sign-up table for now).
- The Verification desk (T8) and profiles (T4).

## Tests

- Gateway, `test_command_buttons.py` (new, 5):
  - a prompt button filled from the row and through a relation, on the row's phone, that sets a date, writes back
    Status and the summary, and can't be pressed twice while it runs;
  - routine, skill, update and open buttons;
  - buttons are checked against their table;
  - a row without its phone says so;
  - agents read tables (personal columns hidden), propose presses and add rows.
- Glass, `buttons.test.mjs` (new, 3):
  - parsing and the run state;
  - pressing a row's button: it starts, opens only http(s) links, shows Queued and waits;
  - the button editor.
- CI guard, `test_table_buttons_guard.py` (new, 3): a button never approves, never touches secrets, and starts work
  only through tasks and routines.
- Full suites:
  - gateway: all pass;
  - Glass: 252 of 252 pass, build and guard clean;
  - CI guards: 247 pass.

## Physical acceptance

UNVERIFIED. Not yet used in a real Glass with a phone.
