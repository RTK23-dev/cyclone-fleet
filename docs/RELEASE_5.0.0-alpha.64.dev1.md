# Cyclone V5 Alpha 64: what you can do in an app, and the way there

Developer alpha for owner testing. It builds on Alpha 63 (the Driver mode fix and the working-app logo) and includes it.
- **Mobile:** `5.0.0-alpha.64.dev1` (version code 209).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.64.dev1.exe` (runtime `5.0.0-alpha.64.dev1`).
- **Glass:** `1.0.0-alpha.39`.

Alpha 60 named the places of a mapped app in the app's own words. This release turns that map into a manual: the
things you can do in the app, the way to each, and a phone AI that walks the known part itself and stops before any
choice.

## What changed

**1. "Things you can do", for every mapped app.** Glass → Apps → an app → **Abilities**:
- **What is listed:** open a screen, open a "+" or ⋯ panel, switch a category ("Requests on Messages"), something a
  panel offers ("Camera (in Add photos and files)"), a button on a screen, and finding one item in a list.
- **What each shows:** its path in the app's words (Chat › Add photos and files › Camera), whether a walk has done it
  yet (Walked) or it is only mapped, and how sure Cyclone is.
- **Buttons that would ask you first** (delete, buy, call and the like) are listed so a goal can find them, and marked
  "Asks you first". Nothing about that changes: the phone still asks.
- **Search it in plain words:** "see message requests" finds "Requests on Messages". The search runs on the phone, with
  no network call.

**2. The phone's AI uses it.**
- **In an app with a manual,** the AI sees a few lines that fit its goal, each with a handle (a1, a2…).
- **`go_to` with an ability** walks the known part itself: the doors the mapper walked, a menu, a tab. It checks the
  screen after every press and hands back at the first surprise.
- **It never chooses for you.** For "Camera (in Add photos and files)" the walk opens the panel and stops; picking
  Camera is the AI's next step, with the usual rules. Nothing is typed, sent or confirmed by a walk.
- **New tools:** `abilities_find` (search the manual for a goal) and `how_to_find` (how to find one chat, person or file
  in a list: its search, its order, its groups).
- **Runs teach the manual:** a walk that arrives marks the ability Walked and raises its confidence; a walk that stops
  lowers it.

**3. Lists know their order.** When a list's rows show ages ("2m", "Yesterday", "Mon", "12 Aug") or names in A–Z order,
the manual keeps "newest first" or "A–Z". The ages and names are read and dropped on the phone; only the conclusion is
kept. "Open my newest chat" then means item 1.

**4. A one-line purpose per screen, and the self-quiz.** After a pass, the model you picked for it writes:
- **what each screen is for** ("Your direct messages");
- **other ways to say each ability;**
- **about 20 goals a person might have in the app.**

The manual then tries to answer each goal on its own. The Abilities tab shows **"Answers 17 of 20 goals · 3 to
explore"** and lists what is still missing.

**5. Map deeper goes where the gaps are.** **Map deeper** (on the Abilities tab and in the pass report) tries first the
menus and tabs whose words fit the unanswered goals. It is still look only, and every door still passes the safety
check. Only a yes/no flag goes from the PC to the phone: the phone reads its own quiz.

**6. Export manual.** The Abilities tab downloads the manual as Markdown: screens with their purpose, panels,
categories, lists and how to find one item, abilities with their paths, and the quiz.

**7. Other AIs on your PC can read it.** The agent MCP has a read-only `phone_app_manual(device_id, app, query?)` tool.
It returns the manual, never taps anything, and holds no content.

## Safety

- **The app's words only.** Abilities, paths, purposes and phrasings come from the app's own strings and the
  describer. Model text is screened on the phone: one short line, no long numbers, emails or links. (tested)
- **List rows are never kept.** Only "newest first", "oldest first" or "A–Z" is stored. (CI-guarded)
- **A walk never chooses.** It presses only doors the mapper walked, and a switch's category. Offers and buttons are
  left to the AI, with every approval unchanged. (CI-guarded)
- **Read only everywhere.** `manual.get`, the gateway route and the agent tool only read. (CI-guarded)
- **Map deeper sends a flag, not words.** (CI-guarded)
- **JEV stays parked.** Nothing new uses it. (CI-guarded)

## Validation and limits

Tests that pass:
- **Phone:**
  - `AppManualAlpha64Test`, 7 tests: list order from row shapes only; the reader keeps the order and never the rows;
    abilities with stable ids and walk records; the index finds "see message requests" and "add a connector to this
    chat"; a walk that checks every step, leaves the pick and stops on a surprise; the describer's purposes, phrasings
    and quiz, with unsafe text dropped; the manual as Markdown and as an excerpt.
  - `PhoneMindToolboxTest`: `abilities_find` and `go_to(ability)` walk through the normal act path; a surprise stops the
    walk and counts against the ability.
  - `GatewayV5ManualAdapterTest`: `manual.get` with hits, quiz, scores and Markdown.
  - The full phone suite: 2041 tests.
- **Gateway:** `test_dictionary.py` adds the manual's validation and route and the Map deeper flag. The full gateway
  suite: 625 tests.
- **Agent MCP:** `phone_app_manual` is read only and validated (92 tests pass).
- **Glass:** 2 new tests (the parser, the Abilities tab); all 225 Glass tests pass.
- **CI guards:** a new `test_app_manual_guard.py` (7 guards); all 195 pass.

Limits:
- **Physical: UNVERIFIED.** Map an app on the Pixel with a model picked. Then open **Abilities**: check the list, search
  "see message requests" (or a goal in that app), press **Try it**, and look at the quiz line. Ask the phone for
  something in that app and check it uses a1/a2 and stops before choosing.
- **The describer needs a model and a key on the phone.** Without them the abilities are still listed, without
  purposes, extra phrasings or a quiz.
- **Walks need walked doors with words.** A door whose words the app builds at run time cannot be walked by the manual;
  the learned map (`go_to` a screen) still can.
- **Not yet:** masked screenshots for the describer, sharing manuals between phones, and a Lab find-the-feature suite
  with hand-written goals (the self-quiz stands in until your first real passes).
