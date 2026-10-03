# Cyclone V5 Alpha 67: memory that listens and stays tidy

Developer alpha for owner testing. It builds on Alpha 66 (the mission workspace) and includes it.
- **Mobile:** `5.0.0-alpha.67.dev1` (version code 212).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.67.dev1.exe` (runtime `5.0.0-alpha.67.dev1`).
- **Glass:** `1.0.0-alpha.40` (unchanged).

Say "remember…" in a task and Cyclone keeps it, the way ChatGPT and Claude do: it saves it, shows **Memory updated**,
and uses it next time. Memory is built like mem0, the open-source memory layer: each new memory is compared with what
is already there and becomes an add, an update, a merge or nothing, so it does not fill up with near-copies.

## What changed

**1. "Remember…" is never missed.**
- **Where it listens:** the task itself ("Send Lou my ETA. Remember her Instagram is lo.06"), a message you send
  while a task runs, and your answer to a question Cyclone asked.
- **What it listens for:** "remember", "don't forget", "keep in mind", "note that", "from now on", "next time",
  "onthoud", "vergeet niet", "voortaan", "de volgende keer". Questions ("do you remember…?") and reminders ("remind
  me at 7") are not memory requests.
- **Named for the AI:** a request mid-task is written into the conversation as its own note, so it is acted on even
  when the AI is busy with something else.
- **It can't be skipped:** if you asked and nothing was saved, the first "done" gets one reminder to save it (or say
  why not). A second "done" is always accepted.

**2. Memory updated.** Every new or changed memory shows "Memory updated: …" on the task card and is kept in the
task's history. Settings → AI → **Memory** lists everything, grouped as People, Your preferences, Apps and Other, with
"You asked Cyclone to remember this" or "Cyclone kept this" under each, and Forget / Forget everything.

**3. Tidy by design.**
- **One card per person:** "Louella — girlfriend; Instagram: lo.06; WhatsApp: Louella ❤️". New details merge into the
  card; a changed handle replaces the old one, which is kept in the card's history.
- **Four kinds:** people, your preferences, how you use apps ("Instagram: post from @mybrand"), other facts.
- **Add, update or nothing:** the same memory again changes nothing; a close rewording updates the old one (the old
  text is kept in history); related ones are shown to the AI so it can say which one the new memory `replaces`.
- **Durable only:** one-off values ("the ETA today is 22 minutes") are refused unless you asked for them.
- **Full memory:** what Cyclone kept on its own goes first; what you asked for goes last.

**4. The right memories at the start of a task.** The people your task mentions come first (by name, by relation like
"my girlfriend", or by handle), then what you asked to keep, then what fits the task, grouped like a small profile.

## Safety and privacy

- **Secrets are never kept:** passwords, codes, keys and card or account numbers are refused in every field. (tested,
  CI-guarded)
- **People only when you told Cyclone about them.** It can't remember someone it only read about on a screen; it asks
  you first. (tested, CI-guarded)
- **Encrypted on the phone** with an Android Keystore key (AES-GCM). The plain memory file of earlier versions is read
  once and encrypted on its next save. (tested, CI-guarded)
- **Lab runs never read or write your memory.** (CI-guarded)

## Validation and limits

Tests that pass:
- **Phone:**
  - `MemoryV2Test` (13 tests): recognising requests (English, Dutch, not questions or reminders); one card per person
    with merged handles and history; add/update/nothing and `replaces`; app notes; one-off values and secrets refused;
    what you asked for kept last; the task-start digest; encryption and reading an older plain file; the one-time
    reminder before "done"; a request sent mid-task; people only when you told Cyclone about them; similar memories
    shown to the AI.
  - `MindMemoryTest` (earlier behaviour) still passes.
  - The full phone suite: 2078 tests, 0 failures.
- **Gateway, MCP and Glass:** unchanged; their suites pass.
- **CI guards:** a new `test_memory_guard.py` (6 guards); all 215 pass.

Limits:
- **Physical: UNVERIFIED.** On the Pixel:
  1. "Open Instagram. Remember that Louella is my girlfriend and her Instagram is lo.06." Look for "Memory updated"
     and the card in Settings → AI → Memory.
  2. In a new task, "send my girlfriend a heart on Instagram": it should know who and which account.
  3. While a task runs, send "from now on use the bike". It should save it before finishing.
- **Not in this release:** editing a memory in place (forget it and say it again), a memory view in Glass,
  a "people" Lab suite, and recipes and traps from past runs (plan 37 W4).
- **A new phone or a reset Keystore** can't read the encrypted memory; Cyclone then starts with an empty memory.
