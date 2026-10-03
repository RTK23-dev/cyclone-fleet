# 36 — The App Manual: a map builder that understands apps by itself

**Status:** plan, 2026-09-28, with the owner's decisions (§0). **The app dictionary (§7) is built in alpha.59 (§7.8), the App Manual foundation in alpha.60 (§7.9).** Three build runs (M1–M3, §14). Builds on the mapper
(plans 06, 20, 22), one map and grounded skills (23), the Mind's `go_to` (A37) and JEV watching (32).

**The owner's ask:**
> "Rethink how we can optimally write down each screen and function in it so that any AI can easily pick up on what
> it needs from human language. … that plus button in ChatGPT lets me also add a plugin to a chat. … For chats it
> should only think of how these chats are placed from top to bottom … if I want to find a specific chat I can search
> for the keywords. Keep it factual, not mapping every contact name, just how they are listed and how to find them.
> Also categories, like in Instagram people with subcategories: followers, following, DM general, DM primary, close
> friends. The system should just get these. These are only examples. Map out how to build the system that is
> intelligent enough to do this smart map building by itself."

**In one sentence:** a mapping pass stops writing "Screen → Menu → Screen" and writes an **App Manual**. It covers
every screen, panel, list and category in the app's own words; how each list is ordered and how to find one thing in
it; and an index of **things you can do** with the verified taps that do them. A **smart mapper** builds it without
app-specific rules. It recognises common UI patterns, asks itself what it doesn't know yet, runs small safe
experiments to find out, and quizzes itself at the end to find the gaps.

---

## 0. Decisions (owner, 2026-09-28)

- **Privacy: yes.** The promise becomes "Cyclone keeps the app's own words, never your content" (§4).
- **Model selector on the PC.** The mapping start sheet in Glass has a model picker.
  - **Default: the phone's current model** (the model selected in the phone's settings, `openrouter_model`), resolved
    when the pass starts.
  - The model's key stays on the phone; the PC only chooses which model.
- **Screenshots: an on/off switch** with one short, human note (§9). Off by default.
- **Lists are described by how they work, not by what's in them.** The manual records the list's order, its groups,
  how to find one item and what opening one shows. It never records the entries themselves.
- **Categories are found automatically:** tabs, segments, filters and sub-categories (Instagram's Followers /
  Following, DMs Primary / General / Requests, Close friends), with no app-specific code.

## 1. Why the map is vague today (found in the code)

In the ChatGPT pass in the screenshots, 12 places are all called "Screen · Learned app screen", and the doors are
"Menu", "Search", "Back". Five things cause this:

1. **Every name is squeezed into 33 fixed words.** `AtlasPrivacy.coarseStructure` (`brain/graphv2/AtlasContracts.kt`)
   maps any screen or control name onto a frozen list (Home, Search, Menu, Settings, …). Anything else becomes
   "Screen", "Learned app screen" or "Control". The goal was never to keep a chat title or a name, but the app's own
   words are thrown away too.
2. **The mapper drops labels at the door.** `MappingStructuralProjection` (`mapping/crawl/AndroidMapperPorts.kt`)
   reads each control's label, description, resource id and role. It keeps only a hashed key and one of 11 door
   kinds. A "+" described as, say, "Add photos and files" matches no kind and becomes `CONTENT_ROW`.
3. **Screen purpose is almost always UNKNOWN.** `inferPurpose` knows only LOGIN, MENU and LIST.
4. **Panels, lists and categories don't exist as ideas.**
   - The "+" sheet, ⋯ menus and dialogs are separate "places" or never opened.
   - A list is only "some content rows". Tabs are only "TAB doors".
5. **Nothing says what a thing is for.** The Mind's map card (`mind/map/MindMap.kt`) is `s3 Screen: "Menu" → s4`,
   capped at 40 lines. It can route to a screen it can name, but it cannot answer "where do I add a plugin?".

## 2. What we want, in two examples

**ChatGPT: "Add the GitHub connector to this chat."**
1. The goal is looked up in ChatGPT's **ability index**. The top hit is *"Add a connector (app) to a chat"* (score
   0.91; the next is *"Attach a file"*, 0.44).
2. Its path is `Chat › + (Add) › Connectors › pick one`.
3. The walker opens a chat, taps "+", checks that the **Add** panel appeared, then taps **Connectors**. There is no
   model call so far, and every step is checked against the screen.
4. Only the final pick goes to the Mind, with two lines from the manual.

That is about 1 model call instead of 6–10.

**Instagram: "Open my chat with Sam."**
1. The manual says:
   - *Messages* is a list with the categories **Primary · General · Requests**, newest first;
   - a **Search** field at the top finds a chat by a person's name.
2. The walker opens Messages and taps Search. The Mind types "Sam" (a normal task, with its usual rules) and picks
   the result.
3. The manual never knew Sam existed; it only knew how to find anyone.

## 3. What the manual contains

One manual per **app × version** (the Versions tab already tracks versions). Every entry carries **provenance** (seen,
probed, walked, used in a run, taught by the owner, inferred) and a **confidence**.

| Entry | What it holds | Example |
|---|---|---|
| **Screen** | The name in the app's words, one line on what it's for, how you get there, its regions | **Chat** (ChatGPT): "Talk to ChatGPT. Composer at the bottom, replies above." |
| **Panel** | A sheet, menu or dialog over a screen: what opens it, what it offers, how to close it | **Add** (from "+" on Chat): Camera · Photos · Files · Connectors · … |
| **Control** | Label (app's words + string resource id), role, region, **effect**, **risk**, selector bundle | "+" · opens panel Add · safe |
| **List** | What one item is (a typed slot), layout, **order**, **groups**, rough size, **how to find one**, what tapping or long-pressing one does | Chat list: ‹a chat›, newest first, grouped *Today / Yesterday / Previous 7 days*; find one with *Search chats* |
| **Categories** | A set of views of the same place (tabs, segments, chips, filters), their sub-categories, and which list each one shows | Messages › **Primary · General · Requests**; Profile › **Followers · Following** |
| **Ability** | A thing a person can do, as a verb phrase, with the path that does it and 8–15 other phrasings | "Add a connector to a chat" → `Chat › + › Connectors › ‹pick›` |
| **Fact** | A small learned truth for planning | "Temporary chats are not saved to history" |
| **Dictionary** (§7) | The app's sets of things (under a fixed core kind), their markers and views, with stable ids. Every other entry tags these ids | `set:ig.close_friends` ⊂ Person, listed at Settings › Close friends |

- **Effects** are a fixed set: `navigate`, `reveal` (opens a panel), `switch` (changes category), `scroll`, `edit`,
  `toggle`, `choose`, `send`, `pay`, `delete`, `grant`, `sign-in`, `external`.
  - The last six keep today's approval boundaries (GATE and `MapperDoorRisk`).
  - The manual may *describe* "Delete chat" so that a goal can find it, but a pass never walks it and a task still
    asks.
- **Selector bundle:** resource id, string resource id, content description, role, class, region and relative
  position. The walker needs two of them to agree. Because the string id is in the bundle, a path still works after
  the phone's language changes.
- **Lists give positional abilities for free.** "Open the newest chat" means the first item, and "the third chat"
  means position 3. That works because the order is known, not the entries.

### 3.1 The manual as text (what any AI reads)

The same data renders as compact Markdown for the Mind, Claude, Codex or any AI. The excerpt below is an
**illustration of the format**; a real one comes from a pass.

```
# Instagram (com.instagram.android) · 402.0 · mapped 2026-09-28 · look only
## Screens
- **Home feed** — posts from people you follow. Top: Instagram logo, ♡ Notifications, ✉ Messages.
  Bottom bar: Home · Search · + Create · Reels · Profile.
- **Messages** — your direct messages.
  - Categories: **Primary · General · Requests** (switch at the top).
  - List: ‹a chat› rows, newest first; unread shows a dot. Long list, loads more as you scroll.
  - Find one: **Search** at the top matches a person's name or username.
  - Tap a row → **Chat** (‹a person›). Long-press → menu: Mute · Delete (never walked) · …
- **Profile** — your posts and account.
  - Categories: **Posts · Reels · Tagged**. Header links: **Followers**, **Following** (numbers are never kept).
- **Followers / Following** (one screen, categories **Followers · Following**) — ‹a person› rows.
  Find one: **Search** at the top. Row button: Remove / Following (never walked).
- **Close friends** (Settings › Close friends) — ‹a person› rows with a tick; Search at the top.
## Abilities
- Open a chat with someone → Messages › Search › ‹their name› › pick           [walked · 0.9]
- See message requests → Messages › Requests                                  [walked · 0.95]
- Open my newest chat → Messages › Primary › item 1                           [walked · 0.9]
- See who follows me → Profile › Followers                                    [walked · 0.95]
- Edit close friends → Profile › ☰ › Close friends                            [seen · 0.7 · changes ask you]
```

## 4. Privacy: the app's own words, never your content

A text is kept verbatim only if it is **the app's own words (chrome)**. Everything else is **content**. Content
becomes a typed slot (‹a chat›, ‹a person›, ‹a post›) or a *shape* ("a relative time", "a number"). The value itself
is never kept.

**What counts as chrome** (one test must pass):
- **In the app's own dictionary.** Cyclone reads the installed app's string resources
  (`PackageManager.getResourcesForApplication`) and builds a per-version **lexicon** of every string shipped in the
  APK, in the phone's language, stored as normalised hashes. "Primary", "Followers", "Search chats" and "Previous 7
  days" are in the APK. "Sam" and "Dinner plans" never are.
- **Stable in a chrome slot**, for words an app downloads instead of shipping. All of these must hold:
  - it sits in a chrome region: an app bar, tab bar, category strip, sheet or dialog title, section header or menu
    item;
  - it is outside list rows;
  - it had the same text in the same slot on two passes on different days.

**Never chrome:**
- list rows;
- editable fields and their values;
- numbers next to labels ("1,234 followers" keeps only "Followers");
- anything `AtlasPrivacy` flags (emails, tokens, codes);
- anything in a secure window or password field.

**What content may leave behind: shapes, never values.** Examples: "each row has a name, a line of preview text and a
relative time"; "sorted newest first" (§6.3); "unread rows show a dot".

**Guarded, not promised:**
- **Canary.** Seeded canary content (a chat titled `CANARY-7781 lighthouse`, a contact named `Canary Qx`) must never
  appear in the manual, the database, Glass, diagnostics, or anything sent to the model. CI and every Lab suite check
  this.
- **Echo check.** Each model-written sentence is compared with the observation's content text. Any run of 3 or more
  content words, or any content token of 5 or more characters, rejects the sentence.

## 5. The smart mapper: how it builds the map by itself

The mapper works like a careful person learning a new app. It looks, recognises what kind of thing each part is,
wonders what it doesn't know, tries something harmless to find out, writes it down, and moves on to the most useful
unexplored part. None of it is written for one app. It generalises because it reasons about **UI patterns**, which
every app shares, rather than about apps.

### 5.1 The loop (one screen)

```
 perceive ─► split chrome/content ─► recognise patterns ─► hypothesise ─► probe ─► record ─► choose next
    ▲                                                        (model)       (safe)                  │
    └──────────────────────────────────────────────────────────────────────────────────────────────┘
```

1. **Perceive.** The accessibility tree, grouped into regions (top bar, category strip, body, bottom bar, floating
   button, sheet). If screenshots are on (§9), a **masked** screenshot is taken: every content region is painted solid
   before the picture leaves the phone.
2. **Split** chrome from content (§4).
3. **Recognise patterns** (§5.2): cheap, deterministic detectors label the regions with evidence and a confidence.
   For example: "bottom navigation, 5 items", "category strip, 3 segments, Primary selected", "vertical list,
   ‹row›×14", "search field (hint: Search)".
4. **Hypothesise (the model).** One call per **new screen template** receives:
   - the pattern-annotated skeleton, which is chrome words, slots, shapes and pattern labels, never raw content;
   - the parent screens, and the masked screenshot if on.

   It returns strict JSON with the screen's name and purpose, each region's role, draft abilities, and **questions
   it can't answer yet**, each paired with a probe that would answer it:
   - "Does 'General' show a different list?" → switch category;
   - "What does the ✦ icon open?" → reveal;
   - "Is this list newest first?" → read row shapes, scroll one page;
   - "What does a row open?" → open one sample.
5. **Probe (the experiments).** Each probe is a safe action with a **predicted outcome**. The result confirms or
   refutes the prediction. The allowed probes:
   - **reveal**: open a panel, check that the screen under it is the same, then press Back;
   - **switch**: pick another category and check that the frame stayed and the list changed;
   - **scroll**: scroll the list one or two pages, reading row shapes and group headers;
   - **sample**: open the first item, learn the detail template, press Back;
   - **long-press reveal**: open a row's menu, record its items, press Back (items are described, never walked).

   Probes on the owner's own account never type, choose, toggle, send, follow or sign in. They never type in search
   either, because apps save search history. Search is described from its hint and position. On a **test account**
   (plan 22), a search probe with a nonsense word is allowed, to learn the results template.
6. **Record.** Findings go into the manual with provenance. A refuted hypothesis corrects the draft, and a second
   model call per screen (batched) revises it after the probes.
7. **Choose next** (§5.4).

### 5.2 The pattern library (generic, tested on many apps)

About 20 detectors, each pure and fixture-tested on recorded skeletons from at least 10 different apps:

| Pattern | Evidence it looks for | What it adds to the manual |
|---|---|---|
| App bar | Top region, title, icon buttons | Screen name, top actions |
| Bottom navigation / tab bar | 3–5 same-role selectables at the bottom, one selected | The app's main sections |
| **Category strip** (tabs, segments, chips, filters) | ≥ 2 sibling selectables in one container, one selected, above a body region | A category set, and each category's list (§6.2) |
| Navigation drawer / sidebar | A panel from the edge, with a list of destinations | A panel with sections |
| Overflow / "+" / FAB | An icon button with a reveal-like description, or a floating button | A revealer (probe it) |
| Sheet, menu, dialog | A new window over the same frame | A panel |
| **List / grid / feed / carousel** | Repeated same-shaped rows in a scroll container | A list (§6.1) |
| Section header | Chrome text between groups of rows | A list group ("Today", "Pinned") |
| Search field | An editable field with a search hint or icon, or a search button | "How to find one" |
| Index scroller | A column of letters beside a list | "A–Z, jump by letter" |
| Detail view | Reached from a list row, one large item, back arrow | An item template (‹a chat›) |
| Composer | An editable field at the bottom with a send button | "Write and send" (send always asks) |
| Settings list | Rows with switches or chevrons | A settings tree (toggles never walked) |
| Form / wizard / stepper | Labelled fields, Next or Continue | A form (typed slots only) |
| Empty state | An illustration and one call to action | A fact ("empty until you …") |
| Login wall, paywall, upsell | Sign-in or plan words from the lexicon | A boundary (the pass stops there) |
| Media player | Play/pause/seek controls | Player controls |
| Picker (files, photos, contacts) | A system or in-app chooser | "The pick is yours" (the Mind or the owner chooses) |

- **No app-specific rules.** A CI guard fails if the mapper or manual code names an app package.
- Every new pattern is added with fixtures from several apps, never tuned to one.

### 5.3 Templates, not copies

200 chat rows are **one** template, ‹a chat›, and 200 chats open **one** screen, *Chat (‹a chat›)*. The structural
fingerprint already groups screens with the same shape. The manual adds the template's parameter (which slot
changes) so that "open my chat with Sam" resolves to *Chat* reached through the list's "how to find one".

### 5.4 Choosing what to explore next

Each unexplored thing gets a **value**:
- how much of the app it probably opens;
- whether it answers an open question;
- whether it is a new pattern;

minus its cost and its risk. The mapper then takes the best one. In practice:
1. revealers and category strips on known screens first (cheap, and they uncover the most);
2. then navigation to unseen sections;
3. then one sample per list;
4. never a second copy of a template.

A section stops when new probes stop adding anything (saturation). The budget chips (10 min, 30 min, …) still cap
the whole pass.

### 5.5 The self-quiz: finding its own gaps

When a pass ends, the mapper tests itself:
1. **Quiz.** The model writes about 20 plain-language goals a person might have in this app ("see message requests",
   "find a chat from last week", "turn on dark mode"). It then tries to answer each one **from the manual alone**,
   without the phone.
2. **Gaps.** A goal it can't answer, or can answer only with low confidence, becomes a target for the next pass. "Map
   deeper" then means "answer these", not "wander longer".
3. **Contradictions.** A review call reads the whole manual for conflicts ("Search is on Messages" vs "Search is in
   Chat") and marks them for re-checking.

The report in Glass shows how many quiz goals the manual can answer: "Answers 17 of 20 goals · 3 to explore".

### 5.6 Learning after the pass

- **Runs teach it.** A task that succeeds writes or confirms its path as an ability (the Learn engine, now writing
  into the manual). A task that hits a surprise lowers the confidence and queues a re-check.
- **Teach on the phone** records the owner's own taps as an ability with the owner's wording.
- **App updates.** The manual is copied forward as *unverified*. Each path is checked on its next use, and a broken
  selector is found again by its description and string id. "Map again" re-checks only what changed.

## 6. Lists and categories in detail

### 6.1 Lists: how they work, not what's in them

For each list, the mapper records:
- **Item:** a typed slot named from the context (‹a chat›, ‹a person›, ‹a post›, ‹an email›), plus the row's
  *shape* ("name, preview line, relative time, unread dot").
- **Layout:** vertical list, grid, feed or carousel.
- **Order** (§6.3): newest first, oldest first, A–Z, the app's own ranking, or unknown.
- **Groups:** section headers in the app's words, in order ("Pinned", "Today", "Yesterday", "Previous 7 days").
- **Size:** a coarse size ("a few", "tens", "long, loads more as you scroll"), never a count of your items.
- **How to find one**, listing what exists:
  - search, with its hint and where it is;
  - an A–Z scroller;
  - filters or categories;
  - "scroll to it" as the last resort.
- **What a row does:** tap → which template. Long-press → which menu, with its items described and never walked.
- **Positions:** "item 1 is the newest", so positional goals work.

### 6.2 Categories and sub-categories, found automatically

- **What makes a category set:** a category strip (§5.2) where a **switch** probe keeps the screen frame and changes
  the list region. Header links that open the same screen with a different category selected (Instagram's Followers
  and Following) are recognised by the same probe: the same template is reached, with a different selection.
- **The tree:** the mapper nests what it finds as `Screen › Category set › Category › List`, and a category set found
  inside a category becomes a sub-category. Instagram's Messages might be `Messages › Primary | General | Requests`,
  with a category set like *Unread* inside one of them. Close friends is found as its own screen under Settings.
- **Names** are the app's words (from the lexicon). Numbers are dropped.
- Each category gets its own list entry (order, how to find one), because categories often differ. Requests, for
  example, may have no search.

### 6.3 Knowing the order without keeping anything

Order comes from the **shapes** of what the rows show, read and then discarded:
- **Time shapes** ("2m", "3h", "Yesterday", "Mon", dates) are parsed as ages. If they only ever grow down the list,
  the order is **newest first**.
- **First letters** are compared. If they never go backwards, the order is **A–Z**.
- **Group headers** from the lexicon ("Today", "Earlier") confirm the time order.
- **Pinned** sections are recognised by their header, or by a pin icon, and noted as "pinned first".

Only the conclusion is stored ("newest first, pinned first"), and the ages and letters are thrown away. If there is
no clear signal, the order is written as "the app's own order".

## 7. The app dictionary: one structure, kept in check

**The owner's question:**
> "How to make sure it stays close to one mapping structure … when it looks for close friends in Instagram it can
> actually understand how to differentiate the different types of people without it all having to come from a text
> explanation. Maybe one controlled organizer agent that keeps sub-categories in check and considers carefully before
> making a new sub-sub-category … like an app dictionary, and then pages tag these confirmed sub-categories."

**The answer: yes, with one change.** The organizer should be a **gatekeeper with rules and a short list of allowed
decisions**, not a free agent. Most of its work is deterministic checks. A model is asked only the one narrow question
that needs judgement, and every change it makes can be undone.

### 7.1 The designs weighed

Six designs were compared. Each is scored 1 (poor) to 5 (strong) on what this system needs:

- **Consistent:** the same thing gets the same name and id across passes, models and app versions.
- **App concepts:** it can hold what only this app has ("Close friends", "Requests").
- **Survives model errors:** one bad model answer can't corrupt the map.
- **Tells things apart without prose:** an agent can check "is this row a close friend?" from structure.
- **Privacy:** it can't turn into a list of your people.
- **Cost / effort:** tokens per pass and build work (5 = cheap).

| Design | Consistent | App concepts | Survives model errors | Tells apart without prose | Privacy | Cost / effort | Total |
|---|---|---|---|---|---|---|---|
| A. Free-text descriptions only (the plan so far) | 1 | 4 | 2 | 1 | 4 | 5 | 17 |
| B. One fixed schema for all apps, written by hand | 5 | 1 | 5 | 3 | 5 | 3 | 22 |
| C. The model invents types freely on every pass | 1 | 5 | 1 | 2 | 3 | 3 | 15 |
| D. Several agents debate each new category | 3 | 4 | 3 | 2 | 4 | 1 | 17 |
| E. No names: embeddings cluster similar things | 2 | 3 | 3 | 2 | 2 | 3 | 15 |
| **F. Fixed core + per-app dictionary behind a gatekeeper** | **5** | **5** | **4** | **5** | **5** | **4** | **28** |

- **A drifts.** "Followers", "People who follow you" and "Your followers" become three things, and nothing can be
  checked.
- **B is consistent but blind to anything it didn't foresee.** It would slowly fill with app-specific rules, which §5.2
  forbids.
- **C is the owner's worry realised:** a new sub-sub-category every pass, with synonyms and orphans.
- **D costs several model calls per decision and still has no memory of past decisions.** Consistency comes from
  stored ids and rules, not from debate.
- **E can't explain itself or be checked,** and embeddings of rows risk encoding content.
- **F takes B's consistency for the few things every app shares, and a governed dictionary for the rest.** Its only
  weakness, a wrong merge or split, is handled in §7.6.

**Chosen: F.**

### 7.2 Layer 1: a small fixed core (the same in every app)

About 25 **core kinds**, fixed in code and changed only by a release: **Person, Conversation, Message, Post, Media,
File, Link, Place, Event, Task, Product, Order, Payment, Account, Setting, Notification, Group, Page/Channel, Search
result, Draft, Collection, Tool/Connector, Model/Assistant, Other**.
- Every list item, typed slot and ability parameter is **one core kind** or a set under one.
- This is what lets "message a person" mean the same in WhatsApp, Instagram and Gmail, and lets the ability index
  match across apps.

### 7.3 Layer 2: the per-app dictionary

Each app has one dictionary, shared by all of its versions, and each entry carries the versions it was seen in. An
entry is one of four things:

| Entry | Meaning | Instagram example (illustrative) |
|---|---|---|
| **Set** | A named group of one core kind, defined by where the app shows it | **Followers** ⊂ Person · **Following** ⊂ Person · **Close friends** ⊂ Person · **Message requests** ⊂ Conversation |
| **Relation** | How two sets relate, stated only when proven | Close friends and Followers: *overlap unknown* until evidence shows otherwise |
| **Marker** | A structural sign the app uses to show membership or state | "Remove" button on a row (only in Followers); a badge with the app's content description; an unread dot |
| **View** | A category or filter that shows a set | Messages › **Requests** shows Message requests |

Each entry holds these, and **no prose is needed to use it**:
- a **stable id** that is never reused (`set:ig.close_friends`);
- its **core kind** and its **parent** (a set can sit under another set, up to 3 levels deep);
- its **canonical name, from the app's own words** (lexicon or chrome), plus aliases;
- its **anchors:** where it is listed (a screen and category path), its markers, and how membership changes (the
  action, with its risk);
- its **recognition signature,** which lets a walker **test membership from structure** at run time:
  - "this row is in the Close friends list";
  - "this row shows the *Remove* marker, so the person follows you";
- **provenance, confidence and status:** *candidate*, *confirmed* or *locked* (owner-locked).

**How this tells people apart without text.** A Person in Instagram isn't described in words. The map knows
*structurally* which sets it can be in, where each set is listed, which marker shows membership, and how to check one.
So "add Sam to close friends" becomes:
1. Resolve the set (`set:ig.close_friends`).
2. Take its "membership changes" anchor. That is a change, so it asks you.
3. Before acting, check with the recognition signature that Sam isn't already in it.

The one-line descriptions stay, but only as a help for reading. The ids and anchors carry the meaning.

**Privacy: sets are defined by structure, never by members.** The dictionary may say *Close friends* exists, where it
is listed and how to check someone. It never stores who is in it, how many there are, or any name. A CI guard rejects
any dictionary field that isn't chrome, a slot, a shape or an id, and the canary check covers the dictionary too.

### 7.4 Pages tag the dictionary; they don't describe people

Screens, lists, categories, markers and abilities point at dictionary ids instead of free text:
- the Followers screen's list: `item: set:ig.followers ⊂ core:Person`, `view of: set:ig.followers`;
- ability "Open a chat with ‹Person›": parameter `core:Person`, with the path Messages › Search;
- ability "Add ‹Person› to ‹Close friends›": parameter `core:Person`, target `set:ig.close_friends`, effect `change`
  (asks you).

When the Mind or JEV reads the manual, it gets a **glossary block** first (the app's sets, with parents and anchors,
usually 10–40 lines) and then the relevant screens. Words in a goal ("close friends", "beste vrienden", "my CF list")
match a set by its name, aliases and paraphrases, and the set leads to its anchors.

### 7.5 The organizer (gatekeeper)

Describers and probes **propose**; only the organizer **admits**. It runs once at the end of each pass, on the batch of
proposals, and also when a run or a teaching session proposes something.

**Step 1: deterministic gates.** A proposal fails early if any gate fails.

| Gate | The rule |
|---|---|
| **Named by the app** | The name must be the app's own words (lexicon or stable chrome). The model can never invent a set name. |
| **Anchored** | The set must have a structural anchor: its own list, a category or filter that shows it, or a marker only its members carry. |
| **Not a duplicate** | Same string resource id, same anchor fingerprint, or a normalised alias match → merge into the existing entry, never create. |
| **Seen twice** | Seen in 2 observations, or 1 probe with a confirmed prediction. Otherwise it stays a *candidate*. |
| **Depth and fan-out** | At most 3 levels under a core kind, and at most 12 children per parent before a review is required. |
| **Useful** | Referenced by a list, a view or an ability, or needed to answer a self-quiz goal. Otherwise it stays a candidate. |

**Step 2: one narrow model question, only for what the gates can't settle.** This is a typed choice with a fixed set
of answers:
- `NEW(parent)`: a new set under this parent;
- `SAME_AS(id)`: the same set as an existing one (becomes an alias);
- `SUBSET_OF(id)`;
- `KEEP_CANDIDATE`;
- `REJECT`.

It sees the proposal's evidence (anchors, markers, app words) and the nearby dictionary entries, never content.
- It is one batched call per pass, typically 0–5 questions.
- A decision-shaped question is exactly what **JEV** is built for. JEV watches these decisions from M2, next to the
  model, and can take them over when its numbers earn it.

**Step 3: audit.** Every admission, merge and split is recorded with its evidence. The owner sees it in Glass.

### 7.6 Keeping it correct over time

- **Mistakes can be undone.** A merge keeps the old id as a redirect. A split gives new ids and repoints what tagged
  the old one. Abilities keep working through redirects.
- **Owner locks.** The owner can rename (as an alias), merge, reject or **lock** an entry in Glass. The organizer never
  changes a locked entry.
- **App updates.** An entry not seen in a new version is marked *not seen in 404.0*, not deleted. It is removed only
  after two passes without it.
- **Model changes.** Ids, gates and stored decisions don't depend on which model is picked (§9). A new model can only
  propose, and the gates judge the same way.
- **Health check.** After each pass the organizer reports orphans (sets nothing points at), near-duplicates (similar
  anchors), sets that are too deep or too wide, and candidates older than 30 days. The self-quiz (§5.5) also asks
  set-shaped goals ("who are my close friends?" → where to look, not names).

### 7.7 What it improves, and what it could hurt

**Improves:**
- **Consistency:** one name and one id per concept, across passes, models and versions.
- **Precision:** abilities take typed parameters, so "close friends" can't be confused with "followers" when their
  anchors differ.
- **Checks at run time:** membership is tested from structure before acting.
- **Smaller prompts:** a glossary instead of paragraphs.
- **Transfer across apps:** core kinds let "message a person" work anywhere, and set patterns seen in other apps
  (blocked, muted, requests) become hypotheses to probe. They are never admitted without evidence.

**Could hurt, and the guard against each:**

| Risk | Guard |
|---|---|
| Over-structuring (too many tiny sets) | "Useful" and "seen twice" gates; fan-out limit; candidates don't show to agents by default |
| A wrong merge or split spreads | Redirects and reversible ids, the audit, owner locks, health report |
| Rigidity: something doesn't fit the core | `Other` as a core kind; the core grows only through a release with fixtures |
| Delay | The organizer runs at the end of a pass; during the pass, candidates are used provisionally |
| Cost | One batched model call per pass, and zero when the gates settle everything |
| Privacy creep ("list of close friends") | Structure-only fields, CI guard, canary |

### 7.8 As built (alpha.59)

- **Core:** `manual/CoreKind.kt`: 24 kinds plus Other, with generic hint words.
- **The app's words:** `manual/AppLexicon.kt`.
  - The app's shipped strings, hashed. Templates match through digits only.
  - A *vocabulary* proof covers downloaded names (every word shipped, the phrase not). The reader does not propose
    them in production (`allowVocabulary = false`): a user's own folder or group-chat name can look the same.
    Screen titles must be exact app strings.
- **Reader:** `manual/StructureReader.kt`.
  - Finds the title, category strips (with a selection, above 85% of the screen height), lists (row shape, lexicon
    section headers, search, markers on at least half the rows) and a kind guess.
  - It proposes a set per titled list and per category. The selected category also owns the list.
- **Dictionary:** `manual/dictionary/DictionaryModel.kt`.
  - Entries, anchors, audit and the JEV tally.
  - `DictionaryPrivacy` checks every text in and out; JSON uses fixed keys.
- **Organizer:** `manual/dictionary/Organizer.kt`.
  - `record` folds proposals by resource, place or name.
  - `run` works in parent-first rounds through the gates, then asks one batched question (at most 8 items) with the
    fixed choices.
  - Also: redirects, owner edits, health, glossary, and retiring after two missed passes.
- **The question:** `OrganizerPrompt.kt`, with a strict parser. JEV's typed choice is watch-only (`ManualRuntime`).
- **Wiring:**
  - the mapper's `captureOnce` feeds each in-place screen;
  - `learnIfEnded` runs the organizer in the background;
  - `mapping.start` takes `describer: {model}`.
- **Ops and routes:**
  - phone ops `dictionary.get`, `dictionary.edit` (owner) and `models.list`;
  - gateway routes `GET /v1/devices/{id}/dictionary`, `POST …/dictionary/edit` and `GET …/models`;
  - the Glass Dictionary tab and the start-sheet model picker;
  - the Mind gets the glossary with the map.
- **Not yet:** revealers and probes (§5.1), the describer and abilities (M2), the screenshots switch, JEV ability picks
  and the Lab dictionary-stability suite.

### 7.9 As built (alpha.60): the App Manual foundation

- **Reveal doors:** `MappingDoorKind.REVEAL` covers icon or floating buttons whose description or id says add, more,
  attach, create, new or options.
  - Their priority comes after tabs and menus.
  - They pass the same safety check as every door; `MapperDoorRisk` now also refuses add-to, buy, checkout, call and
    similar.
  - Text rows are never revealers.
- **Screen and door cards:** `ScreenCard` and `DoorCard` in the dictionary store (schema 2), keyed by room key and
  Atlas edge id.
  - `ManualScreens.observed` sets the title, selected category and button words.
  - `ManualScreens.verified` sets the door words, the name from the door and `panelOf` for a reveal.
  - The Atlas stays structure-only, and Glass lays the names over the map (`applyManualNames`).
- **One-pass proof:** `PassMemory` marks categories proven when the same strip is seen with different selections;
  `Gate.SEEN_TWICE` accepts `proven`.
- **App word or yours?:** the reader sees downloaded names, but `PassMemory.split` records only app strings.
  - A proven downloaded name waits in `ReviewQueue`, held in memory only.
  - The owner's `app_word` calls `Organizer.ownerAdmit`; `mine` keeps a hash only (`declined`).
- **Agents:** the glossary lists panels (what opens them, what they offer) and named screens.
- **JEV is parked by the owner** (2026-09-28). The organizer watcher from alpha.59 stays watch-only, and nothing new
  uses JEV.

### 7.10 As built (alpha.64): abilities, the navigator and the self-quiz

- **Lists:** `manual/ListOrder.kt` concludes newest first, oldest first or A–Z from row shapes (ages, weekdays, dates,
  first letters). The texts are read in `StructureReader.findLists` and dropped there; only the order is kept, on the
  list anchor and on the screen's `ListNote` (shape, order, section headers, search).
- **Screen cards** also keep the phone's structural page keys (for "where am I") and a describer-written `purpose`.
  Dictionary schema 3 adds `abilityStats`, `phrasings` and `quiz`, all through `DictionaryPrivacy` (model text:
  `sentence`, one line, no digits runs, emails or links).
- **Abilities** (`manual/Abilities.kt`) are derived, never stored: open a screen, open a panel, switch a category,
  an offer in a panel, a button on a screen, find one in a list. Stable ids (`ab:` + hash). Paths are the words on the
  doors from the app's first screen. A button the approval rules would ask about is marked "asks". Runs teach them:
  a walk that arrives marks the ability walked and raises its confidence; one that stops lowers it.
- **The index** (`AbilityIndex`): rarity-weighted word coverage (0–1) with generic verb folding; the Tier 0 rule is a
  score ≥ 0.8, 0.3 ahead of the next, and a navigation-only walk.
- **The navigator** (`ManualNavigator`) walks only doors the mapper walked, by their words, and checks every landing in
  plain code: the page key, else the place's title or most of its button words. The first surprise stops it. It never
  taps an ability's pick (an offer, a button); a switch taps its category last.
- **The describer** (`ManualDescriber`) runs after the organizer with the pass's model: one call writes purposes,
  phrasings and about 20 goals. `SelfQuiz` answers each goal from the index alone; unanswered goals are the gaps.
- **Map deeper:** `mapping.start {deeper: true}` (a flag only). The phone turns its own quiz gaps into focus words and
  tries doors with those words first; they still pass the safety check.
- **The Mind:** `abilities_find(goal)`, `go_to(ability=a3)` and `how_to_find(list)`, plus a few manual lines that fit
  the mission's goal with the map card. Choosing, typing and confirming stay the Mind's, with the usual approvals.
- **Surfaces:** phone op `manual.get` (read only; abilities, hits, quiz, scores, Markdown); gateway route
  `GET /v1/devices/{id}/manual`; the Glass **Abilities** tab (search, Try it, the quiz report, Map deeper, Export
  manual); the agent MCP's read-only `phone_app_manual`.
- **Not yet:** masked screenshots for the describer, sharing manuals between phones, the Lab find-the-feature suite
  with hand-written goals (the self-quiz stands in until the owner's first passes), JEV (parked).

## 8. How agents use the manual: rapid navigation in three tiers

| Tier | Who decides | When | Cost |
|---|---|---|---|
| **0 — Index + walker** | No model | One ability matches clearly (score ≥ 0.8, margin ≥ 0.3 over the next) and its path is navigate, reveal or switch only | About 0 tokens, 1–3 s |
| **1 — JEV picks** | JEV (a typed choice with calibrated confidence) | Two to five abilities are close. JEV is asked "which of these fits the goal?", with "none" as a choice | About 0.2 s, nearly free |
| **2 — Mind with an excerpt** | The Mind | Anything else, and every type, choose or send step | 3–5 relevant manual lines instead of the 40-line map dump |

- **The index lives on the phone:** BM25 over ability names and paraphrases, screen, panel, list and category names,
  and the app's words. Paraphrases are written once when an ability is created, in several languages, so matching is
  cheap. There is no network call and no new dependency.
- **JEV starts watch-only**, as in Drive (plan 32):
  - its pick is logged next to the Mind's and never used;
  - it is promoted only after the Lab numbers earn it (≥ 95% agreement when confident, over at least 200
    decisions);
  - even then it picks only among navigation abilities, and never approves, types or chooses content.
- **New Mind tools:**
  - `find(goal)` returns the top abilities, with their paths and confidence;
  - `go_to(ability)` walks an ability's path the way `go_to` walks to a screen today;
  - `how_to_find(list)` returns a list's search, filters and order.
- **Shortcuts first:** deep links and intents the app exposes are recorded, and `phone.open_app` / intent landing
  already comes first.
- **Unchanged rules:**
  - every tap goes through PhoneToolExecutor, with a check after each step;
  - the first surprise hands control back to the Mind;
  - the approval boundaries are unchanged.

## 9. Settings: the model and screenshots

**In Glass, on the mapping start sheet** (remembered per app, with a default in Glass settings):

> **Model** [ Phone's model · Claude Fable 5.1 ▾ ]
>
> **Use screenshots** [ off ]
> Better results. Your chats and names are blacked out, but a picture of the app goes to the AI.

- **The model list** comes from the phone: a new `models.list` op returns the phone's `ModelRegistry` profiles and
  whether each one reads images.
  - The first entry is always **Phone's model**, showing its current name. The phone resolves it from
    `openrouter_model` when the pass starts.
  - The choice is sent with `mapping.start` as `describer: { model: "phone" | <cycloneId>, screenshots: bool }`.
  - Keys never leave the phone.
- **A model that can't read images** keeps the switch off and shows "This model can't read pictures."
- **Masking** paints every content region solid before a screenshot leaves the phone, using the same chrome/content
  split. The echo check (§4) still applies to what the model writes back.
- **On the phone,** App Maps settings shows the same two choices for passes started there.

## 10. Glass

- **Map tab:**
  - real screen names with a one-line purpose under each;
  - panels hang off their screen;
  - lists show as one card ("‹a chat› · newest first · Search");
  - category sets show as tabs on the card;
  - zones get real names;
  - each list and category shows its dictionary set as a small tag ("Person › Close friends").
- **Place inspector:**
  - *What it's for*;
  - *Controls*, by region, with their effect and risk;
  - *Panels*;
  - *Lists* (order, groups, how to find one);
  - *Categories*;
  - *Abilities that start here*;
  - Facts.
- **Abilities tab, "What you can do in Instagram":**
  - a search box ("try: see message requests");
  - each ability with its path on the map, its provenance and confidence;
  - **Try it**, which walks the safe part on the phone.
- **Report:**
  - "Answers 17 of 20 goals · 3 to explore" (§5.5);
  - **Map deeper** explores exactly those.
- **Dictionary tab:**
  - the app's sets as a tree under their core kinds, each with its anchors, markers, versions and status
    (candidate, confirmed, locked);
  - **Rename** (as an alias), **Merge**, **Reject** and **Lock**;
  - the organizer's audit ("Close friends admitted: named by the app, own list, seen twice") and the health report.
- **Export manual** gives the §3.1 Markdown, with the glossary block first.
- **Agent MCP:** a read-only `app_manual(app, query?)` tool. It returns manual text, never content.

## 11. Measuring it: the Lab suites

- **Find the feature:**
  - 30 plain-language goals per app across 5 apps (ChatGPT, Instagram, Gmail, WhatsApp, Settings), each with its
    ground-truth path written once by hand;
  - measured: top-1 and top-3 match, success, taps, seconds, model calls and tokens;
  - compared: Mind alone, Mind + map card (A37), Mind + manual, and the tiered routing.
- **Map quality:** for each app, a hand-written checklist of the lists (with their order and search), the category
  sets (with sub-categories) and the key panels. Scored as the share the pass found correctly, with and without
  screenshots, and per model.
- **JEV:** agreement and calibration on ability picks and on organizer decisions.
- **Dictionary stability:**
  - map the same app twice with different models, and check the share of sets that get the same ids;
  - count duplicates and orphans left after the organizer runs;
  - check against a hand-written list of each app's sets.
- **Privacy:** the canary scan in every suite. One hit fails the suite.
- **Targets for M3:**
  - ≥ 85% top-1 ability match;
  - ≥ 90% success on navigation goals;
  - ≥ 60% fewer model calls than Mind alone;
  - ≥ 80% map-quality score;
  - ≥ 90% of sets matched across two models, and 0 duplicate sets after the organizer runs;
  - 0 canary hits.

## 12. Safety (CI-guarded where marked)

- **Never kept:** content. Only the app's own words, typed slots and shapes are stored. *(guard: canary, echo
  check)*
- **Only checked words** go to the model. Screenshots are sent only when the switch is on, and always masked.
  *(guard)*
- **Look-only probes** are reveal, switch, scroll, sample, long-press reveal and back. They never type, choose,
  toggle, send, follow or sign in. *(guard, via `MapperDoorRisk`)*
- **No app-specific rules** in the mapper. *(guard)*
- **The dictionary holds structure only:** set names from the app's own words, anchors, markers and ids. It never
  holds members, counts or names of people. *(guard: field whitelist, canary)*
- **Only the organizer admits sets.** Describers and models can only propose. *(guard)*
- **The manual is advice.** Walks check every step, stop at the first surprise, and keep every approval boundary.
- **JEV is watch-only** until promoted, and never approves, types or chooses content. *(guard)*
- **The MCP tool is read-only** and returns manual text only. *(guard)*

## 13. What changes in the code

- **Phone, `brain/graphv2/AtlasContracts.kt`:**
  - `AtlasPrivacy` gains `AppLexicon`, `ChromeFilter` and `ContentShapes`;
  - `coarseStructure` stays only as the last fallback.
- **Phone, `mapping/crawl/`:**
  - `MappingStructuralProjection` keeps chrome labels and string ids, and groups regions;
  - new `patterns/` (the detectors), `Probe` (reveal, switch, scroll, sample, long-press) and
    `ExplorationPlanner` (§5.4);
  - `SafeMapperWalker` runs probes with predicted outcomes.
- **Phone, new `manual/`:**
  - `AppManual` (store, schema, versions, provenance);
  - `ManualDescriber` (hypothesise and revise calls, JSON schema, echo check, masked screenshots);
  - `SelfQuiz`;
  - `AbilityIndex` (BM25, paraphrases);
  - `ManualRenderer` (Markdown, glossary block, Mind excerpts);
  - `CoreKinds` (the fixed core, in code);
  - `AppDictionary` (entries, ids, redirects, versions, locks);
  - `Organizer` (the gates, the batched typed-choice question, the audit and the health report).
- **Phone, `mind/`:**
  - `find`, `go_to(ability)` and `how_to_find`;
  - excerpts replace the map card;
  - Learn writes abilities.
- **Phone, `voice/JevShadow.kt`:** becomes a shared `Jev` client, with a second watch-only question.
- **Phone ops:**
  - `models.list`;
  - `mapping.start` gains `describer`;
  - `manual.get`, `manual.search` and `manual.export`;
  - `dictionary.get` and `dictionary.edit` (rename, merge, reject, lock: the owner only, from Glass).
- **Gateway:** forwards the new ops and adds the read-only agent MCP tool.
- **Glass:**
  - the start sheet's model picker and screenshots switch;
  - Map tab, inspector, Abilities and Dictionary tabs, report and Export.
- **Lab:** find-the-feature, map quality, JEV ability tally and the canary scan.

## 14. Releases

| Release | Contents | Exit criteria |
|---|---|---|
| **M1: The app's own words, lists and categories** | App lexicon, chrome filter and content shapes; the pattern library; reveal, switch, scroll and sample probes; lists (order, groups, how to find one) and category sets with sub-categories; real names in Glass; the model picker and screenshots switch; canary guard. **Dictionary foundations:** the core kinds, the dictionary store with stable ids, and the deterministic gates (sets admitted only when named by the app, anchored and seen twice); lists and categories tag set ids | On the owner's phone: ChatGPT shows Chat, Sidebar, the Add panel from "+" and the chat list "newest first · Search chats". Instagram shows Messages' Primary / General / Requests and Profile's Followers / Following, each as a set under Person or Conversation. Two passes give the same ids. 0 canary hits |
| **M2: The describer, abilities and self-quiz** | Hypothesise → probe → revise loop; abilities with paraphrases and provenance; the self-quiz and targeted Map deeper; Learn and teaching write abilities; Abilities tab; Export; `app_manual` MCP tool. **The organizer:** the batched typed-choice question, redirects for merges and splits, owner locks, the audit and the health report, the Dictionary tab, and JEV watching organizer decisions. Abilities take typed parameters | "Add a connector to a chat" and "see message requests" are found and walked; "add ‹Person› to Close friends" resolves to its set and asks first; the report shows the quiz score |
| **M3: Rapid navigation** | Ability index; Tier 0 walks; `find`, `go_to(ability)`, `how_to_find`; Mind excerpts; JEV watching; the Lab suites; diff passes; self-healing selectors | The §11 targets on the owner's phone, stated honestly |

These are runs 3–5 in plan 35 (alpha.59–61), ahead of parallel sessions. JEV's promotion for abilities follows its
Lab numbers, as in Drive.

## 15. Still open

- **Sharing manuals** between the owner's phones through the Command Center: planned for M2. Manuals hold no content.
- **A public manual library**, as marketplace cards, is later and needs its own decision.
