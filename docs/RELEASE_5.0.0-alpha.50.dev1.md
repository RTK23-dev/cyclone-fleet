# Cyclone V5 Alpha 50 — Drive: conversations

Developer alpha for owner testing, built on Alpha 49 (Drive: talk), which it includes.
- **Mobile:** `5.0.0-alpha.50.dev1` (version code 194).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.50.dev1.exe`. It has no changes of its own since alpha.48.
- **Glass:** `1.0.0-alpha.27` (unchanged).

This is the second Drive release (plan 32, D2): the back and forth with Cyclone while you drive.

## What changed

**Cyclone asks, you answer out loud.** When a task needs you, the orb glows warm, a sound plays, AI mode rises and
Cyclone says what it needs. What you answer depends on what it asks:

| Cyclone asks | It says | You answer |
|---|---|---|
| A question | The question, with options as "A, or B?" | Say your answer. |
| Details for a form | One field at a time: "What's the date?", then "What's the time? Morning, or Evening?" | Say each one. What you say is used for this task only and never remembered. |
| To send a message | The message **word for word**: "I'll send Louella: "Hey, that's alright, see you tonight.". Send it?" | "Yes" sends it. "No" doesn't. "Change the end to …" makes Cyclone rewrite it and read the new text back. |
| Anything else | "That needs you on screen when you're stopped." | Nothing by voice; the card waits on screen. |

**Not now** on AI mode (or "never mind") declines a question and closes. Stop still ends everything.

**The Louella flow.** Say "reply to Louella":
1. Cyclone finds her message: first in your notifications, otherwise in the chat.
2. It asks: "Louella wrote "I will be home late". How should I answer?"
3. You say what to answer, and Cyclone writes the reply in your own style: your language, tone and nicknames from
   that conversation. It uses them for this reply only and never saves them.
4. Cyclone reads the reply back word for word. It sends only after your yes.

**Replies from the notification now work.** Before this release, a reply Cyclone wrote from a notification (without
opening the app) could not be approved and was never sent. It now waits for your approval on the task card, the
notification, or by voice in Drive.

## Safety

- **A spoken yes approves only one thing:** sending the message that was just read back. "Yes", "send it" or "no" are recognised on the phone itself, word for word; anything else (like "change the end to …") goes to the model and is never taken as a yes.
  - The readback is the approval's own text: the text in the notification reply, or what is in the chat's message box
    at that moment.
  - The model never rewords it. Tests check that the text read back is the text sent.
  - If the message box changes after your yes, nothing is sent and Cyclone asks again.
- **These are read back only when every word can be said as written:**
  - drafts Cyclone typed itself;
  - drafts of 40 words or fewer;
  - drafts with nothing the redaction would hide.
  Anything else, and a box holding text Cyclone did not write, waits for you on screen.
- **Paying, deleting, permissions, signing in, passwords and handing over the phone** are never approved by voice.
- **Secrets are never heard or said.** Details cards that ask for a password, PIN, code or card number wait on screen.
- Everything from alpha.49 holds:
  - the microphone opens only when you tap;
  - nothing is recorded to disk;
  - voice reaches Cyclone only through a new request or Task Kit.

## Validation and limits

Tests that pass:
- **Voice core** (JVM):
  - every conversation path: readback then yes, no or change; details one field at a time; a second unclear answer
    closing politely; approvals, secrets and handovers never answered by voice;
  - a yes approves only the read-back moment.
- **Readback equals sent:**
  - the notification reply sends exactly the approved text;
  - the chat send carries the box's exact text and sends nothing when the box changed;
  - text Cyclone did not type is never read back.
- **CI guards:** voice boundaries (now including readback equals sent), Task Kit, permissions, versions.
- **Mobile CI:** the Android build and all unit tests.

**Physical status: UNVERIFIED.** The Louella flow has not run on your Pixel 8 or in a car yet, on either the
notification route or the chat route.

Honest limits:
- **Recipient:** a chat send reads back as "I'll send: "…"", without who it goes to; Cyclone names the recipient only
  for notification replies.
- **Long messages:** drafts longer than 40 words are checked on screen, not read aloud while driving.
- **Next, in D3:** reading new messages aloud as they arrive, the car's Bluetooth microphone, and interrupting by
  talking over Cyclone.

Install the APK as an update; do not uninstall it. It is signed with the rotated Cyclone release key.

Suggested checks:
1. Driver mode on. Ask someone to message you, then tap the orb and say "reply to <their name>".
2. Answer the question out loud. Listen to the readback, then say "change the end to see you soon". Listen to the new
   readback and say "yes".
3. Check in the chat app that the sent text is exactly what you heard.
