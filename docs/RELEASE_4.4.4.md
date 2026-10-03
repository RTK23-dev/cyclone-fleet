# Cyclone Mobile 4.4.4 — Cohesive Liquid Glass redesign

Cyclone Mobile **4.4.4 / versionCode 104** is built directly on the published 4.4.3 release. It keeps the existing phone-control, task, workspace, camera-streaming, security, recovery and consent architecture while completing the visual-control overhaul around Kyant0/AndroidLiquidGlass.

## Cohesive Liquid Glass interaction language

- Keeps Kyant0/AndroidLiquidGlass Backdrop `1.0.0` with Capsule `2.1.1` as the optical renderer instead of substituting generic translucent glassmorphism.
- Preserves the Kyant LiquidButton recipe and shared Backdrop ownership so controls refract the same visual source on dark and light/white backgrounds.
- Extends Liquid Glass beyond ordinary buttons into navigation, search, model/API actions, composer chrome and selection controls without turning content cards into glass.
- Uses quiet Material surfaces for information and reserves refraction for controls, preventing the stacked "glass soup" and duplicate-control problems fixed in the 4.4 visual work.

## Selection controls, toggles and three-option controls

- Rebuilds shared segmented controls as one liquid tray with one moving refractive selection lens.
- Applies that model to three-option choices such as phone autonomy and routine repeat controls rather than rendering three unrelated pills.
- Keeps provider/model-default states visually neutral instead of falsely highlighting a reasoning override.
- Replaces the Quick Setup stock switch with a dedicated Liquid Glass toggle.
- Converts queued-task destination choices and other discrete action selectors to the same coherent control language.

## Ask Cyclone and settings

- Rebuilds the Ask Cyclone composer as one integrated Kyant liquid panel with intelligence, attachment and microphone actions plus a distinct primary liquid Send control.
- Keeps the model pill separate from the typing width and keeps intelligence/autonomy controls available from the composer without duplicating model selection.
- Converts model search, model filtering, API-key actions and settings interaction chrome to the shared liquid primitives.
- Keeps destructive actions visually distinct while retaining the same optical system.

## Routines and navigation

- Rebuilds bottom navigation around a shared liquid tray and moving refractive selection lens.
- Converts Routines search to liquid interaction chrome.
- Uses the shared selection component for Apps / Categories / Specifics and other segmented surfaces.
- Converts Once / Daily / Weekly repeat choice to the same three-state liquid selection control.

## Compatibility and release safety

- Minimum SDK remains 33 and target SDK remains 35.
- compileSdk remains 36 for Kyant compatibility.
- Kotlin/Compose compiler remains aligned to 2.2.21 while AGP remains on Cyclone's 8.7.3 line.
- Cyclone One 1.5.5 compatibility and the secure native-aspect camera viewer are preserved.
- Publication is authorized only from the exact release-branch SHA after Mobile CI tests, lint, release assembly, checksum/provenance verification and signer continuity with the published 4.4.3 APK succeed.

Physical-device Liquid Glass visual/interaction acceptance remains **UNVERIFIED** until tested on real hardware. This release does not claim hardware acceptance that has not occurred.
