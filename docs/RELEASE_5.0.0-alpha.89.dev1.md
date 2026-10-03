# Cyclone V5 Alpha 89: Instant decisions

Developer alpha for owner testing. It builds on Alpha 88 and includes it. This is the decisions model and Instant
routing chosen in the instant-routing plan: **JEV decides, the phone learns from JEV, and OpenAI Decisions can take
JEV's place later without changing anything else.**

Versions:
- **Mobile:** `5.0.0-alpha.89.dev1` (version code 234).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.89.dev1.exe` (runtime `5.0.0-alpha.89.dev1`).
- **Glass:** `1.0.0-alpha.50`.

## How a request is decided now

In order, the first one that is sure wins:

1. **Your setting.** Speed set to Commands or the Mind decides directly.
2. **The grammar** (on the phone, about 1 ms): "volume up", "open Spotify", "scroll down", answers such as the time.
3. **The rules:** messages, payments and other approval-bound requests go to the Mind; "open X and do Y" goes to Flash.
4. **The phone model**, only for actions it has earned (below).
5. **JEV**, the decisions provider, with a 2.5 second deadline. It answers in one typed shape: mode, action, target,
   confidence.
6. **No JEV** (offline, no key, deadline passed): the Mind, as before.

**Auto is now the default Speed.** Existing choices are kept.

## The lesson store

Every routed request becomes one lesson, kept on the phone only (`files/decide/lessons.jsonl`, at most 2,000):
- the request, the on-screen and app names it could choose from;
- who decided (grammar, rules, phone model, JEV, setting) and how long it took;
- what the phone model guessed silently alongside;
- the outcome: verified by the phone, failed, promoted to a bigger mode, cancelled, or handed to Flash / the Mind.

A request that looks like it carries a secret (a code, a password, a card number) is not kept at all.

## The phone model

- A small classifier trained on the phone, from built-in English and Dutch examples plus JEV's lessons. It never
  learns from its own decisions or from failures; only from the grammar, the rules and JEV when the result backs
  them up.
- App and on-screen names come only from the phone. It never invents an app that isn't installed.
- **It earns each action separately.** It may decide an action (say "volume up") only after at least 50 sure
  guesses on it agreed with JEV at 98% or better, with no more than one failure of its own. One bad streak and the
  action goes back to JEV.
- **JEV still checks it.** One in ten earned decisions goes to JEV anyway, to keep the agreement numbers honest.
- **It works offline.** Without a connection, an earned action is still decided on the phone.

Settings → Speed → **Phone model**: *Use what it has earned* (default), *Learn only*, or *Off*. The card shows
what it has learned so far.

## Numbers, not requests

The phone's health report carries decision numbers only, never request text: how many lessons, who decided,
the share decided on the phone, JEV's median and slowest-5% time, how often Instant was verified, how often the
phone model agreed with JEV, and which actions it earned. The PC keeps only whitelisted numbers, and Glass shows them
under a phone's **Details → Instant decisions**.

## Checks

- Mobile: 9 new `PhoneModelTest` cases (everyday phrasing, targets only from the phone, what teaches and what
  doesn't, bounded lessons with no secrets, earning and losing an action, the numbers, the routing order, offline,
  audits); Mobile CI (unit tests and the APK build) on this commit.
- CI guards: 261, including new decisions-guard checks for the phone model rules and the routing deadline.
- Gateway: full `pytest` suite, including the decision numbers reaching the PC as counts and times only.
- MCP: `unittest` (191).
- Glass: 271 tests, `npm run build`, `glass_guard`.

Physical phone acceptance: **UNVERIFIED** until tested. On the Pixel 8, check:
- Say or type "make it louder", "go up a bit" and "skip this song" a few times: each works, and Glass Details shows
  JEV's time and the lessons growing.
- Airplane mode: "volume up" still works (grammar); an unearned phrase goes to the Mind as before.
- Settings → Speed → Phone model → Off: nothing is learned from then on.
