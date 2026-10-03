# Cyclone V5 Alpha 99: Ports reach the phone

Developer alpha for owner testing. It builds on Alpha 98 and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.99.dev1` (version code 244).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.99.dev1.exe` (runtime `5.0.0-alpha.99.dev1`).
- **Glass:** `1.0.0-alpha.55` (unchanged).

## What's new

Run 4 of Cyclone Ports (plan 48): your phone's runs now use the ports your PC's plugins serve.

**How it connects:**
- **The PC asks the phone:** the phone never calls your PC. The Cyclone runtime on your PC checks each paired phone
  for port messages: every 1.5 seconds while a run is busy, every 5 seconds otherwise.
- **The phone knows when the PC is there:** it counts as connected while your PC checked in the last 20 seconds.
  A run that starts while your PC is connected gets the port tools; a run that starts without it doesn't.

**What a run can do now** (the Mind's two new tools):
- **Send to your PC** (`port_send`):
  - a stage of the run, with a short note;
  - a log line;
  - a screenshot, or the screen's text;
  - account details that aren't secret (a username, a display name).
- **Wait for something from your PC** (`port_wait`):
  - **a code:** a sign-in or verification code your SMS or inbox plugin receives;
  - **a value:** a caption, a name, anything a plugin provides;
  - **a link:** for example a verify link from an email;
  - **a file:** a photo, video or audio file, saved on the phone in the Cyclone folder.

## Codes stay sealed

- **Sealed to your phone:** a code is sealed on your PC to your phone's own key, the one you trusted in Glass →
  Command Center → Phones. If that key isn't trusted yet, no code goes to the phone, and the run says why.
- **Bound to this run:** the seal works only for that run and for the app or site on screen, and only for 5 minutes.
- **Filled without being seen:** the phone opens the code and fills it into the code field through the Secrets Card
  path. The Mind learns only that a code came and how long it is, never the code.
- **Never stored:** the code isn't written to any log, the database, Glass or the run record.

## Privacy

- **Private screens never leave the phone:** a screen with a password, code or card field, or a banking or payment
  app, is never sent as a screenshot or as text.
- **Secrets are taken out twice:** secret-looking fields and lines are removed on the phone, and again on the PC. A
  message the PC still refuses is dropped, and the rest keep flowing.
- **Values are data:** a value from a plugin reaches the Mind as quoted data, never as instructions. A value that
  looks like a secret is refused.
- **Links show their site only:** a link is opened on the phone (https only), and the Mind sees only its site.
- **The run record has no values:** its port lines say what happened ("a code came in", "a value came in"), never the
  value itself.

## Tests

- **New:**
  - `test_ports_phone.py` (8): the PC side end to end with the kit's example plugins. Covers:
    - events and screenshots reach a plugin;
    - a code sealed to the trusted key, opened with a real HPKE decryption and never readable anywhere else;
    - an untrusted phone gets no code;
    - values, links and files; cancel; a plugin missing;
    - a refused message dropped while the rest flow;
    - the polling pace.
  - `PortToolsTest` (10): the Mind's port tools and the outbox link. Covers private screens, codes known by length
    only, quoted values, links reduced to their site, and briefs without values.
  - `PortOutboxTest` (10) and `SealedDeliveryTest` (+2, a code sealed on the PC opens on the phone).
  - Guard `test_ports_run4_guard.py`: port tools only with a PC, no code for the Mind, private screens, codes sealed
    to the trusted key, the phone never calls the PC.
- **Updated:** `test_mission_workspace_guard.py` (48 tools, the two port tools only with a PC).
- **Results:**
  - full phone suite: 2333 tests, 0 failures;
  - gateway: 797 passed;
  - Ports kit: 21 passed;
  - CI guards: all 276 pass.

## Physical acceptance

UNVERIFIED. With the PC runtime running and the phone paired:
1. Glass → Command Center → Phones: trust the phone's key (compare the fingerprint).
2. Add the example SMS and value plugins (Glass → Command Center → Ports).
3. Ask the phone: "Sign in to <an app you own>; wait for the code from my PC". When it reaches the code page, text the
   code to the number your SMS plugin watches. The code field fills, and Glass → Ports → Activity shows "a code came
   in" for the run, without the code.
4. Ask: "Send a screenshot of this screen to my PC". The plugin receives it. On a password screen, it is refused.

## Not yet

- Account Setup's verification steps don't wait on a code by themselves yet; the Mind waits when it reaches a code
  page (run 5).
- The per-run lane on Glass's Run page.
- Run 5 (SMS and inbox sources, key rotation, rate limits, manifest drift) and run 6 (polish and ship).
