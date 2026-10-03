# 30 — Setup cards (alpha.46)

**Status:** built in **alpha.46** (2026-09-27). Physical Pixel acceptance is still owed.

**The owner's ask:** "Make a small welcome helper path for settings with instructions so clear anyone understands, for
all the important settings, in super short, super clear human language. A clear Setup button that takes them to the
right settings, a smaller Skip button in the bottom right corner, and an X on the top right. Include the background
engine. In Settings, an info button on the most important ones shows the card again."

**Design:** follow [`docs/design/CYCLONE_TILT_GLASS.md`](../docs/design/CYCLONE_TILT_GLASS.md) exactly. Read it before
writing any UI. Reuse its tokens and parts (`ui/v32/InAppGlass.kt`, `ui/overlay/glass/GlassKit.kt`); add no new
colours, radii or materials.

---

## 1. The cards

One card per setting. Each has a title, one or two short lines on why, and a Setup button. The copy below is a draft:
keep it at this length or shorter.

| # | Card | Why (draft copy) | Done when | Setup opens |
|---|---|---|---|---|
| 1 | Phone control | "Lets Cyclone tap and read the screen for you. You can stop it any time." | `CyclonePermissionSetup.phoneControlReady` | `accessibilitySettings()` |
| 2 | Show over other apps | "Lets Cyclone show what it's doing, and a stop button, on top of any app." | `overlayEnabled` | `overlaySettings(context)` |
| 3 | Notifications | "So Cyclone can tell you when a task is done." | `resultNotificationsEnabled` | `appDetails(context)` (or the runtime request on Android 13+) |
| 4 | Read notifications | "So Cyclone can read and answer messages without opening the app." | `notificationAccessEnabled` | `notificationAccessSettings()` |
| 5 | Keep running | "Stops Android from pausing Cyclone in the middle of a task." | `batteryUnrestricted` | `batteryExemptionRequest(context)` |
| 6 | Background work | "Lets Cyclone work on a hidden screen, so your phone stays yours. Needs the free Shizuku app." | `MissionPlanes` capability is ready and the last Background Check passed | `BackgroundSetupActivity` (existing Shizuku guide) |
| 7 | Calendar | "So Cyclone can check and add events without opening an app." | `calendarEnabled && calendarWriteEnabled` | Android's own permission dialog (as `DirectAccessActivity` does) |
| 8 | Contacts | "So Cyclone can find someone's number. It never changes your contacts." | `contactsEnabled` | Android's own permission dialog |

Card 6 is only shown on Android 15 or later. The copy says nothing technical (no "Accessibility service", "ADB",
"virtual display").

## 2. The flow

- **When it opens:** once after install (and once after an update that adds a card), and from Settings → "Set up
  Cyclone".
- **Order:** the table order. Cards that are already done are skipped automatically, both when the flow starts and when
  the owner comes back from Android's settings.
- **Buttons:**
  - **Setup:** the main lit button. It opens the right Android screen; coming back re-checks and moves on only if the
    setting is now done.
  - **Skip:** smaller, bottom right. Moves to the next card; the skipped card stays open to set up later.
  - **X:** top right. Closes the whole flow. It does not come back by itself, except for a new card after an update.
- **End:** a short "You're set" card naming anything still off, and where to set it up later (ⓘ in Settings).
- **Info buttons (ⓘ):** in Settings, next to Phone control, Notifications and Background work. Tapping one shows that
  one card, with the same buttons (Skip then closes it). Settings → Phone → **Set up Cyclone** runs the whole flow
  again and shows how many settings are on.
- **Never:** Cyclone never turns a setting on for the owner and never taps inside Android's settings screens during this
  flow. The owner flips every switch; Android keeps the permission dialogs.

## 3. Code shape

- **Pure model** (`setup/SetupCards.kt`: `SetupCard`, `SetupCopy`, `SetupFlow`): the card list, and `next(done, skipped, dismissed)` deciding which
  card comes next. No Android types, so it is unit-tested directly.
- **Android glue** (`setup/SetupState.kt`): a small `SetupState` that reads the checks in `CyclonePermissionSetup` and the background capability,
  and a `SetupStore` with seen, skipped and dismissed card ids. It keeps nothing else.
- **UI:** a Tilt Glass card sheet (`ui/v32/SetupCardSheet.kt`, hosted in `CycloneV32App`) and the ⓘ buttons on the `Settings426Row` entries in
  `ui/v32/CycloneSettings426.kt`.
- **Routing:** "Quick setup" stays for rooted phones only; "Set up Cyclone" is the one guided path.

## 4. Proof

- Unit tests (`SetupCardsTest`) for `next(...)`: done cards skipped, skip order, X ends the flow, a card added by an update shows again,
  and card 6 is hidden below Android 15.
- A CI guard (`scripts/ci/tests/test_mobile_setup_cards.py`): every card opens an Android settings intent or Android's own dialog, and none uses accessibility actions
  or the executor.
- `./gradlew :app:testDebugUnitTest`; `python scripts/ci/release_versions.py --check`;
  `python scripts/ci/mobile_product_guard.py`.
- Physical phone: UNVERIFIED until the owner runs the flow.

## 5. Release

Alpha.46 through the fast lane (AGENTS.md → Versioning): `5.0.0-alpha.46.dev1`, versionCode **190**, with
`docs/RELEASE_5.0.0-alpha.46.dev1.md`. The PC companion stays frozen.

## 6. Out of scope

Cyclone's own privileged engine. See plan 29 §4: Shizuku stays the helper, and this plan only makes its setup clearer.
