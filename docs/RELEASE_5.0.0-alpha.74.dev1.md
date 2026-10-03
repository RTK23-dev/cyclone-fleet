# Cyclone V5 Alpha 74: Home, fixed

Developer alpha for owner testing. It builds on Alpha 73 (calm and findable) and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.74.dev1` (version code 219).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.74.dev1.exe` (runtime `5.0.0-alpha.68.dev1`, unchanged).
- **Glass:** `1.0.0-alpha.40` (unchanged).

Two fixes from the owner's first look at R6 on the phone.

## What changed

**The + drawer opens above the Ask bar.**
- Before, Photos, Camera, Files and Share screen opened under the bar and pushed it up the screen.
- Now they rise from the bar as a glass drawer on top of it. Back or + closes it.

**"Routines" is no longer cut off.**
- The round actions under the profile slider were clipped to a capsule, which sliced the corners of the wider labels.
- The press area is now a soft rounded square, and labels stay on one line.

**Plan 40: profiles as a workhorse** (`Cyclone V5 plan/40-profiles-workhorse.md`). A deep dive into how root profiles
work today and what doesn't follow you into a new profile. It sets out:
- **Cyclone Carry:** your skills, maps, routines and settings in every profile;
- **Removing with undo:** Recently removed, and a backup before anything is deleted;
- **The build order.**

Nothing of plan 40 is built in this release.

## Tests

`HomeR4ContractTest`:
- the + drawer sits above the bar and grows upward, and Back closes it;
- the round actions aren't clipped by a capsule, and their labels stay on one line.

## Physical acceptance

UNVERIFIED on the phone until the owner looks: the drawer rising above the bar, and the four labels whole.
