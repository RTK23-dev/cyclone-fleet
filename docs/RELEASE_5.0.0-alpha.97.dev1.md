# Cyclone V5 Alpha 97: the Port map

Developer alpha for owner testing. It builds on Alpha 96 and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.97.dev1` (version code 242). No phone changes.
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.97.dev1.exe` (runtime `5.0.0-alpha.97.dev1`).
- **Glass:** `1.0.0-alpha.54`.

## What's new

Run 2 of Cyclone Ports (plan 48): you decide which plugin serves each port, either for every run or for one routine
or one app.

Open Glass → Command Center → Ports → **Port map**:
- **A switchboard.**
  - Ports are on the left and plugins on the right, with a line for every route.
  - **Solid** lines are the routes runs use.
  - **Dashed** lines go to plugins that could serve a port but don't there.
  - **Amber** lines mean the port needs your choice.
- **Who serves what:**
  - **From the phone** (events, notes, screenshots, account details, files): automatic sends to every live plugin you
    allowed. You can choose some of them, or switch the port off.
  - **To the phone** (files, values, codes, links): a waiting run takes one answer. With one live plugin, that's the
    one. With two, say an SMS plugin and a mail plugin that can both deliver codes, the port waits for you to choose.
    Nothing answers meanwhile. It is never "whichever is faster".
- **Per routine or app.** Pick "For a routine or app" to give a routine, or an app being set up (like
  `com.instagram.android`), its own choices. Ports without their own choice follow Everywhere.
- **Honest when something breaks.** If the plugin you chose isn't live (paused, failing or removed), the port shows
  **Unavailable** and nothing gets through. It is never quietly sent to another plugin. Removing a plugin takes it
  out of every choice.
- **Plugins view:** a port that needs your choice appears under **Needs you**, with a link to the map.

Runs don't send to plugins yet. Run 3 (live traffic) sends along exactly these routes; the gateway already answers
"what would a run with this routine and app reach" (`/v1/ports/resolve`).

## Verified

- Port Hub tests 9/9 (2 new for bindings), full gateway suite, CI guards and kit tests: green.
- Glass: 285 tests (3 new for the map), typecheck, build and the Glass guard: green.
- In a real browser (light and dark), with five live plugins including two that both deliver codes:
  - the conflict showed;
  - choosing SMS codes settled it;
  - an app-specific choice routed run events to one plugin only.

## Not verified

- On the owner's Windows PC: **UNVERIFIED**.
- No phone changes.
