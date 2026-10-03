# Cyclone V5 Alpha 100: codes that fill themselves

Developer alpha for owner testing. It builds on Alpha 99 and includes it.

Versions:
- **Mobile:** `5.0.0-alpha.100.dev1` (version code 245).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.100.dev1.exe` (runtime `5.0.0-alpha.100.dev1`).
- **Glass:** `1.0.0-alpha.55` (unchanged).

## What's new

When a code is sent by text to **this phone's own number**, and the run plainly uses that number, Cyclone reads the
code from the text and fills it. It doesn't ask you, and the AI never sees the code. This is plan 49's first build.

**When it fills without asking:**
- an **Account Setup** row's phone number is one of this phone's numbers;
- the run typed this phone's number into the app;
- the code page says where it sent the code ("sent to •••• 5678") and that is this phone's number;
- you asked for it: "sign up with my number", "use this phone", or the number itself.

**When it still asks:**
- **Another number:** the code went to a number that isn't on this phone;
- **No sign:** nothing says the code goes to this phone;
- **Off:** you switched it off in Settings.

**Never automatic:**
- banking, payment and wallet apps (the same list Fast mode keeps out);
- payment confirmation codes;
- Lab runs.

## Filling without glitching

- **The app filled it itself:** many apps read their own code. Cyclone checks that first and again right before it
  types, and then types nothing.
- **The right text:** a text that names the app wins. A code from an unnamed sender counts only if it's the only code
  and nothing better arrives within 15 seconds. Two different codes: Cyclone doesn't guess.
- **Six separate boxes:** filled one character per box when the app doesn't spread the code by itself.
- **No stray taps:** the code is filled into the field directly, so a text-message banner over the screen can't
  steal a tap.
- **A wrong or expired code:** Cyclone taps the app's own "Resend code" once and uses the new code, never the refused
  one.
- **No text after 2 minutes:** one resend, then 2 more minutes, then the Secrets Card with the reason.
- **Account Setup:** a code page is no longer "a person's step" when the code goes to this phone. The Glass row keeps
  saying **Creating**, with "Waiting for the code on this phone".

## Set it up

Settings → Permissions → **Codes from this phone's texts**:
1. **Allow "Read texts".** If Android says "Restricted setting": App info → ⋮ → Allow restricted settings, then try
   again.
2. **Check this phone's number.** Cyclone shows the SIM's number when the carrier sets it. Otherwise type it once and
   tap Save numbers.
3. **The switch** is on by default and turns it all off.

## Privacy

- **Texts:** read from the phone's message store only during a code step, only from that moment, in memory, and never
  saved, logged or sent anywhere.
- **The code:** never reaches the AI, the run record, diagnostics, the PC or Glass. The run record says "code from
  this phone's texts: filled".
- **Permissions:** Cyclone only reads texts; it has no permission to receive or send them.

## Known limits (next build)

Apps that send codes in Google's "SMS Retriever" format (often Instagram and WhatsApp) get their texts held back
from other apps by Android for 3 hours.
- **Usually fine:** those apps normally read their own code and move on, which Cyclone detects.
- **When they don't:** the Secrets Card asks you, as before.
- **Alpha 101:** reads the code from Google Messages in the background, and adds Android's one-tap "allow Cyclone to
  read this message" as the last step before you.

## Tests

- **New:**
  - `CodesTest` (13): reading codes from real message shapes (Instagram, Google, WhatsApp, TikTok, Microsoft, Uber,
    Telegram, Dutch). It also checks that amounts, dates, times, years and phone numbers are never codes, and that
    two codes mean no guess. Plus when to fill without asking, and picking the right text in the window.
  - `CodeFillToolboxTest` (9), inside the real toolbox:
    - fills without the AI seeing the code;
    - asks when the number isn't this phone's;
    - never fills for a bank;
    - types nothing when the app filled the code itself;
    - fills six boxes;
    - a wrong code → one resend → the new code;
    - no text → one resend → you;
    - Account Setup's code page.
  - Guard `test_codes_guard.py`: target Android 16 or lower; texts never logged or stored; no code in anything the AI
    sees; read-only permissions; never in the Lab; never for private apps or payments.
- **Updated:** the permission guard lists the two new permissions with their Settings row. The gateway accepts the
  new "code" Account Setup state and shows it on the row.
- **Results:**
  - full phone suite: 2355 tests, 0 failures;
  - gateway: 797 passed;
  - Ports kit: 21 passed;
  - CI guards: all 281 pass.

## Physical acceptance

UNVERIFIED. On the Pixel:
1. Settings → Permissions → Codes: allow Read texts, and check that this phone's number shows (or type it).
2. In Glass, make an Account Setup row for an app you own that texts a plain code (not Instagram or WhatsApp), with
   this phone's number. Press Create accounts. The code page fills itself, and the row shows "Waiting for the code on
   this phone" and then carries on.
3. Ask the phone: "Sign up for <app> with my number". Same result, without the Secrets Card.
4. Enter a wrong code by hand first: Cyclone taps Resend once and fills the new code.
5. Try a banking app's code: the Secrets Card asks you.
