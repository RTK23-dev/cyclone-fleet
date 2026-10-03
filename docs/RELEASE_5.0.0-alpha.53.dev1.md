# Cyclone V5 Alpha 53 — Drive: in the car

Developer alpha for owner testing. It builds on Alpha 52 (Drive: faster) and includes it.
- **Mobile:** `5.0.0-alpha.53.dev1` (version code 197).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.53.dev1.exe`. No Drive changes; the same as alpha.52.
- **Glass:** `1.0.0-alpha.28` (unchanged).

This release is Drive's D3, "in the car": Cyclone tells you about new messages, and it listens through the car's own
microphone. What Drive may do by voice has not changed.

## What changed

**Message announcements** (Settings → Driver mode → In the car → Announce messages). Off until you turn it on. You
pick:
- the chat apps: WhatsApp, WhatsApp Business, Signal, Telegram, Messages, Messenger or Instagram, whichever are
  installed;
- optionally, only certain people, by name.

When a message arrives while Driver mode is on and Cyclone is free:
- **A short message** is read out: "Louella wrote: "I will be home late." Reply?"
- **Longer than 25 words:** "Louella sent a long message. Reply?" It is never read in full.
- **A group:** "New message in Family on WhatsApp." It is named only, with no reply offer, because a reply there
  reaches everyone.
- **Codes, PINs, passwords,** or anything Cyclone's redaction would hide: not announced at all.

**Answering.** The orb glows for a minute. Tap it and say:
- **"yes"** or **"reply":** Cyclone opens the reply and asks what to say. This needs no model call.
- **"tell her I'm on my way":** the reply starts with your answer.
- **"no":** nothing happens.
- **Anything else** ("navigate home") is simply a new request.

Every reply follows the Alpha 50 rules: drafted in your style, read back word for word, and sent only on your "yes".
It is sent from the notification when the app allows that.

**The car's microphone** (Settings → Driver mode → In the car → Car microphone, on by default).
- **With a car kit or headset** connected over Bluetooth, Cyclone listens through it; that microphone is closer to
  you than a mounted phone's.
- **Timing:** the link comes up while the listen sound plays.
- **Fallback:** if the link isn't up within 1.5 seconds, Cyclone uses the phone's microphone.
- **Speech:** Cyclone's voice keeps playing through the car's media audio.
- **Afterwards:** the car's audio is handed back as soon as Cyclone stops listening, so music and navigation return.

**A safer interruption.** Tapping the orb while Cyclone talks already stopped it and listened (since D1). Now, if you
cut off a message readback, Cyclone reads the message again in full before your "yes" counts. It is never sent on a
readback you didn't hear to the end. A CI guard checks this.

## Safety

- **The microphone still opens only when you tap.** An announcement never opens it; the orb only glows. A CI guard
  checks this.
- **Messages are never stored or logged by Drive.** An announced message lives in memory until it is said.
- **The understanding model never sees the message.** It hears only who wrote and in which app. The Mind reads the
  message on the phone when it replies, as before.
- **Sender names are cleaned** before they are said or put into a request (letters, digits and simple punctuation,
  six words at most), so a name cannot carry instructions.
- **Unchanged:**
  - only a send can be approved by voice, after the full readback and an explicit yes;
  - paying, deleting, permissions, sign-in, passwords and handovers wait for you on screen;
  - no audio is written to disk.

## Validation and limits

Tests that pass:
- **Voice core** (JVM, 102 tests):
  - the announcement lines (short, long, group, no reply field);
  - codes and passwords never announced;
  - sender names cleaned; allowed apps, senders and Driver mode;
  - the turn engine: an announcement said without opening the mic; "yes" with no model call; "tell her …" in the
    goal; "no" and a new request; dropped while busy; expiry after a minute;
  - a cut-off readback read again before a yes counts;
  - everything from D1, D2 and alpha.52.
- **CI guards:** voice boundaries, now also covering the readback rule and announcements (no mic, the model never sees
  the message, codes skipped, nothing logged); plus Task Kit, permissions and versions.
- **Mobile CI:** the Android build and all unit tests.

**Physical status: UNVERIFIED.** None of this has run on your Pixel 8 or in a car yet.

Honest limits:
- **Busy means silent.** An announcement is dropped while Cyclone is busy (listening, talking, working or waiting for
  you); it stays in your notifications.
- **Car kits differ.** Some pause music or switch to "call" audio while the car microphone is on, and some take longer
  than 1.5 seconds to connect (Cyclone then uses the phone). If your first words get cut off, turn Car microphone off
  and tell me.
- **Announcement support varies by app.** It depends on how each chat app builds its notifications. WhatsApp, Signal
  and Messages carry the sender and text; a notification without a sender is not announced.
- **Not in this release:**
  - interrupting by talking over Cyclone: echo from car speakers must be measured first;
  - the Lab voice suite;
  - JEV taking over simple decisions: it waits for your car-test numbers under Settings → Voice → Instant decisions.

Install the APK as an update; do not uninstall it. It is signed with the rotated Cyclone release key.

Suggested car test (about 15 minutes, with alpha.52's list):
1. Turn on **Announce messages** for WhatsApp, and ask someone to message you while you drive.
2. When Cyclone reads it, tap and say "tell her I'm on my way". Check the readback, then say yes.
3. Check whether the car's microphone was used and whether your music paused.
4. Afterwards, note JEV's agreement and time under Instant decisions.
