# Cyclone V5 Alpha 44 — Background that stays working

Developer alpha for owner testing, built on Alpha 43 dev3 (the Glass drawer and the calmer voice button), which it includes. Mobile is
`5.0.0-alpha.44.dev1` (version code 188). Glass stays `1.0.0-alpha.26`. The Windows companion is not part of this
release: keep Cyclone PC Companion `1.6.0-alpha.43` installed.

This is plan 28. The whole background path was read again, from the Shizuku helper to the way Cyclone decides, and
everything that made a background task stop for reasons unrelated to the task is fixed. There is also a check that
says exactly which step fails on your phone.

## What changed

**Background tasks no longer stop by themselves:**
- **Random stops:** when any app asked Android's accessibility speech to stop, Cyclone closed every background screen.
  Now only Cyclone's service really going away does that.
- **After the phone locks:** the background screen stayed paused after you unlocked, so every later step failed. Now
  the task waits while the phone is locked and carries on after you unlock.
- **Send, pay and delete in the background:** after you approved, the approved tap could never run. Now the approval
  card works exactly as on your screen, and the approved action happens on the background screen.
- **Not thrown onto your screen:** any small miss (a page that changed, a tap that missed) used to move the whole task
  to your screen. Now only a step that truly can't be done in the background moves it.
- **Apps with more than one window:** Chrome, Gmail and Docs with a compose window, a second document or a leftover in
  Recents made the background screen fail again and again. Now they work.
- **Dialogs from other apps:** a permission dialog, share sheet or sign-in page on the background screen used to make
  Cyclone blind. Now it sees them (and your approval still applies to every tap there).
- **No pointless waiting:** a paused background screen made Cyclone wait for you to "hand the phone back". Now it takes
  its own screen back.
- **A second route for taps:** on a phone that does not deliver Cyclone's accessibility gestures to a background
  screen, the tap or swipe now goes through the Shizuku helper instead. It is never sent twice.

**The Background check:** *Settings → AI → Background work → Check.*
- **What it does:** it opens a harmless app (Settings, Clock or Calculator) on a hidden screen. Then it checks that
  Cyclone can see it, read it and scroll it once, that your screen stays yours, and that it closes cleanly. Each step
  shows ✓ or ✗; a failed step shows what Android answered and one thing to do. It takes about 10 seconds, and nothing
  on your screen changes.
- **It runs once by itself** after this update, when background work is set up.
- **When a step fails in the background engine,** Automatic works on your screen and says why, instead of failing in
  the middle of a task. Check again after a fix.

## Validation and limits

Tests that pass:
- Android unit tests (1 847), including the new background tests:
  - apps with several tasks, Recents leftovers and the owner opening the app;
  - the failure classifier;
  - the check report.
- The background stability guard (7 rules), the Task Kit and product guards, and version metadata.

**Physical Pixel 8 acceptance is UNVERIFIED.** The fixes come from reading the code, and the check is the way to prove
them on your phone.

Honest limits:
- **The phone must stay unlocked.** Background work pauses while the phone is locked and continues when you unlock.
- **After a restart, Shizuku must be started again:** one tap in Shizuku, unless *Keep background work on* was set from
  Glass on your PC.
- **The check proves the engine on your phone, not every app.** Cyclone still learns per app.

Install the APK as an update; do not uninstall. It is signed with the rotated Cyclone release key (the alpha.34 /
39 / 40 / 41 / 42 / 43 signer).

Suggested checks:
1. *Settings → AI → Background work → Check.* All steps should show ✓. If one shows ✗, send me the line.
2. With WhatsApp open in Recents, ask Cyclone "send 'test' to <a contact> in WhatsApp in the background". The task
   should work behind your screen, ask you to approve Send, and send after you approve.
3. During a background task, lock the phone for a minute and unlock it: the task should carry on.
4. During a background task, open WhatsApp yourself: Cyclone should pause and continue when you leave it.
