# Cyclone V5 Alpha 94: The new Cyclone logo

Developer alpha for owner testing. It builds on Alpha 93 and includes it. It has no behaviour changes, only the new
logo, on every surface that showed the old three-arc mark (or no mark at all).

Versions:
- **Mobile:** `5.0.0-alpha.94.dev1` (version code 239).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.94.dev1.exe` (runtime `5.0.0-alpha.94.dev1`).
- **Glass:** `1.0.0-alpha.52`.

## The mark

Seven petals split through the rim. The splits are logarithmic spirals that wind exponentially smaller into a black
core: the center of a storm, or a wormhole. The design, its construction and the generators are in
`brand/logo-wormhole/`.

## Where it is now

| Surface | Version |
|---|---|
| **Phone launcher icon** (new: the app had none, so Android showed its default icon) | Colour mark on the deep teal tile, adaptive (circle, squircle, any mask) |
| Themed icon (Android 13+, Material You) | One colour, tinted by your wallpaper |
| In-app mark (app bars, Home, Ask) | Colour |
| Notifications, both Quick Settings tiles, the overlay orb and header | One colour, a cut made for 16–24 px |
| "Unknown app" icon fallbacks | Colour |
| Glass switcher logo and browser-tab icon | Colour on the app tile |
| Windows: `CyclonePCRuntime.exe`, its Start menu and desktop shortcuts, the setup and the uninstaller | Colour app tile (16–256 px icon) |

## Checked

- A CI guard (`test_brand_logo.py`) checks the wiring:
  - the manifest's launcher icons;
  - the adaptive layers, including the themed one;
  - every Android drawable comes from the brand geometry;
  - status and tile icons stay one colour;
  - Glass and Windows use the new mark.
- The Android vector drawables were converted back to SVG and rendered under launcher masks, as a themed icon and at
  status-bar sizes. Mobile CI compiles them into the APK.
- Glass tests, build and guard pass.
- On the phone and on Windows: **UNVERIFIED**. Check the launcher icon (including themed icons), a notification, the
  Quick Settings tiles and the installer icon.
