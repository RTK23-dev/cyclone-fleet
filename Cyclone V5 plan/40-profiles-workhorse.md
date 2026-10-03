# 40 — Profiles as a workhorse: one Cyclone, every profile

**Written:** 2026-09-29, at 5.0.0-alpha.73. **Status:** P1 and P2 built in alpha.75 (see §8); P3–P5 are plans.
**Owner's brief:** Cyclone should be the hub that brings your whole workhorse into every profile: skills, knowledge
and settings. Spinning up, renaming and deleting profiles should feel easy and safe, with backups and undo before
anything is deleted. Then: what would make this a truly great feature.

## 1. How profiles work today (as built)

**A profile is a real Android user**, created with root, never by the model:
- **Created** by Cyclone with root (Magisk 26+) or the Shizuku shell, through fixed commands only
  (`ProfileSetupPlan`):
  - the command is `pm create-user Cyclone_<16 hex>`, as a secondary user or a managed work profile;
  - there is no free shell text anywhere, and every command's shape is checked before it runs.
- **Apps:** `cmd package install-existing --user N` installs apps that are already on the phone. The APK is shared,
  but each profile keeps its own app data, so the same app has its own login in each profile.
- **The label** you see ("Profile B", "Work") lives in Cyclone's profile registry. The Android user keeps its
  `Cyclone_…` name; that is how Cyclone proves a user is one it created (`ProfileRecovery.validOwned`).

**Switching** (`ProfileBootstrapRuntime.prepare`, then `openProfile`) runs these steps:
1. Install Cyclone and the allowlisted support apps into the profile and enable them.
2. Start the profile, and require it to be unlocked.
3. **Root follows you:** Magisk multi-user mode is set, and Cyclone's saved root grant (and that of allowlisted
   support apps that already have one) is copied to the profile's app ids. Nothing new is granted.
4. **Permissions follow you:**
   - notifications, microphone and calendar;
   - overlay, exact alarms and battery exemption;
   - the assistant role, the accessibility service and the notification listener.
5. **A small, encrypted handover:**
   - the profile's Cyclone makes a key pair in its own Android Keystore and publishes the public key;
   - the source writes a file into the profile's private device storage;
   - the file holds four AI preferences (model, reasoning effort, access profile, safe mode), the profile registry,
     and the OpenRouter key, encrypted (RSA-OAEP) for that Keystore key;
   - the profile's Cyclone imports it, checks every grant, and answers with the nonce.
6. **Two proofs, then the switch:** the answer is checked, and a root check is run from inside the profile. Only
   then does `am switch-user` run.

**Lifecycle today:**
- **Create:** a guided flow that can pause and resume.
- **Rename:** the label only (`ProfileRegistryStore.rename`).
- **Delete:** none; there is no `remove-user`.
- **Backups:** none.
- **Repair:** a rescue path exists.

## 2. What does not follow you today

The handover carries settings and a key, but none of Cyclone's learning. A new profile's Cyclone starts nearly
empty:

| What | Where it lives | Crosses today? |
|---|---|---|
| Verified skills, learned apps, paths, notes | `cyclone_adaptive_brain_v27.db`, `cyclone_brain.db` | No |
| App maps and the atlas | `files/atlas`, `files/mapping/…` | No |
| The app manual's dictionaries | `files/manual/dictionaries` | No |
| Playbooks and compiled skill routes | `files/cyclone-playbooks`, `cyclone_stock_skill_checkpoints` | No |
| Routines, and what was taught by doing | `files/cyclone-v31`, `Cyclone Brain/Routine Teachings` | No |
| Marketplace and owner skills | `Cyclone Brain/Marketplace/*.json` | No |
| Mind memory: people, handles, app notes | `Cyclone Brain/Mind memory.json`, sealed with this profile's Keystore key | No, and it can't simply be copied (the key never leaves) |
| Settings: Drive, Visual quality, working indicator, plane compatibility | `cyclone_drive`, `cyclone_ui`, `Cyclone Brain/Planes/*` | No |
| Model, reasoning, access profile, safe mode | `cyclone_ai` (4 keys) | Yes |
| OpenRouter key | the encrypted secret store | Yes, encrypted to the profile's key |
| Chat history, missions, run logs, recent activity | `cyclone_ai_history.db`, `Cyclone Brain/Missions`, Run Logs | No (and by default they shouldn't) |

## 3. The design: Cyclone Carry

**The hub-and-spoke model:**
- The main profile's Cyclone is the **hub**; every other profile's Cyclone is a **spoke**.
- Knowledge flows hub → spoke when a profile is made and each time you switch into it.
- What a spoke learns flows back to the hub when you return. A skill learned in "Work" makes every profile better.

**Three classes of data**, decided per store, not per file:

| Class | What | Rule |
|---|---|---|
| **Shared** | Skills, learned apps and paths, app maps, dictionaries, playbooks, routines, marketplace skills, settings (Drive, visual, planes, model) | Carried both ways and merged |
| **Per profile** | Chat history, missions, run logs, recent activity, the Brain's own chat, and people memory by default | Stays in its profile. People memory can be shared by a switch (§7) |
| **Never** | Gateway pairing and device tokens, task authority, leases, the run queue, app sessions, anything from the vault | Never leaves the profile it belongs to. The vault keeps its own zero-knowledge delivery (plan 33) |

**The bundle:**
- A versioned snapshot per shared store, as rows with a stable id, `updatedAt` and a deleted flag.
- Encrypted with AES-256-GCM. The AES key is wrapped with RSA-OAEP for the destination's Keystore key.
  `ProfileTransferCipher` becomes hybrid, because RSA alone can't carry more than a few hundred bytes.
- It travels by the same path as today's handover: the profile's private device storage, a nonce, and an answer.

**Merging:**
- Last writer wins per row. Deletions travel as tombstones.
- Verified skills keep the higher success count and confidence.
- A routine edited on both sides keeps both, with the newer one on top. Nothing is silently lost.

**Sealed memory:**
- The source opens it, carries it inside the encrypted bundle, and the destination seals it again with its own
  Keystore key.
- It is never written anywhere in plain text.

**The same laws as today:**
- Only the owner's own tap moves profiles or data; no model tool.
- The chrome filter and `looksSecret` run on everything carried: app words only, never a typed value.

## 4. Lifecycle, made friendly

**Create in one sheet:**
- A name, a colour and an emoji.
- Apps: start from "Same apps as Profile A", "Clean", or a template ("Work: Gmail, Slack, Calendar").
- Carry: "Bring my skills, maps and routines" is on by default.
- Plain progress steps ("Making the profile…", "Adding 6 apps…", "Bringing your skills…"). It can pause and resume,
  as today.

**Rename:**
- Straight from the Home slider or the Profiles tab (hold a profile, then Rename), together with its colour and emoji.
- The Android user keeps its `Cyclone_…` name, so ownership checks never depend on a label.

**Remove, with undo (soft delete first):**
1. **Remove:**
   - the profile is stopped (`am stop-user`) and hidden from the slider;
   - it moves to **Recently removed**, where Restore brings it back as it was;
   - no data is touched, so undo is instant and complete.
2. **After 7 days**, or on "Remove now":
   - Cyclone first saves a **backup** of that profile's Cyclone: its carry bundle, its own history and its memory,
     encrypted, kept in the main profile;
   - then it runs `pm remove-user`.
3. **Restoring from the backup** makes a new profile and brings that Cyclone back in.
4. **Optional app-data snapshot** (off by default, with its size shown before it starts):
   - root archives the profile's app data;
   - restoring puts files back with their owner and SELinux labels.
   - **Honest limit:** apps that bind their login to the device or its keystore (banking, WhatsApp) will ask you to
     sign in again.

**Guards:**
- **Who can be removed:**
  - only users Cyclone created (the `Cyclone_[hex]` name, with the parent checked);
  - never the main user or the profile you're in;
  - never while a task runs.
- **Confirmation:** Remove now needs a hold-to-confirm with the profile's name.
- **The new commands** (`STOP_USER`, `REMOVE_USER`) join `ProfileSetupPlan`'s typed commands with the same shape
  checks, and have guard tests like today's.

## 5. What makes it great

**One phone, many lives, one brain.** Work, clients, a test profile, family: each one a clean, separate identity,
with the same Cyclone who already knows your apps.

**Features that compound:**
- **Everything learned anywhere helps everywhere.** A skill learned in "Client A" works in "Client B" the first time.
- **Run everywhere:** "run this routine in every profile" and profile-aware schedules. Profiles are capacity: phones
  × profiles in the Command Center (plan 33).
- **Snapshots:** save a profile's Cyclone before a big change and roll back. Cloning a profile is a snapshot restored
  into a new one.
- **Profile health:** which apps are signed in, which need you, last used, storage. Cross-profile search (R6's search
  gains a Profiles filter per result).
- **Templates:** a share-safe recipe of apps, routines and settings (never data or accounts), for your own profiles or
  the marketplace.

**Trust is the product:**
- A visible audit per profile (created, switched, carried, removed, restored).
- Clear isolation promises: what is shared, what isn't, and why.

**Limits to respect:**
- Only accounts the owner owns or manages. Multi-accounting against a service's terms is not a feature.
- Root features can't ship on the Play Store.
- OEMs cap the number of users (`pm get-max-users`), and every running user costs RAM and battery. Cyclone keeps
  only the active profile and the ones with work running.
- Android 15's Private Space is a possible non-root lane later.

## 6. Build order

| Run | Delivers |
|---|---|
| **P1: lifecycle and safety** | Remove (stop + hide), Recently removed with Restore, Remove now with a Cyclone backup first, rename, colour and emoji in one sheet, typed `STOP_USER`/`REMOVE_USER` with guards and tests |
| **P2: Carry v1 (hub → spoke)** | The hybrid cipher and bundle; shared stores carried on create and on every switch; the create sheet's "Bring my skills…" |
| **P3: Carry v2 (both ways)** | Merge back on return, tombstones, sealed memory carried and sealed again, conflict rules and their tests |
| **P4: snapshots** | Profile snapshots and restore, clone, the optional app-data archive |
| **P5: at scale** | Templates, run-in-every-profile, per-profile schedules, Command Center capacity, profile health |

Each run keeps the laws in `AGENTS.md`, adds guards in `scripts/ci/tests`, and reports physical checks as UNVERIFIED
until the owner tests them on the Pixel.

## 7. Owner decisions (with recommendations)

1. **People memory across profiles:** recommend per profile by default, with a switch to share it. Profiles often
   exist to keep identities apart.
2. **The grace period before a removed profile is deleted:** recommend 7 days.
3. **App-data snapshots:** recommend opt-in per removal, showing the size first.
4. **Order:** recommend P1 first (safety before power), then P2.

**The owner's answers (2026-09-29):** people memory is shared across profiles, clearly labelled by the profile it
came from; removed profiles can be restored for 7 days from a trash bin, like a photos app, and can be deleted for
good from there; backups are automatic; build P1 and P2.

## 8. As built (alpha.75)

**P1: lifecycle and safety.**
- **Remove** (from a profile's detail, on the main profile only): `am stop-user`, then the profile moves to
  **Recently deleted** (the Profiles tab "Deleted"). It is hidden from Home's slider and search, and can't be opened.
  **Restore** brings it back untouched.
- **Delete now / Delete all**, or by itself after 7 days (checked each time Cyclone opens):
  1. an **automatic backup** first: each app's data (credential and device storage) as tar files in the main
     Cyclone's own files, smallest first within 70% of free space, largest left out and listed;
  2. only if the backup was made, `pm remove-user`, then a check that Android removed it.
  Backups are listed under Deleted and cleared after 30 days.
- **Rename** with a colour and an emoji in one sheet.
- **Guards:** typed commands (`STOP_USER`, `REMOVE_USER`, `MEASURE_APP_DATA`, `BACKUP_APP_DATA`, `OWN_BACKUP`,
  `LABEL_BACKUP`) with shape checks; only `Cyclone_` users Cyclone owns; never the main or current profile; never
  while a task runs; only from the main profile's Cyclone. `ProfileLifecycleTest`,
  `test_profile_lifecycle_guard.py`.
- **Not yet:** restoring a backup into a new profile (P4); the backup is kept for that.

**P2: Cyclone Carry, both ways from the start.**
- **When:** every switch through Cyclone, in any direction (the main profile included). The Cyclone you leave packs;
  the Cyclone you open takes it in. It never blocks a switch; Profiles says what came, and when a carry out didn't
  arrive.
- **What:** people memory, the Brain's verified skills and learned paths, notes, how well Cyclone opens each app,
  and the Drive and Visual quality settings.
- **Sealed:** AES-256-GCM, with the key wrapped by RSA-OAEP for the destination's Keystore key; the destination and
  the switch's nonce are bound as associated data. Memory is opened in one profile and sealed again with the other's
  memory key. The answer carries counts only.
- **People memory, labelled by profile:**
  - each memory keeps the profile it came from and its id there; Memory groups them ("This profile", "From Work")
    and the Mind's digest says "(from Work)";
  - what a profile learns goes on its own cards, never on a card from another profile;
  - newer wording wins; forgetting a memory forgets it in every profile (forgotten records are kept 90 days).
- **Skills:** the most recently used row wins; notes are only added; app evidence only grows for apps the profile
  has.
- **Never carried:** keys, tokens, pairing, sessions, the vault, chat history, missions, run logs; any row, memory or
  setting that looks like a secret.
- **Tests:** `MemoryCarryTest`, `CarryRulesTest` (the sealed bundle included), `test_profile_carry_guard.py`.
- **Not yet (P3):** app maps, the atlas, dictionaries, playbooks, routines and marketplace skills (files, not rows);
  deleting a skill in one profile doesn't delete it in the others; switches made outside Cyclone (Android's own user
  switcher) carry nothing.
