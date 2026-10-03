# Cyclone V5 Alpha 73: calm and findable

Developer alpha for owner testing. It builds on Alpha 72 (the app redesign and Drive hearing you) and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.73.dev1` (version code 218).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.73.dev1.exe` (runtime `5.0.0-alpha.68.dev1`, unchanged).
- **Glass:** `1.0.0-alpha.40` (unchanged).

Every page but the AI page now rests on a still, calm blue, like a banking app's home. Home slides between your
profiles, and one search finds anything in Cyclone. The contract is `docs/design/redesign/rounds/R6-calm.md`.

## What changed

**A calm blue behind every page but AI.**
- Home, Profiles, Routines, the Marketplace, Brain and Settings sit on a blue that deepens to night, with a soft light
  from the top.
- It never moves, so it is also the cheapest background the app has.
- The glass is lighter on it (frosted indigo); navigation stays the darker glass.
- The AI page keeps the Cyclone rain and its scene.

**Home, like a banking app's.**
- **At the top:** Settings, a search pill and the Cyclone mark.
- **The profile slider:** swipe between your profiles.
  - Each shows what it is doing ("In use", "Working", "Setting up"), its name in large type, and its apps.
  - Dots show where you are; a tap opens Profiles.
- **Four round actions:** Ask, Routines, Brain, More.
- **Then** the live task, recent activity, your routines and the Ask bar.

**Smart search.** From Home's search pill or the search button in Settings.
- **One field finds:**
  - settings, including by other words: "battery" finds Visual quality and Permissions, "openrouter" finds Model & API;
  - recent runs;
  - routines;
  - skills;
  - the apps on your phone;
  - profiles.
- **Results come by kind,** with chips that count each kind and narrow to one. The words you typed are shown in bold.
- **Before you type:** your last runs and the settings you open most.
- **A tap opens the thing itself:**
  - a setting's section;
  - a run in the AI menu;
  - a routine's detail;
  - Brain, for a skill;
  - Profiles, for a profile;
  - the app itself.
- **Nothing found?** "Ask Cyclone: …" sends it to the AI page.
- **Nothing is stored:** the search reads what is already there, only while it is open.

## Tests

- `CycloneSearchTest` (new, 8): words and synonyms, grouping and order, accents, word starts, newest first, no loose
  matches, four per group with "All", every settings entry complete.
- `HomeProfilesTest` (new): the slider's order, "This phone" when there are no profiles, its two lines.
- `SearchR6ContractTest` (new):
  - every kind has a source, and none writes;
  - each result opens its own place;
  - search opens from Home and Settings;
  - every settings entry is a real section.
- **Updated:**
  - the Home contract (now R6): its order, the calm backdrop never animating, no teal parts;
  - `AppR5ContractTest`, `CycloneTealMatrixTest`, `CycloneAppleUiContractTest`, `CycloneVisual42ContractTest`.

## Physical acceptance

UNVERIFIED. Not yet seen on a phone:
- the calm blue and the glass on it;
- the profile slider's swipe and dots;
- the search sheet with the keyboard up;
- opening each kind of result.
