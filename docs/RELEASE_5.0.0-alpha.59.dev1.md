# Cyclone V5 Alpha 59: the app dictionary

Developer alpha for owner testing. It builds on Alpha 58 dev2 and includes it.
- **Mobile:** `5.0.0-alpha.59.dev1` (version code 204).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.59.dev1.exe` (runtime `5.0.0-alpha.59.dev1`).
- **Glass:** `1.0.0-alpha.35`.

This is the system chosen in plan 36 §7: **a fixed core of kinds, plus a dictionary per app, behind a gatekeeper.** It
lets Cyclone keep one consistent structure for each app. It can tell Followers, Following, Close friends and Message
requests apart by where the app shows them, never by who is in them.

## What changed

**1. The fixed core.** 24 kinds every app shares, plus Other. For example: Person, Conversation, Message, Post, Media,
File, Setting, Notification, Group, Tool or connector, and Assistant or model. Every group in every app sits under
exactly one of them.

**2. The app's own words.** During a mapping pass the phone reads the app's own dictionary of words, meaning the
strings the app ships with.
- A label on screen is kept only if it is one of those words, like "Primary", "Followers" or "Search chats".
- "1,234 followers" keeps only "Followers".
- A person's name, a chat title or a message never matches, so it is never kept. A name slot in a template ("Sam's
  story") can't make a name match either.
- **Names made of the app's words but not one of its strings are not proposed at all.** "Family" or "Work" could be a
  folder or group chat you named yourself, so they are dropped, not even kept as waiting. A screen title is kept only
  when it is exactly one of the app's strings. Downloaded menu names come back when the mapper can open and test them
  (plan 36 §5). The organizer already knows how to treat them (two days plus the question); CI guards that production
  never proposes them.

**3. Reading a screen's structure.** On each screen of a pass the phone finds:
- **the title**, if it is the app's own words;
- **category strips** (tabs, segments, chips with a selection), with each category's position and its neighbours;
- **lists**: row shape ("2 texts · image · button"), section headers ("Today"), whether a search is there and its
  hint, and **markers**, meaning a button on most rows such as "Remove" on Followers;
- **the kind**, guessed from generic words and the developer's ids (`row_thread`, `follow_list_user`). When the words
  don't settle it, the kind is left open for the question.

None of this is written for one app. CI fails if this code names another app's package.

**4. The organizer: the gatekeeper.** The reader only proposes. At the end of each pass the organizer decides.
- **The gates:**
  - named by the app;
  - has its own place on screen;
  - not a duplicate: the same string, place or name folds into the existing group and never creates a second one;
  - seen twice;
  - at most 3 levels deep;
  - room under its parent (up to 12).
- **Parents first:** Messages is confirmed before Primary, General and Requests take their places under it.
- **One narrow question per pass**, only for what the gates can't settle. It goes to the model chosen for the pass, with
  fixed answers: new, same as, part of, keep, reject. Only ids it was offered count; anything else keeps the candidate
  waiting. The question contains only the app's own words, kinds and set ids.
- **JEV watches that question** and never decides. Its agreement and speed show in the Dictionary tab. The Drive
  setting "JEV watching" switches it off.
- **Everything is written to an audit**, and every change can be undone.
- **A group missing from its own screen on two passes** is marked "Gone from the app"; its id still resolves.

**5. The Dictionary tab (Glass → Apps → an app → Dictionary).**
- The app's groups as a tree under their kinds. Each shows where it lives ("a category on “Messages” next to
  “General”, “Requests” · find one: “Search”"), why it waits ("seen once so far"), and how often it was seen.
- **Your controls:** Confirm, Lock (the organizer never changes a locked group), Rename (kept as another name), Same
  as… (merge), Undo merge, Under… (move) and Reject.
- **Needs a look:** near-duplicates, orphans, groups waiting over two weeks, and groups not seen in this app version.
- **What an agent reads:** the glossary block.

**6. Agents use it.** The first time a phone task is in an app, the Mind gets the app's glossary next to the map. For
example: `set:primary = Conversation › Messages › Primary (category on “Messages”; find one: “Search”)`.

**7. Model picker on the PC.** The mapping start sheet has **Model**: *Phone's model · (its current name)* first, then
the models chosen on the phone. It decides what new groups are, and your key stays on the phone. The phone's model is
the default and is not even sent, so an older phone never sees a field it doesn't know.

## Safety

- **Structure only:**
  - the dictionary holds group names in the app's own words, where they live, ids and counts of sightings;
  - it never holds who is in a group, how many there are, or any name, message or typed text;
  - every stored and served field is checked on the way in and out.
  - CI guards the fields, and a planted canary chat and person never appear in the proposals or the stored JSON
    (tested).
- **Only the organizer and you admit groups.** CI guards that nothing else can set a group to confirmed or locked.
- **JEV only watches.** CI guards that its pick is never applied.
- **Agents can't read or edit the dictionary or pick models.** The agent MCP servers have no route to it (CI-guarded).
  Edits come from Glass only.
- **The PC sends a model name, never a key.** The gateway accepts `describer` as `{"model": …}` only (tested), and the
  models list carries ids, labels and a picture flag only.
- **Mapping is unchanged:** it still never pays, sends, deletes, changes settings or signs in.

## Validation and limits

Tests that pass:
- **Phone:**
  - `AppDictionaryTest`, 11 tests:
    - the lexicon keeps only the app's words (numbers dropped, names never matched);
    - Instagram-shaped screens: Messages › Primary / General / Requests, Followers with its "Remove" marker, and
      Following. The canary chat, names and previews never appear;
    - the gates, parents first and no duplicates;
    - a label made of the app's words but not its string (a folder you named) is never proposed;
    - the organizer's rule for downloaded names: two days and the question;
    - answers outside the allowed choices are ignored;
    - owner merge, undo, lock, rename and refused moves;
    - the stored JSON has only allowed fields and round-trips;
    - health and retiring.
  - `GatewayV5ManualAdapterTest`, 3 tests.
  - The full phone suite: 2022 tests.
- **Gateway:**
  - `test_dictionary.py`, 15 tests: ops registered, edits and replies validated, `describer` and models carry no key,
    routes.
  - The full gateway suite: 607 tests.
- **Glass:**
  - `dictionary.test.mjs`, 4 tests: strict reading, the tree and its controls, the empty state, and the picker.
  - An atlas client test: `describer` is sent only for a picked model.
  - All 201 Glass tests pass.
- **CI guards:** `test_app_dictionary_guard.py`, 8 guards. All 181 guards pass.

Limits:
- **Physical: UNVERIFIED.** It has not run on the Pixel yet. Map Instagram or ChatGPT twice, then open the Dictionary
  tab.
- **Groups need two passes.** One pass proposes; the second confirms.
- **Downloaded menu names are left out for now** (see above), so an app whose tabs come from its server shows fewer
  groups until probes land.
- **Apps that hide their strings.** Apps that build their words at run time, or obfuscate their string resources,
  give fewer matches. Groups then wait instead of being guessed.
- **This release reads categories, lists and markers only.** It does not open "+" menus, sheets or long-press menus.
  Opening those safely is the next App Manual step (plan 36 §5), together with the describer, abilities and the
  screenshots switch. The screenshots switch is not in this release.
- **JEV ability picks and the Lab dictionary-stability suite** (same ids across two models) are not in this release.
