# Cyclone V5 Alpha 81: Accounts, rebuilt, with sign-up mapping

Developer alpha for owner testing. It builds on Alpha 80 (Tables that link) and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.81.dev1` (version code 226).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.81.dev1.exe` (runtime `5.0.0-alpha.81.dev1`).
- **Glass:** `1.0.0-alpha.43`.

This is plan 43's Accounts rebuild and sign-up mapping together (T5 + T6). As you asked, profiles are skipped for
now: Accounts goes phone → apps → accounts.

## What changed

**Accounts in Glass is now phone → apps → accounts.**
- The top of the page shows your connected phones. Pick one.
- On the left are the apps installed on that phone. Apps with accounts or a mapped sign-up come first, and you can
  search by name or package.
- Click an app to see:
  - the accounts you have in it;
  - where its sign-up stands: not mapped, mapping, partly mapped or mapped.
- The list of all your accounts stays below, with **New account** as before.

**Map the sign-up.** On an app without a map:
1. Say whose the first account is: yours, your company's, or a client's you manage. Cyclone only makes accounts you
   own or manage.
2. Press **Map the sign-up**. This starts a task on that phone.
3. The phone opens the app and walks its sign-up, creating your first account there:
   - It asks you for each value on the phone or in Inbox. It never makes values up.
   - Passwords go through the phone's secure card. Cyclone never sees them.
   - On every page it records what the page asks for: each field's label, its kind (email, name, birthday, a
     choice…), whether it's required, the app's format hint, and a picker's options.
   - It never records a value. The phone refuses anything that was typed, anything that looks like an address, a
     phone number or a secret, and anything extra. The PC checks all of this again.
   - A page only a person can do (an email or SMS code, a CAPTCHA, a selfie, an ID check, a call) is marked, and the
     phone hands it to you. Cyclone never tries to solve one.
   - Before the button that creates the account, the phone asks you **Create the account / Not now**. This is enforced
     in code, whatever the model decides. If you say Not now, the map is kept as far as it got.

**The map in Glass.** An app's map shows each page in order: its fields with their kinds, the steps that are yours,
the button that goes on, and the final button. Maps are kept on the PC too, so they still show, with a note, when the
phone is away. **Forget this map** lets you map the app again.

**The sign-up table.** Press **Make the sign-up table** and Cyclone makes a Cyclone Table called "<App> sign-ups":
- one column per sign-up field, never passwords, codes or photos;
- names, email, phone, birthday and gender columns are marked personal;
- pickers (like gender) become select columns with the app's options;
- **Status** (Draft, Ready, Queued, Creating, Needs verification, Created, Failed), **Whose account**, **Phone** (a
  link to the phone), **Cyclone account** (a link to the account once it exists) and **Notes**.

Each row is an account to create next. The table opens right in the app's page, with every view, filter and link
from Alpha 79 and 80.

## Not yet (the next plan 43 builds)

- **Create accounts** from the table's Ready rows, with pause and cancel (T7). For now the table is where you line
  them up.
- The Verification desk for codes and ID checks over MCP (T8).
- Action buttons on rows (T3).
- Profiles (T4).

## Tests

- Phone:
  - `SignupMapTest` (4): an Instagram-style sign-up becomes a map; nothing typed ever enters it; kinds, checks and
    limits are strict; a map stopped early is not complete;
  - `GatewayV5SignupAdapterTest`: maps go out as schemas and can be forgotten;
  - `GatewayV5CommandAdapterTest`: a mapping task starts with its app and nothing else.
- Gateway, `test_command_signup.py` (new, 4):
  - a map is a schema and never a value;
  - a mapping task names its app and whose account it is, and reaches the phone as `signupMap`;
  - maps are kept for when the phone is away;
  - a map becomes a table without passwords or codes.
- Glass, `signup.test.mjs` (new, 5):
  - parsing and columns;
  - the app ordering;
  - phone → app → map → table;
  - starting a mapping task with whose account it is;
  - the phone-away note.
- Full suites:
  - gateway: all pass;
  - MCP: all pass;
  - Glass: 246 of 246 pass, build and guard clean;
  - CI guards: 244 pass, with the Mind tool count now 43 (the three sign-up tools are offered only in a mapping
    mission).

## Physical acceptance

UNVERIFIED. Not yet run on a phone. The first real test is mapping a sign-up for an account you own.
