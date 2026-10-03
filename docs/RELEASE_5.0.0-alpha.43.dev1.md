# Cyclone V5 Alpha 43 — Glass overlay

Developer alpha for owner testing, built on Alpha 42 (Background always), which it includes. Mobile is
`5.0.0-alpha.43.dev1` (version code 185), the Windows companion is `1.6.0-alpha.43`, and the bundled Glass web app
is `1.0.0-alpha.26` (both unchanged apart from their version). This is plan 27: the overlay redesigned exactly as agreed
in the Overlay Studio rounds, and a background switch that works and says why when it cannot.

## What changed

**One glass, lit by how you hold the phone.** The working card, the Ask bar, the island and the small pills are one
dark teal glass:
- **Shine:** a sharp line on the edges that face the light, and a weaker one on the opposite edge, as real glass does.
- **Follows your hand:** tilt the phone and the shine slides around the rim, a full 360°, read from the phone's motion
  sensor. With animations switched off in Android's settings the light stays still.
- **Fingerprint dots:** fine halftone dots hug the edges of the card and the bar: crisp and bright where the light
  falls, soft and faint on the far side.
- **Soft text pills:** words sit on a soft pill with no edge, so they stay sharp.

**The stack:**
- **The plane pill:** a small pill with one word (*Screen*, *Background*, *Moving* or *Waiting*) sits above the working
  card. It stays there as its own capsule when you fold the card into the island. Tap it to move the task; press and
  hold it to see why.
- **Natural gaps:** 10 dp between the pill and the card, 14 dp between the card and the Ask bar.
- **The card:**
  - The logos of the apps the task works in lead the card, the current one in front with a teal ring. There is no
    "Cyclone Mind" title any more.
  - Then *Working*, a collapse button, the title, a thin progress line, the last few steps, and a quiet line such as
    "Step 3 of 8 · Calendar → WhatsApp".
- **The Ask bar:**
  - "Ask Cyclone" fills the space between **+** and the voice button.
  - The voice button looks like the other round buttons until you talk. Then it becomes a glowing green orb whose light
    rises into the bar, and it goes back when you stop.
- **Buttons:** every round button has a thin lit edge, and every button lights up when pressed.

**Four heights, one gesture:**
- **Card:** drag the stack down once and the card folds into the **island**. The island is the Ask bar's size, with
  the current app's logo inside a turning ring, what Cyclone does now centred on a soft pill, and the progress along
  the bottom. Tap it to open the card again.
- **Island:** drag down again and only the **live notification** remains. Its **Show** button brings the island back.
- **Idle:** a small, slightly oval, see-through bubble with Cyclone's mark.
- **Needs you:** when a task needs you, the card rises with the plane pill above it. An approval puts what will be sent
  first: "Send to **Sam** in WhatsApp", then the message itself, large and bright, then **Send**, **Change** and
  **Not now**, lit like the rest.

**The background switch works, and is never silent:**
- **The reason shows:** when a move does not happen, the reason appears beside the pill for four seconds, for example
  "Couldn't move WhatsApp: it stayed on your screen". Before, it only showed if you long-pressed the pill.
- **You land at home:** when your tap moves the task behind the screen, you land on your home screen and the stack
  folds into the island. Moving it back opens the card again.
- **Your tap is not dropped:** if Cyclone is in the middle of an action, your tap now waits up to 20 seconds for the
  step to finish (the pill says *Moving*) instead of giving up after 2.5 seconds.

**The notification:**
- **Show** comes first: *Show · Work in background · Stop task*.
- On Android 16 the Live Update shows the task's real steps as segments.

## Validation and limits

Tests that pass:
- **Android unit tests (1 838):**
  - the glass optics: shine on the facing and opposite edges, a narrower shine on panels than on pills, crisp and soft
    dots, the tilt baseline and limits;
  - the overlay words: approval split, one-word pill, steps, meta line, island lines, outcome line;
  - the app trail;
  - folding to notification only while a task runs, and to the launcher after it ends;
  - the stack order guard (pill, 10 dp, card, 14 dp, bar).
- **Other checks:** lint, the release build, the CI guards (Task Kit, product, versions), gateway, MCP, Glass (142) and
  companion (238) suites.

**Physical Pixel 8 acceptance is UNVERIFIED.** The design was agreed on the web prototype. On the phone it has not been
seen yet: the shine, the dots, the gestures and the switch all need your eyes.

Honest limits:
- **No fly-into-pill animation yet.** The design's "app flies into the pill" is not built. After a background move the
  phone goes home and the stack folds into the island.
- **Island button:** it keeps the Ask bar's pause control (tap pauses; hold two seconds or tap twice to stop), not a
  one-tap stop square, so a stray tap cannot end a task.
- **Notification buttons:** Android draws them itself, so they cannot take the overlay's pill shape.
- **No PC switch log:** the planned PC view of the switch log was left out; the reason now shows on the phone beside
  the pill.
- **Overlay only:** Ask inside the Cyclone app keeps its current card; this alpha redesigns the overlay.
- **Approval quotes:** the approval shows the quoted message large when the request quotes it; otherwise the whole
  request is the message.

Install the APK as an update; do not uninstall. It is signed with the rotated Cyclone release key (the alpha.34 /
39 / 40 / 41 / 42 signer).

Suggested checks:
1. Ask for something in WhatsApp. Tilt the phone slowly left, right, forward and back: the shine should follow.
2. Drag the card down once (island), then again (only the notification). Tap **Show** in the notification to bring the
   island back, and tap the island to open the card.
3. Tap **Screen** on the pill. You should land on your home screen with the island working, or see the reason beside
   the pill.
4. Tap the microphone: it should turn into the glowing orb, and back when you stop.
