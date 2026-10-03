# Cyclone V5 Alpha 75: one Cyclone, every profile

Developer alpha for owner testing. It builds on Alpha 74 (the Home fixes) and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.75.dev1` (version code 220).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.75.dev1.exe` (runtime `5.0.0-alpha.68.dev1`, unchanged).
- **Glass:** `1.0.0-alpha.40` (unchanged).

The first two runs of plan 40 (profiles as a workhorse), with the owner's answers: people memory is shared across
profiles and labelled by the profile it came from, removed profiles wait 7 days in a trash bin, and backups are
automatic. The as-built notes are in `Cyclone V5 plan/40-profiles-workhorse.md` §8.

## What changed

**Remove, restore, delete (P1).**
- **Remove** a profile from its detail page (on the main profile). It stops and moves to **Deleted**, where it waits
  7 days, untouched. **Restore** brings it back exactly as it was.
- **Delete now**, **Delete all**, or after 7 days by itself:
  - Cyclone first **backs the profile up** automatically: each app's data, as much as fits;
  - only then does Android delete the profile. If the backup can't be made, nothing is deleted.
- **Backups** are listed under Deleted and kept 30 days.
- **Rename** a profile with a colour and an emoji in one sheet.
- **Never:** the main profile, the profile you're in, a profile Cyclone didn't make, or while a task runs.

**Cyclone Carry (P2).** Every time you switch profiles in Cyclone, what it knows goes with you, and what it
learned there comes back when you return.
- **People memory, shared and labelled.** Settings → Memory groups memories by profile ("This profile",
  "From Work"), and the Mind knows where each came from. What a profile learns stays on its own cards. Forget a
  memory once and it's forgotten in every profile.
- **Skills:** verified skills, learned paths, notes, and how well Cyclone opens each app.
- **Settings:** Drive and Visual quality.
- **Private by design:**
  - the bundle is encrypted for the other profile's own Keystore key, and only opens for that switch;
  - keys, tokens, pairing, the vault, chat history and runs never travel;
  - anything that looks like a secret stays behind.
- **Never blocks a switch.** Profiles shows what the last switch brought.

## Not yet

- Restoring a backup into a new profile (P4).
- Carrying app maps, dictionaries, playbooks, routines and marketplace skills (P3).
- Switches made outside Cyclone (Android's own user switcher) carry nothing.

## Tests

- `ProfileLifecycleTest` (new): trash days and lines, order, every refusal, the backup plan, command shapes.
- `MemoryCarryTest` (new, 8): labelled by profile, own cards stay own, round trips without duplicates, newer words
  win, forgetting travels, secrets never travel, older memory files still read.
- `CarryRulesTest` (new, 5): row merging, secret rows refused, only portable settings, the Profiles line, and the
  sealed bundle opening only for its profile and switch.
- Guards: `test_profile_lifecycle_guard.py`, `test_profile_carry_guard.py` (new); `test_memory_guard.py` updated.

## Physical acceptance

UNVERIFIED. Not yet seen on a phone:
- Remove, Restore, Delete now with its backup, and the 7-day deletion;
- a carry in each direction (main → profile, profile → main), and Memory's "From …" groups;
- switch time with a carry.
