# Cyclone V5 Alpha 38 — Mapping missions from Glass

Developer alpha for owner testing, built on Alpha 37 (runs from the map), which it includes. Mobile is
`5.0.0-alpha.38.dev1` (version code 180), Windows companion is `1.6.0-alpha.38`, and the bundled Glass web app is
`1.0.0-alpha.21`.

## What changed

**Map an app on purpose, from Glass.** On an app's page in Glass, **Start mapping** (or **Map again**) opens a sheet
with two choices:

- **Whose account.** *My account — look only* walks what the phone already shows with your signed-in account. It
  never signs in or out and never opens account or security pages; if the app shows a sign-in screen, the pass ends
  there and says so. *Test account* also maps sign-in, sign-up and onboarding screens; passwords only ever go through
  the phone's Secrets Card.
- **How long.** 10 minutes, 30 minutes or 2 hours (up to 60, 200 or 500 new places). The phone enforces the budget.

While it runs, the board shows a **live mission panel**: time against the budget, new places, verified moves, doors
tried, where the phone is now and what it just found. When it ends, a **report** says what the pass added (places,
doors, zones, scenarios), how many doors it refused, and why it ended, with *View map*, *Map again* and *Map deeper*.

**It never changes anything.** Mapper safety v2 (on the phone, `MapperDoorRisk`) adds to the existing pay / send /
delete / permission gate: no toggles, switches, checkboxes or settings rows that hold one; no social or state actions
(follow, like, subscribe, join, save, install, block, report, …); no security areas (password, two-factor, passkeys,
sessions, delete or deactivate account), not even to look. In *look only*, account and sign-in doors are refused too.

**A map you can read.** The Map tab opens on an **overview**: the app's scenarios (Signed out → Sign in → Signed in)
and its **zones** (Home, Search, Messages, Profile, Settings…), each with places, doors, confidence and blocked doors.
Click a zone to see its places laid out level by level; *All places* keeps the full map. The **place inspector** lists
the doors from a place with their confidence, and for a door, the runs that walked it.

**Honest coverage.** A new **Coverage** tab shows scenarios known, zones, places, doors, average confidence, doors not
yet confirmed, doors refused for safety and the share seen on the installed version. It never claims a percentage of
"the whole app": nobody knows how big an app is.

**The fleet.** The Apps page is now a fleet view: per app, what Cyclone can do (*Routing ready*, *Needs attention*,
*Partial*, *Unmapped*), places and doors, confidence (doors seen on the installed version), freshness (*Current* or
*Map from v…*), what is happening now (*Mapping…*, last run) and an action (*Start mapping* / *Open* / *Watch*).
Filters with counts; sort by activity, least mapped or name.

**Run replay.** The run inspector steps through a run like a film: *Step N of M*, previous / next and the arrow keys,
a filmstrip of steps, and for each step what the map expected, what was done, where the phone landed, how it ended and
who chose it (map, model or mapper), with a link to those places on the map. Replay is structural: no screenshots.

## Validation and limits

Android unit tests (door risk classes, identity through start / job / report, look-only stop at a sign-in wall, test
account secret pause), lint, gateway contract (identity validation, refused on resume), both MCP suites, Glass (zones,
coverage, overview, start sheet, live panel, report, fleet, replay) and companion tests, and the CI guards pass. Glass
pages were checked rendered in Chromium against fixtures. **Physical Pixel 8 acceptance is UNVERIFIED**: a real
mission on a real app is the first thing to try.

Install the APK as an update; do not uninstall to work around a signing mismatch. The developer-alpha publisher
verifies the historical development certificate against the previous release; that signer is update-compatible but
exposed in repository history and unsuitable for production. The Windows installer is not Authenticode-signed.

Suggested checks:
1. Glass → Apps → an app you use (for example Settings or Clock) → **Start mapping** → *My account — look only*,
   *10 min*. Watch the live panel, then read the report.
2. Open the Map tab: the overview, a zone, a place and its doors. Open Coverage.
3. Glass → Runs → any run → step through with ← and →.
