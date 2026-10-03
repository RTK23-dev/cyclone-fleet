# Cyclone V5 Alpha 45 — Direct first

Developer alpha for owner testing, built on Alpha 44 (Background that stays working), which it includes. Mobile is
`5.0.0-alpha.45.dev1` (version code 189). Glass stays `1.0.0-alpha.26`. The Windows companion is not part of this
release: keep Cyclone PC Companion `1.6.0-alpha.43` installed.

This is plan 29, layer 1. The most reliable way to do a task, on every Android phone, is to need no screen at all. Cyclone
now does calendar, contacts, alarms and timers directly, without opening any app or taking your screen, and tries that
first. The hidden background screen stays as an extra for the phones where it is proven.

## What changed

**Things Cyclone now does without any screen:**
- **Add to your calendar:**
  - For example: "Add dinner with Sam on Friday at 7, remind me 30 minutes before".
  - Cyclone adds it straight to your calendar and reads it back to prove it is there.
  - It uses your main Google calendar unless you name another.
  - It never adds guests, so nobody gets an invitation.
- **Read your calendar:** "What's on my calendar tomorrow?"
- **Look up a contact:** "What's Sam's number?" Cyclone only reads contacts; it never changes them.
- **Alarms and timers:**
  - Set straight away, without the clock app opening over what you are doing.
  - Cyclone confirms an alarm by Android's "next alarm", and a timer by the clock's running-timer notification. When
    it can't confirm one, it says so instead of claiming it.
  - A clock app that doesn't support this still works the old way.
- **Replying to a message from its notification** already worked this way (Alpha 42), with your approval of the exact
  text.

**Direct first:**
- **Tried first:** Cyclone tries these before opening any app.
- **No screen at all:** they work with the phone locked in your pocket, never start a background screen, and don't
  pull a background task onto your screen (timers and alarms used to).

**Asked once, by Android itself:**
- **When:** the first time a task needs your calendar or contacts.
- **How:** Android shows its own permission dialog; Cyclone has no screen of its own for this. If you allow it, the
  task continues. If not, Cyclone says so and does nothing else.
- **Where you control it:** Settings → Permissions now has **Calendar** (read and add) and **Contacts** (look up), and
  you can change them there.

## Validation and limits

Tests that pass:
- Android unit tests (1 862), including:
  - the new rules for times, events, whole days, reminders and likely mistakes;
  - alarm proof;
  - the Mind using direct tools with no screen;
  - asking for access once;
  - a refusal.
- Lint, the release build, and the CI guards (131), including the new direct-first guard:
  - direct actions only through the phone harness;
  - access only through Android's dialog;
  - no secrets in calendar events.

**Physical Pixel 8 acceptance is UNVERIFIED.** The calendar, contacts and clock paths have not run on your phone yet.

Honest limits:
- **Texting someone without a notification to reply to** (a new WhatsApp or SMS) still needs the app's screen. A
  direct SMS needs a special Android exception for sideloaded apps; that is a later step if you want it.
- **Editing or deleting calendar events** is not direct yet; Cyclone opens the calendar app for that.
- **The one-tap background engine** is next (Alpha 46), switched on only on phones whose Background Check passes.

Install the APK as an update; do not uninstall. It is signed with the rotated Cyclone release key.

Suggested checks:
1. Ask "add lunch with Sam tomorrow at 12:30". Allow calendar access once. It should appear in Google Calendar with no
   app opening.
2. Ask "what's on my calendar this week?"
3. Ask "what's Sam's number?". Allow contacts once.
4. Ask "set an alarm for 6:30" while you are in another app. The clock app should not open.
