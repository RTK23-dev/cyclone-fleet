# Overnight run — 2026-09-24

The owner asked for one long coding session: build the V5 plan out on top of alpha.10 / Cyclone One 1.6.0-alpha.10, push
checkpoints and cut as many alphas as possible. This is the record. **Nothing below was tried on a physical phone**; every
release passed exact-source CI (phone build + tests, Windows installer build, Glass tests) and nothing more.

## Releases published tonight

| Release | Phone | Cyclone One | Glass | Headline |
|---|---|---|---|---|
| [v5.0.0-alpha.10.dev1](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.10.dev1) | 5.0.0-alpha.10.dev1 | 1.6.0-alpha.10 | 1.0.0-alpha.3 | Devices: connect with a code + Allow |
| [v5.0.0-alpha.11.dev1](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.11.dev1) | 5.0.0-alpha.11.dev1 | 1.6.0-alpha.11 | 1.0.0-alpha.5 | Run inspector v2, Scenarios, Versions |
| [v5.0.0-alpha.13.dev1](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.13.dev1) | 5.0.0-alpha.13.dev1 | 1.6.0-alpha.13 | 1.0.0-alpha.6 | Knowledge, Screens / Runs tabs, mapping passes in Runs |
| [v5.0.0-alpha.14.dev1](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.14.dev1) | 5.0.0-alpha.14.dev1 | 1.6.0-alpha.14 | 1.0.0-alpha.7 | Mapping depth, type + scroll from the PC, Teach |
| [v5.0.0-alpha.15.dev1](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.15.dev1) | 5.0.0-alpha.15.dev1 | 1.6.0-alpha.15 | 1.0.0-alpha.8 | Home, Sign in scenarios, Never pressed |
| [v5.0.0-alpha.16.dev1](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.16.dev1) | 5.0.0-alpha.16.dev1 | 1.6.0-alpha.16 | 1.0.0-alpha.9 | Linked PCs, wrong room, compare with the last good run |
| [v5.0.0-alpha.17.dev1](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.17.dev1) | 5.0.0-alpha.17.dev1 | 1.6.0-alpha.17 | 1.0.0-alpha.10 | App Issues tab, live runs, PC connected notice |
| [v5.0.0-alpha.18.dev1](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.18.dev1) (morning, after the first device test) | 5.0.0-alpha.18.dev1 | 1.6.0-alpha.18 | 1.0.0-alpha.11 | Self-healing live view, real USB reasons, Glass shows its Cyclone build |
| [v5.0.0-alpha.19.dev1](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.19.dev1) | 5.0.0-alpha.19.dev1 | 1.6.0-alpha.19 | 1.0.0-alpha.12 | Wi-Fi screen share (AnyDesk-style, phase 1) |
| [v5.0.0-alpha.20.dev1](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.20.dev1) (other session) | 5.0.0-alpha.20.dev1 | 1.6.0-alpha.20 | 1.0.0-alpha.12 | Teal Matrix v2 phone visuals |
| [v5.0.0-alpha.21.dev1](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.21.dev1) | 5.0.0-alpha.21.dev1 | 1.6.0-alpha.21 | 1.0.0-alpha.13 | Multi-app navigation (v5/navigation, reviewed and fixed) + Teal Matrix v2 |
| [v5.0.0-alpha.23.dev1](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v5.0.0-alpha.23.dev1) | 5.0.0-alpha.23.dev1 | 1.6.0-alpha.23 | 1.0.0-alpha.13 | Reliability from the alpha.22 device runs + the `cyclone` terminal command |

Another session works on the same version line: it published alpha.9 (Teal Matrix redesign) and claimed alpha.12 (calmer
Trace Field, no release tag). Their branches were merged in, not overwritten, so every release above contains that work.

## What changed, in the owner's words

- **Connecting is like WhatsApp Web.** Glass → Devices → Connect shows a six-digit code; the phone shows "Connect this PC?"
  with the same code; Allow. The phone's PC Gateway settings now list **Linked PCs** with Active now / last used and
  **Log out** per PC.
- **Glass opens on Home**: what needs you, runs over the week, what Cyclone knows, a downloadable report.
- **Why did a run fail?** The inspector shows rooms per step, map vs model, the route on the map, the cause with a fix link
  (`stale-door`, `door-missing`, `wrong-room`, login wall…), the scenarios it reached, a comparison with the last good run
  of the same sentence, and **Ask again**. Runs can be grouped by sentence (**Goals**). Running runs update live.
- **Issues** per app: every open problem with a button to its fix. Runs update live while they are going.
- **Apps**: Map (depth: Quick / Standard / Deep, teach from the map), Screens, Scenarios (Sign in / Already signed in first,
  health from real runs, board view, mapping pass or your teaching, stale flag), Versions (needs remap), Runs.
- **Knowledge**: Vault slots as set / not set, **Never pressed** (guarded pay / send / delete doors per app), skills,
  automations. Settings has a Safety card and the keyboard shortcuts.

## alpha.21: multi-app navigation

Another agent built `v5/navigation` from [`HANDOFF-navigation-multi-app.md`](HANDOFF-navigation-multi-app.md) (draft PR #178)
and ran out of usage before closing two gaps it had found. A review confirmed both and found a third; all three are fixed
in alpha.21 with tests (`NavigationHardeningTest`):

- Learned App Learner routes could click an account-creation submit without approval → they now pass the same clause
  boundary as model taps.
- Screens captured during a mapping pass were labelled live → observations now record their producer; only ordinary live
  captures feed the task ledger, clause proof and people memory.
- Every sentence naming an app took the clause route and lost Stage 1 Fast Path and compiled skills → clause runs are
  used only when a sentence needs them; single-clause runs may replay skills, and clause proof still decides completion.

Test it with the five sentences in [`12-navigation-eval.md`](../12-navigation-eval.md) and record results there.

## Left for the owner (not done on purpose)

- **Bookmarkable Glass session** (a codeless `/v1/glass/session/direct` endpoint and a fixed port) — blocked by the safety
  classifier; Glass still opens through the one-time launch link from Cyclone One or `cyclone-device-gateway glass`.
- **Clipboard sync on by default** — blocked by the safety classifier; it stays opt-in.
- **Physical-device verification** of everything above: run
  [`HANDOFF-device-test-alpha17.md`](HANDOFF-device-test-alpha17.md).
- Next cuts: redacted before/after frames per step, expected room recorded by the agent itself, per-version forget, data
  retention settings.
