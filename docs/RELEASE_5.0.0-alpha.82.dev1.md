# Cyclone V5 Alpha 82: Create accounts

Developer alpha for owner testing. It builds on Alpha 81 (Accounts with sign-up mapping) and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.82.dev1` (version code 227).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.82.dev1.exe` (runtime `5.0.0-alpha.82.dev1`).
- **Glass:** `1.0.0-alpha.44`.

This is plan 43's T7. A sign-up table now creates the accounts in it. As you asked, it has no extra rules for now:
- no format or duplicate checks before sending;
- no list of apps it refuses;
- no limits beyond one account at a time per phone.

We plan the special cases later.

## How it works

1. **In Accounts:**
   - open an app with a mapped sign-up;
   - show its sign-up table;
   - fill a row per account and set its **Status** to **Ready**. Whose account defaults to Mine, and the Phone column
     defaults to the phone that mapped the sign-up.
2. **Press Create accounts** (purple, above the table). It lists the Ready rows, and anything that can't start says
   why, for example "That account is already in the Command Center".
3. **Type your vault passphrase and confirm.** Confirming is your approval to create each of these accounts, so the
   phone presses the final button without asking again. For each account, Glass:
   - makes a new 20-character password in your vault, encrypted in the browser and linked to a new Cyclone account for
     that row;
   - starts a task on the row's phone.
4. **Keep the page open for a moment.** When each phone's task asks for its password, Glass seals it to that phone for
   that task only. The page drops the vault key once every password is sent, or after 15 minutes.
5. **On the phone,** Account Setup mode runs the sign-up:
   - the map is the plan and the row gives the values, page by page;
   - the password is filled from the vault, and the AI never sees it;
   - if a page changed since the map, the AI works that page out, and the row says which page changed.
6. **Codes, CAPTCHAs, selfies and ID checks** pause that row as **Needs verification** and wait for you. Other rows and
   other phones keep going. Cyclone never tries to solve these.
7. **The row follows along.** Status goes Queued → Creating → Needs verification → Created or Failed. **Progress**
   shows the page ("Page 3 of 7"), a changed page, the step waiting for you, or why it failed. Once an account is
   created:
   - its handle goes onto the Cyclone account;
   - the row links to that account;
   - the password is in your vault.

**Pause all** puts running rows back to Paused; set one to Ready and it starts again from the beginning with the same
Cyclone account and password. **Cancel all** stops them and marks them Failed. After an account exists there is
nothing left to cancel.

## Before you try it

- Map the app's sign-up first (Alpha 81).
- The vault must exist, and the phone's key must be trusted (Command Center → Vault → Phones). Otherwise the password
  can't be sealed and the row waits.

## Not yet

- The Verification desk: handing a code or ID step to someone else, over MCP too (T8).
- Branches in a sign-up (email or phone).
- Offering a re-map after a changed page. The Progress column already shows which page changed.
- Checks before sending, app rules and limits: to plan later, as you asked.

## Tests

- Gateway, `test_command_signup.py` (now 8):
  - Ready rows run with their values by field key (never a password);
  - Status and Progress follow the run through Queued, Creating (page 3 of 7), Needs verification (a changed page and
    an email code) and Created;
  - on success the handle goes onto the account, and the row links to it;
  - pause and cancel, and Ready again reuses the same account;
  - only sign-up tables can create accounts;
  - the phone's progress report is checked.
- Phone:
  - `AccountSetupTest` (3): the plan names every page and value but never a password; strict values; the progress
    shape;
  - `GatewayV5CommandAdapterTest`: an Account Setup run starts with the map and the row's values; an unmapped app or
    bad values are refused.
- Glass:
  - `createAccounts.test.mjs` (3), with a real vault and real sealing: confirming makes one vault password per account
    and starts the runs; the password is sealed to the phone once its key is trusted and opens there; it appears in no
    other request;
  - no Ready rows;
  - Pause all.
- Full suites:
  - gateway: all pass;
  - Glass: 249 of 249 pass, build and guard clean;
  - CI guards: 244 pass, with the Mind tool count now 45 (`setup_page` and `setup_done` are offered only in Account
    Setup).

## Physical acceptance

UNVERIFIED. Not yet run on a phone. The first real test: map a sign-up, add one Ready row for an account you own, and
press Create accounts.
