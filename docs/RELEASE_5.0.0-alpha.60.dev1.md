# Cyclone V5 Alpha 60: the App Manual foundation

Developer alpha for owner testing. It builds on Alpha 59 and includes it.
- **Mobile:** `5.0.0-alpha.60.dev1` (version code 205).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.60.dev1.exe` (runtime `5.0.0-alpha.60.dev1`).
- **Glass:** `1.0.0-alpha.36`.

This is the base the rest of plan 36 builds on: places named in the app's own words, menus and sheets that the mapper
opens and reads, categories proven in one pass, and a safe way to bring back names the app downloads.

## What changed

**1. The map uses the app's words.** Glass → Apps → an app → Map now names places with the app's own words:
- **the screen's title** ("Messages", "Settings");
- **or the words on the door that led there** ("Add photos and files");
- **the selected category** shows under the name ("Primary selected");
- **panels** say what they open over ("Panel over Messages");
- **doors** show the words on them instead of "Open tab".

A place with no app words keeps its old label. Nothing that was on your screen is used: only exact app strings.

**2. The mapper opens menus and sheets.**
- **Which buttons count:** icon buttons whose description says add, more, attach, create, new or options (such as "+"
  and ⋯), and floating buttons, are now doors that open a panel.
- **What the pass does:** it opens the panel, reads what it offers in the app's words ("Camera · Files · Connectors"),
  and goes back.
- **What it taps inside:** only doors of their own (a tab, a menu, settings), never a choice.
- **Never a revealer:** a text row ("Add to cart", a person's name).
- **Still judged first:** every door, these included, goes through the same safety check as before. That check now also
  refuses "add to", "buy", "purchase", "checkout", "donate", "tip", "send gift", "go live" and "call".

**3. Categories are proven in one pass.** When the mapper sees the same row of tabs with a different tab selected, the
tabs it saw selected are proven, and they join the dictionary in that same pass. Tabs it never saw selected still need
to be seen twice.

**4. "App word or yours?"**
- **When it asks:** a tab name made of the app's words but not one of its exact strings, such as a menu the app
  downloads, is still never stored. When a probe proves it is a real tab, the Dictionary tab asks: *"“Close friends” is
  a tab on “Messages” next to “Primary”. Is it a word from Instagram, or a name you made?"*
- **App word:** it joins as a confirmed group.
- **Mine:** it is dropped, and only a code is kept so Cyclone never asks again.
- **Until you answer**, the question lives only in the phone's memory. It is not written anywhere, and it is gone if
  the phone restarts.

**5. The Dictionary tab gains "Places":** each named place, panels with what they offer, and the groups that live
there.

**6. The phone's AI sees places too.** Next to the groups, the glossary it gets in an app now lists panels and screens,
for example: *“Add photos and files” opens a panel over “Messages” offering “Camera”, “Files”, “Connectors”*.

## Safety

- **Only the app's exact strings are stored:** names, door words, panel offers and "next to" lists.
  - A neighbour tab's downloaded name no longer rides along in "next to". This was found and fixed during this build.
  - The planted canary chat and names never appear in the store or the replies (tested).
- **Downloaded names are never recorded** until you say "App word". "Mine" keeps a code only. (CI-guarded)
- **Panels, like every door, pass the safety check before a tap.** Text rows are never treated as menu buttons.
  (CI-guarded)
- **Mapping is unchanged:** it still never pays, sends, deletes, changes settings or signs in.
- **JEV stays parked.** Nothing new uses it.

## Validation and limits

Tests that pass:
- **Phone:**
  - `AppManualAlpha60Test`, 5 tests:
    - "+", ⋯ and floating buttons are panel doors; "Add to cart" and rows are not;
    - panel offers are still judged before any tap;
    - two selections prove categories in one pass;
    - a downloaded name waits in memory, and "App word" admits it while "Mine" keeps only a code;
    - places, panels and door words are named with app strings only and round-trip in storage.
  - `GatewayV5ManualAdapterTest` adds screens, doors, the review list and the answers.
  - The full phone suite: 2028 tests.
- **Gateway:** `test_dictionary.py` has 16 tests, including screens, doors, review validation and answers. The full
  gateway suite: 608 tests.
- **Glass:** 2 new tests (names on the map, the review card and Places). All 203 Glass tests pass.
- **CI guards:** 182 pass, including new guards for downloaded names, the in-memory review and the panel-door safety
  check.

Limits:
- **Physical: UNVERIFIED.** Map Instagram or ChatGPT once on the Pixel. Then check that the Map tab shows real names,
  that the "+" panel appears as a panel with what it offers, and that the Dictionary tab confirms tabs you saw switch.
- **Names need the app's strings.** Apps that build their words at run time, or hide their strings, get fewer names.
  Those places keep "Screen".
- **Opening a panel can show a system prompt.** A "create" or camera button may ask for a permission. The mapper never
  taps Allow and goes back.
- **Not yet built:** describing what each screen is for, the "things you can do" index, the self-quiz and the Lab
  scores. These come next, tuned against your first real passes.
