# Cyclone V5 Alpha 93: Grok voice, Repair, cheaper long runs

Developer alpha for owner testing. It builds on Alpha 92 and includes it. It finishes the owner's voice request and
more items from the alpha.90 stress test (`HANDOFF-alpha91-build.md`).

Versions:
- **Mobile:** `5.0.0-alpha.93.dev1` (version code 238).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.93.dev1.exe` (runtime `5.0.0-alpha.93.dev1`).
- **Glass:** `1.0.0-alpha.51` (unchanged).

## Voice: Grok first (§6, owner request)

- Drive and Live voice now prefer **xAI Grok STT** for transcription and **Grok Voice TTS** for speech when OpenRouter
  lists them (≈0.35 s and ≈0.07 s to first audio on OpenRouter, against ≈3 s for Gemini 3.8 Flash TTS). This applies
  to both the fast voice and Best voice.
- The voices are **eve** (the default), ara, rex, sal and leo.
- Your own pick still wins, and without Grok in the live list the old order holds.
- **Test voice** (Drive settings) now also times the first sound of up to two other listed voices, so Grok and Gemini
  can be compared on your phone and network. Keep Grok only if it's really faster here (§6.5: first sound p50 ≤ 0.5 s).

## Accessibility: one-tap Repair

When Cyclone's Accessibility is off, Glass shows **Repair** instead of "Open on the phone":
- pressing it puts Cyclone's service back over USB (the same three fixed settings commands as the automatic repair;
  other services are kept);
- because you pressed it, it works on any phone, not just one where the PC saw it on before. From then on the
  automatic repair covers that phone too;
- at most 6 times an hour;
- only when the setting doesn't stick does the Accessibility list open on the phone.

## Safety

- **Home-screen safety zone (idea 10).** On the home screen, a tap on a banking, payment, sign-in or password app
  (bunq, ING, Rabobank, PayPal, Authenticator, Bitwarden…) is refused unless you named it, or spoke of such apps, in
  the goal, a steer or an answer. The Mind opens the app it needs with `open_app`. In the stress test, a tap one icon
  off opened bunq.

## Cheaper, steadier runs

- **Conversation compaction (P2-1).** At its ceiling, the old compaction shortened one more old result every turn. So
  every turn sent the maximum, and the changing prefix defeated provider prompt caching. Now:
  - it shrinks to 60% of the ceiling in one go;
  - it then leaves the prefix alone;
  - the ceiling is 100k characters (was 150k).
- **Typing read-back (P2-4).** Fields that show the typed text a frame late, like Settings search, are read again after
  150 ms. Before, they were reported as "could not read the text back" or pasted over.
- **A failed `screen_look`** is logged as the failure. Before, it said "Screenshot attached" with `ok:false` (P3-2).

## Checked

- Locally: voice model order and the transcription request (21 tests), conversation compaction, the home-screen rule,
  gateway Repair tests, the full gateway suite, CI guards (267), MCP tests. Android unit tests (including the
  type-engine settle test) run in Mobile CI.
- Grok latency on the phone: **not measured** (OpenRouter isn't reachable from the build machine). Use Test voice.
- Physical phone and Windows acceptance: **UNVERIFIED**.
