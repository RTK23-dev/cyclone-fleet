# Cyclone V5 Alpha 92: Fewer turns for simple goals

Developer alpha for owner testing. It builds on Alpha 91 and includes it. It continues the fixes from the alpha.90
stress test (`HANDOFF-alpha91-build.md`), this time the ones that cost turns and tokens: a run that already met its
goal kept going, `find` didn't scroll, every key of a calculator was its own model turn.

Versions:
- **Mobile:** `5.0.0-alpha.92.dev1` (version code 237).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.92.dev1.exe` (runtime `5.0.0-alpha.92.dev1`, no PC changes).
- **Glass:** `1.0.0-alpha.51` (unchanged).

## The phone says when a settings goal is met (P1-6)

One auto-rotate run met its goal and then worked on for 45 turns (707k tokens). Now, when a goal consists only of phone
setting changes, the phone checks those settings itself:
- recognised: auto-rotate, dark theme, Do Not Disturb, screen timeout, touch vibration, adaptive brightness, in English
  and Dutch ("turn on auto-rotate and set the screen timeout to 2 minutes", "zet niet storen aan");
- once every one of them holds, the Mind is told once that the goal is met, and finishes;
- strict on purpose: any other words in the goal ("…and tell me the battery") and there is no check, so it can never
  end a task early.

## Fewer turns per action

- **`tap_sequence`** taps several elements of one screen in one call: keys of a calculator or keypad, a row of
  options. Each tap is an ordinary tap with the same checks and approvals. It stops at the first tap that doesn't go
  through, and when the page itself changes. A calculator sum took 16–19 turns.
- **`screen_find` scrolls (P1-13).** When nothing matches, it scrolls down up to 4 screens and looks again ("Android
  version" sits below the fold on About phone). `scroll: false` turns it off.
- **A screenshot stays usable (P2-3).** After `tap_point`, while the page's layout is the same (a keypad, a list that
  didn't move), the same screenshot is valid for the next `tap_point`, instead of costing a new look.
- **Small changes are seen (P2-2).** "The screen did not visibly change" was said 11–12 times a calculator run while each
  digit appeared in the display. When the page's text changed, the result now says so.

## Safety

- **Force stop, clear storage, clear data, uninstall and reset app preferences need your approval** (English and
  Dutch). The stress test saw Cyclone confirm a system warning dialog by itself (P1-11).

## Checked

- Pure checks run locally: the settings-goal parser (English, Dutch, mixed goals refused), the approval words, the
  router phrases; CI guard suite (267). Android unit tests run in Mobile CI.
- Physical phone acceptance: **UNVERIFIED**. Re-run smoke × 3 and the calculator, About phone and settings missions.
