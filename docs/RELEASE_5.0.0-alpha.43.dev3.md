# Cyclone V5 Alpha 43 dev3 — Glass drawer and a calmer voice button

Developer alpha for owner testing, built on Alpha 43 dev2, which it includes. Mobile is `5.0.0-alpha.43.dev3`
(version code 187). Glass stays `1.0.0-alpha.26`. The Windows companion is not part of this release: keep Cyclone PC
Companion `1.6.0-alpha.43` installed.

## What changed

**The + drawer is a working card.** On the overlay and in the app, the drawer that opens from **+** now wears the
working card's glass: the same panel, the card's grabber and one soft inner panel. The layout is unchanged.
- **Photos** and **Camera** are glass buttons with the lit edge; they flash when pressed.
- **Files**, **Share screen**, **Cross-app share** and **Model & intelligence** each have a round lit button that
  flashes when you press the row.
- The **Model & intelligence** page uses the glass colours; its picker and choices are glass too. Settings screens
  are unchanged.

**The voice button no longer stops itself.**
- Voice starts as your finger goes down. Tap to talk, or hold while you speak and let go when you are done.
- A second touch in the first moment of listening is ignored, and two touches close together count as one.
- Stopping keeps what Cyclone heard in the Ask field. Before, a second press restarted the recognizer, which ended
  listening with an error.

**The listening orb is the agreed design.** A lit sphere (its highlight follows how you hold the phone) with a slow
swirl inside and a soft breath of light that rises into the bar and fades before its edge. The hard-edged teal
column is gone. The microphone turns dark on the orb so it stays readable.

## Validation and limits

Tests: Android unit tests (including a new test for the voice press rules), lint and the release build run in
Mobile CI; the CI guards (119) and the product guard pass.

**Physical Pixel 8 acceptance is UNVERIFIED.** The drawer, the voice button and the orb have not been seen on the
phone yet.

Install the APK as an update; do not uninstall. It is signed with the rotated Cyclone release key (the alpha.34 /
39 / 40 / 41 / 42 / 43 signer).

Suggested checks:
1. Open the overlay, tap **+**: the drawer should look like the working card, and each button should flash.
2. Tap **Model & intelligence**: the page should be on the same glass.
3. Tap the microphone and speak: it should keep listening; tap again after a moment to stop, and the words should
   stay in the Ask field.
4. Hold the microphone while speaking and let go: listening should end and keep the words.
