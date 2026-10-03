# R6: calm and findable

**Status:** built in 5.0.0-alpha.73.dev1. Physical acceptance UNVERIFIED.
**Owner's brief:** every page that is not the AI page gets a calmer background, like a banking app's home. Home gets a
profile slider, the way a bank's home slides between accounts. A smart search finds anything and shows it by kind,
in a clean sheet like the AI page's model selector.

## 1. The calm blue

`AskCalmField` (ui/v32/ask/AskCalm.kt) is the backdrop of every page but the AI page, inside the glass world:
- Home, Profiles, Routines, the Marketplace, Brain and Settings.
- **The picture:** a vertical gradient from a bright blue at the top to night at the bottom (five stops,
  `AskCalm.STOPS`), with a wide soft light from the top and a faint violet light on the right.
- **It never moves:** drawn once per size, with no frame clock, so the glass above it blurs a still picture. It is the
  cheapest backdrop the app has, in Full and Lite; the rain's cost now lives only on the AI page.
- **The AI page** keeps the rain, the official scene and the owner's own video.
- **Glass on the blue** (`LocalAskCalm`):
  - content glass smokes at 0.40 (0.52 on the rain), so panels read as frosted indigo;
  - navigation stays darker at 0.58, keeping R5's rule that chrome sits above content.
- **Round actions and pills:** a white veil (`AskCalm.Veil`) with a soft rim.

## 2. Home

From top to bottom:
1. **Header:** Settings (the burger), a search pill, and the Cyclone mark (Ask Cyclone).
2. **The profile slider:**
   - one profile per page, centred like an account balance: "Profile · In use", the profile's name large, and its
     apps (up to four icons) with a count;
   - the pages are the Profiles tab's own model (`buildProfileClusters`), read off the main thread: the profile in
     use first, then working ones, then by name;
   - with no profiles, one page: "This phone";
   - page dots in a soft capsule; a tap opens Profiles.
3. The readiness chip, only when phone control needs setup or repair.
4. **Four round actions:** Ask, Routines, Brain, More (More opens Settings).
5. The live task, then Recent activity and Your routines in smoked lists.
6. The Ask bar, pinned above the tab bar.

## 3. The smart search

**Where:** the search pill on Home and a search button in Settings' top bar. It opens one sheet over any page, in
the model selector's style: chrome glass with a darker field.

**Finds, by kind:**

| Kind | Source | A tap opens |
|---|---|---|
| Settings | `SettingsIndex`: every section, with the words people type ("battery" → Visual quality, Permissions) | The section ("Set up Cyclone" opens the setup cards) |
| Recent runs | `MindMissions.history`: the goal, with its summary as extra words | The AI page, with its menu open on that run |
| Routines | `AutomationRuntime.store` | The routine's detail |
| Skills | Brain's verified skills | Brain |
| Apps | The phone's launcher apps, marked when Cyclone knows them | The app itself: the owner's own tap, like a launcher |
| Profiles | The profile registry | Profiles |

**How it ranks** (`CycloneSearch`, pure and tested):
- Case, accents and punctuation are ignored.
- **Scores:**
  - exact title: 100;
  - title starts with it: 90;
  - a word starts with it: 75;
  - every typed word starts a word ("vis qual"): 70;
  - inside the title: 60;
  - a synonym: 55 or 45;
  - the subtitle: 35 or 30;
  - tight skipped letters: 22.
- Below 20 nothing is shown.
- Ties go to the newer item.
- The group with the best match comes first. Each group shows four, with "All n"; a chip shows one kind in full.

**The sheet:**
- **Chips** with counts (All, then each kind found).
- **Rows:** a coloured tile per kind (the app's icon for apps and skills), the matched words in bold, the subtitle,
  and a chevron.
- **Before typing:** the last three runs and four common settings.
- **With no match:** "Ask Cyclone: …" sends the words to the AI page.
- **The search key** opens the top result.

**Privacy:** the sources are read in place, off the main thread, and kept in memory only while the sheet is open.
Nothing is written, logged or sent anywhere.

## Tests

- `CycloneSearchTest`:
  - settings by their words and synonyms;
  - grouping and group order;
  - case and accents;
  - word starts;
  - recency ties;
  - loose letters rejected;
  - four per group, and "All";
  - every settings entry complete.
- `HomeProfilesTest`: the slider's order, "This phone", and its two lines.
- `SearchR6ContractTest`:
  - every kind has a source, and none writes;
  - each result opens its own place;
  - search opens from Home and Settings;
  - every settings entry is a real section.
- `HomeR4ContractTest` (now R6): Home's order, the calm backdrop never animating, no teal parts, the slider's model.
