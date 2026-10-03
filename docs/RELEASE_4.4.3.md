# Cyclone Mobile 4.4.3 — Liquid Glass action controls

Cyclone Mobile **4.4.3 / versionCode 103** is built directly on the exact published 4.4.2 tree. It keeps the 4.4.2 native camera-streaming, security, task, workspace, recovery, consent, and visual-hierarchy work while replacing the shared Compose action-control rendering layer with Kyant0/AndroidLiquidGlass.

## Liquid Glass visual overhaul

- Uses Kyant0/AndroidLiquidGlass Backdrop `1.0.0` with Capsule `2.1.1` rather than a custom translucent glassmorphism recreation.
- Preserves the upstream LiquidButton optical recipe: `ContinuousCapsule`, vibrancy, 2dp blur, 12/24dp lens refraction, AGSL highlight, chromatic/optical response, and interactive press/drag deformation.
- Routes Cyclone's shared Material 3 filled, tonal, elevated, outlined, text, and icon action-button families through the Liquid Glass renderer.
- Handles existing 4.4.2 action controls that supply custom Material `shape` or `contentPadding`; their layout spacing is preserved while the optical surface remains the Liquid Glass capsule.
- Uses the renderer's `surfaceColor` path for neutral/light-background legibility instead of adding an unrelated border treatment.
- Keeps a single non-recursive Backdrop source in `CycloneTheme` so glass controls share one coherent visual environment.

## Visual hierarchy

Liquid Glass is applied to **action controls**, not indiscriminately to cards, navigation, status surfaces, or full-screen content. This preserves the 4.4 visual rule that Cyclone has one visual owner at a time and avoids recreating the stacked-surface chaos fixed in the previous visual sprint.

## 4.4.2 compatibility preserved

- The secure native-aspect camera viewer added in 4.4.2 is unchanged and remains outside the Compose Liquid Glass layer.
- Cyclone One 1.5.5 camera-streaming compatibility is retained.
- Minimum SDK remains 33 and target SDK remains 35.
- compileSdk is 36 to satisfy the Kyant renderer dependency; Android application targeting remains unchanged.
- Kotlin/Compose compiler is aligned to 2.2.21 while AGP remains on Cyclone's 8.7.3 line.

## Validation and release safety

- Repository guards pin the exact Kyant optical recipe, shared Backdrop ownership, Material action-family routing, custom padding/shape compatibility, and 4.4.3 Android identity.
- Four AGP 8.7 lint detectors that crash against Kotlin 2.2's Analysis API (`RememberInComposition`, `NullSafeMutableLiveData`, `FrequentlyChangingValue`, and `AutoboxingStateCreation`) are individually suppressed; broad lint suppression remains prohibited and all other lint checks stay active.
- Publication is authorized only from the exact-source Mobile CI candidate after tests, remaining lint checks, release assembly, checksum/provenance verification, and signer continuity with the published 4.4.2 APK succeed.

Physical-device Liquid Glass visual/interaction acceptance remains **UNVERIFIED** until tested on real hardware. This release does not claim hardware acceptance that has not occurred.
