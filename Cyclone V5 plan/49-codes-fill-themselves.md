# Plan 49: codes that fill themselves, and Ports run 5 and 6

Date: 2026-10-02. Status: **§6.1 built, released in alpha.100**; §6.2–6.4 planned. Follows plan 48 (runs 1–4
released, alpha.96–99).

**As built in alpha.100** (differences from the text below):
- **Step 1 reads the SMS database** once a second during the code step. There is no `SMS_RECEIVED` receiver
  (`RECEIVE_SMS` stays forbidden by the permission guard).
- **No Shizuku grant.** The Settings row explains Android's "Allow restricted settings" instead.
- **An unnamed code** (no app name in the text) waits 15 s for a named one before it counts.
- **The pieces:** pure code in `codes/` (`CodeExtractor`, `AutoCodePolicy`, `CodeCatcher`); Android in `codes/`
  (`AndroidCodes`, `AndroidMindCodes`); the step itself in `PhoneMindToolbox` (`autoCode`, `fillCode`, `setupCode`).
- **Account Setup:** a new progress state `code` that the gateway accepts and shows as "Creating · Waiting for the
  code on this phone".

## 0. The ask

> I want auto SMS fill-in when the phone number is on the phone, when this is obviously what the user wants. Things
> like an Account Setup where the user specified they want a certain number to be used should auto-fill without
> asking or glitching out.

**Today:**
- Account Setup treats `sms_code` as "a person's step" (`SignupCheck.SMS_CODE`). The run stops and asks the owner.
- In a normal mission, `vault_fill what=one_time_code` opens the Secrets Card unless a Ports plugin delivered the code.
- The phone does not read its own texts at all: no SMS permission, and only a notification listener for other uses.

**Goal:** when the code goes to a number that is on this phone, and the run is plainly one where the owner wants
that, Cyclone reads the code from the text itself and fills it, then carries on:
- no question;
- no model ever seeing the code;
- no double typing, and no tapping the wrong thing.

## 1. What Android allows (checked 2026-10-02)

- **SMS permission (`RECEIVE_SMS` / `READ_SMS`):**
  - **Plain OTP texts** ("Your code is 123456") still reach an app with this permission at once, as long as the app
    targets Android 16 (API 36) or lower. Cyclone targets 35.
  - **Apps targeting Android 17 (API 37)** get those texts only **three hours** later. The `SMS_RECEIVED` broadcast is
    withheld and SMS database queries are filtered for that time.
  - **Texts in SMS Retriever or WebOTP format** (with an app hash, used by many big apps) are held back three hours
    for any app that isn't their intended recipient, whatever it targets.
- **Notification listeners (Android 15+):** OTP content in notifications is redacted for untrusted listeners. That
  path is out.
- **Accessibility:** the Messages app's own screen is not redacted for an accessibility service.
- **SMS User Consent API:** works for every format, but shows a one-tap system prompt per message. It is the last
  resort before the owner.

**Consequences:**
1. Pin `targetSdk` at 36 or lower. A guard fails CI if anyone raises it to 37 without this plan's §6.3 rework.
2. Expect the app's own autofill. A Retriever-format app (Instagram, WhatsApp) often reads its own code and advances
   by itself. Cyclone must notice that and **not type**.
3. A ladder of sources is needed, not one source (§3).

## 2. When is it "obviously what the owner wants"? (`AutoCodePolicy`, code, never a model)

The policy is pure. Its result is **AUTO** (fill, no question), **ASK** (today's Secrets Card) or **NEVER** (the owner
types it).

| Situation | Result |
|---|---|
| **Account Setup** run (the owner pressed Create accounts in Glass), and the row's phone number is one of **this phone's numbers** | **AUTO** |
| Account Setup, and the row's phone is a registered **source** on another phone (Ports `code.in`, §4) | **AUTO** through Ports |
| A mission that typed this phone's number into the app's phone field in this run, and the code page follows | **AUTO** |
| The code page says where it sent the code ("sent to •••• 4821"), and it matches this phone's number | **AUTO** |
| The owner's goal names this phone's number, or "my number" / "this phone", for a sign-up or verification | **AUTO** |
| The number is not this phone's and no source matches | **ASK** |
| An app kept private (banking, payments, `Pilot.keepOff`), or a payment or transfer confirmation code | **NEVER**: the owner types it |
| A Lab mission | **NEVER** |

**This phone's numbers:**
- read per SIM with `READ_PHONE_NUMBERS` (`SubscriptionManager.getPhoneNumber`);
- confirmed or typed by the owner once in Settings → Codes, because carriers often leave the number blank;
- kept on the phone only.

**Approval boundaries stay as they are:**
- filling a code is not a submit;
- Account Setup's final "create" was already approved in Glass, and other missions keep their approvals;
- the policy never lifts an approval.

## 3. The code ladder on the phone (`CodeCatcher`)

A code step **arms** at the moment the code is requested: when the run taps "Send code", or when the code page first
shows. Any code that arrived up to 60 s before that counts too. The catcher then tries these in order:

| Step | Source | Notes |
|---|---|---|
| 0 | **The app filled it itself** | Re-observe first. The field holds the right number of characters, or the page moved on: done, type nothing. |
| 1 | **The SMS broadcast** (`SMS_RECEIVED`), plus an SMS database query for the armed window | Instant for plain texts. Bodies are read in memory only, and only the code is kept. |
| 2 | **The Messages app, read in the background** | Open Google Messages on Cyclone's background plane (plan 25) and read the newest message from the sender with accessibility. Deterministic, no model, nothing leaves the phone. Covers Retriever-format texts. |
| 3 | **SMS User Consent** | One system tap, still no typing. Only when steps 1–2 failed and the policy allows it. |
| 4 | **The owner** | Today's Secrets Card or check-in, with the reason ("no text arrived in 2 minutes"). |

**Matching:**
- **When:** received inside the armed window, on the SIM whose number is in use.
- **Who:** the sender or the text names the app (its label, or known sender ids learned per app).
- **What:** the code pattern for that app (6 digits, `G-123456`, 4–8 alphanumeric next to "code", "verification",
  "verificatiecode"…).
- **More than one candidate:** take the newest. If two different codes match equally, don't guess; go to the next
  step.

**Privacy:**
- bodies are never stored, logged, sent to the PC or shown to a model;
- the extracted code lives in memory, single-use, for at most 5 minutes, in the same `code` slot as a sealed Ports
  code;
- the run record says only "a code from a text on this phone, filled".

## 4. Filling without glitching (`CodeFill`)

The Mind does not get a new tool. `vault_fill what=one_time_code` fills from these sources, in order:
1. a sealed Ports code;
2. the `CodeCatcher`, when the policy says AUTO;
3. the Secrets Card.

Account Setup runs the same step from code, so the model can't skip it.

**Robust filling:**
- **One field:** set the text by element (accessibility), never by coordinates. A heads-up SMS banner over the field
  then can't steal the tap.
- **Split boxes** (6 single-character fields in a row): type into the first box and verify. If the app didn't spread
  the code, fill box by box.
- **Already filled or advanced:** step 0 above, checked again right before typing. This covers the app's own autofill
  racing Cyclone.
- **Keyboard chips** ("From Messages: 123456"): never tapped.
- **After filling:** the Fast Path settle, then verify:
  - the page advanced, or the app's "Continue" became enabled. When the map knows Continue, Account Setup presses it.
  - An error ("wrong code", "expired"): tap the app's "Resend code" once (known from the Sign-up Map or found on
    screen), re-arm and wait again. A second failure goes to the owner.
- **Timeout:** 120 s without a code → resend once → 120 s → the owner. The Glass row shows "Waiting for the code on
  this phone (0:42)".
- **One at a time:** one code step per run. A code is used once.

## 5. Ports run 5 and 6 (with the gaps from the alpha.99 review)

| # | Gap or item | Change |
|---|---|---|
| 5.1 | Routine-specific Port map choices never reach phone runs | Pass `routine` and `taskId` from Command Center tasks and routines into `PortOutboxLink` |
| 5.2 | Codes from the owner's other phones and inboxes | **Sources:** register an SMS forwarder or an inbox (add, confirm with a test message, remove). `code.in` is accepted only from a confirmed source. An Account Setup row picks "this phone" or a source. |
| 5.3 | Account Setup's verification steps bound to codes | `sms_code` / `email_code` pages run the code step (§4) with this phone or the row's source. They stop being a person's step under AUTO. |
| 5.4 | Keys switch hard | Rotation: the plugin accepts `…_NEXT`, switch over once health passes, then retire the old key |
| 5.5 | No auto-pause, no limits | Per-plugin rate limit and concurrency; pause after repeated failures, with the reason in Glass |
| 5.6 | Changed plugins show no diff | Manifest diff in the review sheet |
| 5.7 | The phone can't send `file.out` | A phone file-out path (Downloads or a picked file, image/video/audio/PDF) |
| 5.8 | No lane on the Run page | Port lane on Glass's Run page, from the same activity data |
| 6 | Polish | Accessibility, empty and error states, dark mode, docs, physical acceptance checklist |

## 6. Builds (each one released and tested on its own)

### 6.1 alpha.100: codes from this phone's texts
1. **Permissions and numbers:**
   - add `RECEIVE_SMS`, `READ_SMS`, `READ_PHONE_NUMBERS` to the manifest;
   - Settings → **Codes**: "Fill codes from this phone's texts" and this phone's numbers per SIM (confirm or type);
   - grant through the normal prompt, or through Shizuku when Android shows "restricted setting" for a sideloaded
     app;
   - a `targetSdk ≤ 36` guard.
2. **`CodeExtractor` (pure):** the patterns from §3, and a test corpus of real message shapes (Instagram, Google,
   WhatsApp, TikTok, Microsoft, Dutch and English, "do not share", amounts) that must not mistake an amount or a
   date for a code.
3. **`CodeCatcher`:**
   - a receiver registered only while a step is armed, plus the SMS database query for the window;
   - an in-memory buffer (codes only, 5 minutes);
   - steps 0, 1 and 4 of the ladder.
4. **`AutoCodePolicy` (pure)**, with tests for every row of §2.
5. **`vault_fill one_time_code`** takes from the catcher under AUTO. `CodeFill` covers one field, split boxes,
   already-filled detection, the error check, one resend and the timeout.
6. **Account Setup:** `sms_code` under AUTO runs the code step from code. The progress shows the wait in the Glass
   row.
7. **Tests:**
   - `CodeExtractorTest`, `AutoCodePolicyTest`, `CodeCatcherTest` (fake clock and messages), `CodeFillTest` (fake
     screens: one field, 6 boxes, the app's own fill, a wrong code, resend);
   - the Account Setup flow end to end with a fake SMS;
   - guards: SMS bodies never stored or logged (no writes in the `codes` package outside memory); no code in run
     records, diagnostics or briefs.

### 6.2 alpha.101: the background Messages reader, and sources
1. Step 2 of the ladder: read Google Messages on the background plane, falling back to the main screen only when
   the background plane isn't available (then the run comes straight back to the app). Step 3: SMS User Consent.
2. Ports 5.1 (routine and task pass-through), 5.2 (sources), 5.3 (Account Setup's code steps bound to this phone or
   a source).
3. Glass:
   - Ports → **Sources**: add, test message, remove;
   - an Account Setup table's phone column picks "this phone's number" or a source.

### 6.3 alpha.102: Ports resilience
- Ports 5.4–5.8: key rotation, limits and auto-pause, the manifest diff, `file.out` from the phone, the Run page lane.
- Before any future `targetSdk` 37 bump: switch step 1 to rely on steps 0, 2 and 3 only, and move the guard.

### 6.4 alpha.103: Ports run 6 and acceptance
- Polish and docs.
- A physical checklist on the Pixel:
  - Account Setup with this phone's number: a plain-text app and a Retriever-format app;
  - a code from another phone through a source;
  - the app's own autofill racing Cyclone;
  - split boxes;
  - a wrong code and resend.

Later (not scheduled): secret ports (`secret.out` / `secret.in`), recipes, Skill Studio (plan 46), whose S3 SMS-code
resolver becomes `CodeCatcher` + `CodeFill`.

## 7. Invariants

- **The code:** never in the model's context, logs, the database, Glass, diagnostics, run records or Brain. Kept in
  memory, used once, for at most 5 minutes.
- **SMS bodies** are never stored or sent anywhere.
- **The policy is code.** A model can't turn AUTO on, and never for kept-private apps or payment codes.
- **Owned or authorised accounts only.** Approval boundaries unchanged. `PhoneToolExecutor` does every fill.
- **Physical acceptance** stays UNVERIFIED until it is done on the phone.
