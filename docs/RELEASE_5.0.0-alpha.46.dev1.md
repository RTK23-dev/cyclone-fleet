# Cyclone V5 Alpha 46 — Setup cards

Developer alpha for owner testing, built on Alpha 45 (Direct first), which it includes. Mobile is `5.0.0-alpha.46.dev1`
(version code 190). Glass stays `1.0.0-alpha.26`. The Windows companion is not part of this release: keep Cyclone PC
Companion `1.6.0-alpha.43` installed.

This is plan 30. Setting Cyclone up is now a short guided path: one card per important setting, in plain words, with
one button that takes you to the right place.

## What changed

**The setup cards:**
- **One card per setting:** use your phone, show over other apps, tell you when it's done, answer your messages, keep
  tasks running, work in the background (Shizuku, Android 15+), calendar, contacts and voice.
- **Each card:** a title, one or two short lines on why, and a **Set up** button that opens the right Android screen or
  Android's own permission dialog. **Skip** is bottom right; **X** (top right) closes the whole setup.
- **Already on?** Skipped automatically. When you come back from Android's settings, the card checks again and moves
  on by itself once the setting is on.
- **At the end:** "You're set", with whatever is still off.
- **When it opens:** once on the first start, and again only when an update adds a card you haven't seen. Skipping or
  closing never brings it back by itself.

**Seeing a card again:**
- Settings → Phone → **Set up Cyclone** runs the setup again (it shows how many settings are on, like 6/9).
- An **ⓘ** next to Phone control, Notifications and Background work shows that one card.

**Background work, made clearer:** the Background card explains the hidden screen and Shizuku in one line, and **Set
up** opens the existing step-by-step Shizuku guide.

**Safer by design:** the cards only explain and open Android's own screens. Cyclone never turns a setting on for you;
you flip every switch, and Android shows its own permission dialogs. A new CI guard checks this.

**Design:** the cards use Tilt Glass (`docs/design/CYCLONE_TILT_GLASS.md`): the panel glass with the light following
your tilt, words on a soft veil, lit edges only on buttons.

## Validation and limits

Tests that pass:
- Android unit tests, including the new setup-flow tests: already-on settings skipped, Skip and X, opening only for
  unseen cards, a card added by an update, Background only on Android 15+, and short jargon-free copy.
- The CI guards (135), including the new setup-cards guard: no phone actions, every card opens Android's own screen or
  dialog, Tilt Glass parts used, the ⓘ buttons wired.

**Physical Pixel 8 acceptance is UNVERIFIED.** The cards have not been seen on your phone yet, and there is no
screenshot test; how they look on the phone is still to be checked.

Honest limits:
- **A permission you said no to twice:** Android stops showing its dialog, so **Set up** opens Cyclone's app settings
  page instead, where you turn it on.
- **Background ready** means Shizuku is running and allowed. Whether the hidden screen really works on your phone is
  still the Background Check (Settings → Background work → Check).
- **Quick setup (with root)** stays as it was, for rooted phones.

Install the APK as an update; do not uninstall. It is signed with the rotated Cyclone release key.

Suggested checks:
1. After the update, open Cyclone. The setup should open on the first setting that is off.
2. Tap **Set up**, turn the setting on, come back: the next card should show by itself.
3. Tap **Skip**, then **X**. Reopen the app: the setup should not open again.
4. In Settings, tap **ⓘ** next to Background work: that one card shows.
