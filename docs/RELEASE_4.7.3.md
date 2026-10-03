# Cyclone Mobile 4.7.3 — Signature glass

Android versionCode 133, based on published 4.7.2.

The floating Ask Cyclone bar now uses a dark teal glass material, soft optical rim and fine dotted contour texture around its curved ends. Thin outline controls and a microphone halo give the bar the visual identity of the approved concept. The renderer caches the texture geometry and does not capture another app to create the effect.

The overlay input no longer reserves a row for model and intelligence labels. These controls remain under + → Model & intelligence, where both tabs are directly selectable and model setup has a working Open Settings action. Photos and Camera use compact dark tiles with readable labels.

The upper drawer retracts above a persistent composer. Expanded and minimized states reuse the same input instead of fading between two instances. Only upper content is clipped, with rounded edges; the composer remains anchored above the Android gesture area or keyboard. Existing foreground pause/stop controls and task execution boundaries are preserved.

## Verification

Publication is gated by exact-source Mobile CI: repository/security guards, gateway contracts, Android unit tests, lint and unsigned release assembly. The existing publisher verifies provenance and signature continuity with 4.7.2 before publishing the APK.

Production-component Android Studio previews cover ready, listening, narrow width and large type. Physical Pixel 8 appearance, drag/keyboard behavior and pixel-for-pixel equivalence to the concept are **UNVERIFIED**. The glass is a native tinted optical rendering; it does not promise live cross-app background refraction. This change targets the floating overlay; in-app chat keeps its existing layout.
