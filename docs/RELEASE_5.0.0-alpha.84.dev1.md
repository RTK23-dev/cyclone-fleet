# Cyclone V5 Alpha 84: Profiles from the PC

Developer alpha for owner testing. It builds on Alpha 83 (Action buttons) and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.84.dev1` (version code 229).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.84.dev1.exe` (runtime `5.0.0-alpha.84.dev1`).
- **Glass:** `1.0.0-alpha.46`.

This is plan 43's T4. Glass now knows your phone's profiles and can run them from the PC.

## What changed

**Profiles under the phone.** In Command Center → Accounts, pick a phone and its profiles show up underneath:
Profile A and every profile Cyclone made, each with its emoji and colour. The one on the phone's screen says
**In front**. A profile in Recently deleted stays out of the list.

**Switch profiles from the PC.** Pick a profile and press **Switch the phone to …**. The phone:
- gets Cyclone ready in that profile;
- asks Android to switch;
- waits until Android confirms, then Glass shows the new profile in front.

It works whichever profile is in front now, including back to Profile A. It waits while Cyclone is running a task,
or while a request on the phone is waiting for your approval.

Your memory and skills still travel only when you switch on the phone itself. A switch from the PC only puts the
profile in front.

**Manage a profile's apps.** For a profile Cyclone made, **Manage …'s apps** shows:
- **Apps in the profile,** each with **Remove**. Removing takes it out of this profile only; it stays in Profile A.
- **Add an app Profile A has.** Android copies the app Profile A already has into the profile, with no store and no
  download.

Profile A's own apps are managed on the phone.

**The app list follows the profile.** With a Cyclone profile chosen, Accounts lists that profile's apps. Its accounts
and sign-up maps work as before.

**Only Cyclone's own profiles.** Every action re-checks that the profile is the exact one Cyclone created (Android's
user list and Cyclone's registry). Nothing here creates or deletes a profile; that stays on the phone. The root
commands are fixed verbs, with no free shell.

## Needs

Profile switching and app changes use the same root access the phone's Profiles page uses. Without it, Glass shows
the phone as one profile, as before.

## Not yet

- Tasks and buttons that run in a chosen profile. A mission in profile B may need profile B's own Cyclone; this has
  to be checked on a device first.
- Profiles as rows under Phones in tables, and filtering work by profile.
- The Verification desk (T8).

## Tests

- Phone: `GatewayV5ProfilesAdapterTest`:
  - profiles, apps, switching, adding and removing, in their shape;
  - bad ids and actions are refused;
  - Profile A's apps can't be changed from the PC;
  - a busy phone says so;
  - a root failure is reported without leaking command text.
- Gateway, `test_profiles_contract.py` (new, 2):
  - every profile result is checked;
  - requests are checked before they reach the phone.
- Glass, `profiles.test.mjs` (new, 3):
  - parsing and colours;
  - the profile strip: switch the phone, remove an app, add an app;
  - Accounts lists the chosen profile's apps.
- CI guard, `test_profile_apps_guard.py` (new, 3):
  - only fixed verbs, and never creating or deleting a profile;
  - only ProfileApps removes an app from a profile;
  - Profile A's apps stay the owner's.
- Full suites:
  - gateway: all pass;
  - MCP: all pass;
  - Glass: 255 of 255 pass, build and guard clean;
  - CI guards: 250 pass.

## Physical acceptance

UNVERIFIED. Not yet run on a rooted phone with profiles. The first real test is to switch to Profile B from Glass,
add an app to it, then switch back.
