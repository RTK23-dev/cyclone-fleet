# Cyclone V5 Alpha 42 — Background always

Developer alpha for owner testing, built on Alpha 41 (Hands), which it includes. Mobile is `5.0.0-alpha.42.dev1`
(version code 184), the Windows companion is `1.6.0-alpha.42`, and the bundled Glass web app is
`1.0.0-alpha.25`. This is plan 26: background work that is easy to switch on, stays on, and knows what to do when
you are using the same app.

## What changed

**One switch that stays on.** Settings → AI → **Background work** is one switch with one sentence that says where it
stands, plus the one button that fixes it:
- *On*;
- *Paused* (the helper stopped, usually after a restart): **Resume**;
- *Needs a step*: **Set up**;
- *Off*;
- *Not on this phone* (Android 14 or older).

The same switch is a Quick Settings tile, **Cyclone background**. After a restart or an update, if background work
is paused, you get one quiet notification with the tap that fixes it: never in the middle of a task, and at most
twice a day. From your PC, Glass → Phone → **Keep background work on** grants the helper the one permission it needs
to restart itself after every restart; then turn on *Start on boot* in Shizuku once.

**Apps in Recents just work.** Before, Cyclone refused to open an app in the background when it was in your recent
apps, which WhatsApp, Gmail and Chrome almost always are. Now Cyclone moves that app's task behind your screen, on the
page it was left on, with its state.

**The background works like your screen:**
- **Typing:** the alpha.41 typing (put the text in, read it back, paste if needed) works in the background too, in any
  ordinary field or the one that has focus.
- **Swipes:** sideways swipes work in the background (carousels, tabs). A swipe that could delete or pay asks you
  first, just like a tap would.
- **Links:** links open on the background screen in the app that handles them, instead of pulling the task to your
  screen.
- **Screen size:** the background screen has your phone's own shape and density, so apps look and are mapped the same
  as on your screen.
- **Still pages:** a page that does not change no longer counts as a frozen screen.

**Your preference.** Settings → AI → **Where Cyclone works**: *Automatic*, *In the background* or *On screen*. New:
**When background isn't possible**: *Use my screen*, *Wait until I'm done with the app*, or *Ask me each time*. Per
app you can choose *Always on my screen*, *Always in the background* or *Cyclone decides*. Banking, camera and game
apps start as *Always on my screen*.

**When you are using the app Cyclone needs:**
- **A second window:** apps that support one (Chrome, Docs) get a second window behind your screen and you keep yours.
  Cyclone learns per app whether this works. If Android moves your own window instead, it is put straight back.
- **Otherwise, your setting decides:** Cyclone works on your screen, waits until you leave the app (the pill and the
  notification show **Start now**), or asks you: *When I'm done / Now on my screen / Take it to the background*.
- **You open an app Cyclone is using in the background:** you win. Cyclone pauses at its next step, the pill says
  "Paused: you have WhatsApp", and when you leave the app Cyclone takes it back and continues.

**Replies without any screen.** Cyclone can answer a message from its notification's reply action, so nothing opens
and you keep using your phone. It is a send, so you approve the exact text every time.

**See what you approve.** An approval for a task in the background shows a live glimpse of the background screen,
with **Show on my screen**.

**A second task waits its turn.** Asking for a clearly separate task while one runs ("after this…", "in the
background…", "daarna…") queues it as *Runs next*. It starts when the current one ends. Corrections still steer the
running task.

**Lab planes suite.** A new variant knob `plane` (automatic / screen / background), a `launch` setup step, and the
*planes* suite:
- a timer and a Keep note made behind your screen;
- starting from Recents;
- a link in the background.

Each checks that your own screen stayed yours.

## Validation and limits

Tests that pass:
- **Android unit tests:** the capability model; the start decision table; per-app seeds; fallbacks and answers; the
  pill states, including waiting; Start now through Task Kit and the notification; typing on a background display
  through the engine; the background screen shape; the queue and its classifier; notification replies with approval;
  the lab plane knob.
- **Other suites:** gateway (keep-on step, planes suite, plane knob), Glass (Keep background work on), companion and
  MCP, and the CI guards (Task Kit rules include Start now).

**Physical Pixel 8 acceptance is UNVERIFIED.** Plan 26's bars:
- 5 restarts with *Keep on* back to ready within 60 s;
- Automatic starts WhatsApp, Gmail and Chrome from Recents 20/20;
- Hands in the background ≥ 90%;
- same-app waiting starts within 3 s of leaving, 10/10;
- switch success ≥ 99% over 50.

Honest limits:
- The rule that let a still page count as healthy was changed without a device measurement first (plan 26's Phase 0).
  Every action still checks the page's accessibility fingerprint first.
- Running two tasks at the same time is alpha.43; this alpha queues the second one.
- Second windows are tried only for apps that are known or were learned to support them.
- Play Store links still open on your screen.

Install the APK as an update; do not uninstall. It is signed with the rotated Cyclone release key (the alpha.34 /
39 / 40 / 41 signer).

Suggested checks:
1. Settings → AI → Background work: turn it on; add the Quick Settings tile. Restart the phone and see the one "tap to
   resume" notification, or none after Glass → Phone → *Keep background work on*.
2. Open WhatsApp, go Home, open Chrome, then ask: "check my last WhatsApp message". WhatsApp should run behind Chrome
   on the chat you left.
3. With WhatsApp open, ask for something in WhatsApp with *Ask me each time* set: answer *When I'm done*, leave
   WhatsApp and watch it start.
