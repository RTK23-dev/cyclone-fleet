# Cyclone V5 Alpha 98: Ports carry real traffic

Developer alpha for owner testing. It builds on Alpha 97 and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.98.dev1` (version code 243). No phone changes.
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.98.dev1.exe` (runtime `5.0.0-alpha.98.dev1`).
- **Glass:** `1.0.0-alpha.55`.

## What's new

Run 3 of Cyclone Ports (plan 48): messages now flow between runs and your plugins through the Port Hub on your PC.

**Try it:** Glass → Command Center → Ports → **Activity** → **Start a test run**. Pick one:
- **Run events:** a run starts, writes a note and finishes. Every plugin on those ports gets them.
- **Sign-up with a code:**
  - test details (Sam Example) and a screenshot go out, then the run waits for a verification code;
  - send a text to the phone your SMS plugin watches, and watch the step turn green: "a 6-character code, would be
    sealed to the phone";
  - Glass never sees the code, and a test run drops it at once.
- **An image from the PC:** the run waits for an image from your PC image plugin.
- **A value from a plugin:** the run waits for a value, like a caption.

Each step lands live: sent to which plugins, what came back, or why not (no plugin, a choice to make on the Port
map, the port switched off).

**Under the hood:**
- **Messages follow the Port map** for the run's routine and app. Screenshots and files go as one-time links that work
  for 2 minutes. A plugin that doesn't answer gets three tries; a failure is logged and never blocks the run.
- **Waits survive a restart.** If the PC runtime restarts while a run waits for a code, it asks the plugin again with
  the same wait, and the run keeps waiting. A wait that runs out of time tells the plugin to stop.
- **Plugins answer at one address**, with the run's own single-use token and the contract's answers:
  - a repeat of the same delivery is accepted again;
  - a second, different answer is refused.
- **Nothing secret is kept:**
  - a code stays in memory only until the run takes it (once, at most 5 minutes) and is never written anywhere;
  - the log and the database keep metadata only.
- **Activity:**
  - filter by plugin, port and outcome;
  - every run that used a port opens its lane: what went out, what it waited for, what answered, in order.

Phone runs connect to this in run 4: the Mind's `port_send` / `port_wait`, and a sign-up waiting on a code fills it in
by itself.

## Verified

- 6 new end-to-end tests over real HTTP with the kit's example plugins:
  - a sign-up with a real SMS code, which is never written to disk;
  - an image from a folder;
  - the contract's status codes;
  - a wait that survives a restart and times out.
- Full gateway suite, CI guards and kit tests: green.
- Glass: 286 tests, typecheck, build and the Glass guard: green.
- In a real browser, a sign-up test run with five live plugins and a real SMS went through every step.

## Not verified

- On the owner's Windows PC: **UNVERIFIED**.
- No phone changes yet.
