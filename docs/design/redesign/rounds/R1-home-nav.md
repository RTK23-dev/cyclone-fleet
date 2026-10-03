# R1 · Home B + liquid glass navigation

Status: **proposal**. The owner picked R0's B (ask first). R1 keeps it and replaces the tab bar with an
Apple-style liquid glass navigator, built on Kyant's libraries, which the app already ships.

| Board | Shows |
| --- | --- |
| Home B · liquid tab bar | B's Home. The task island sits above a floating squircle capsule (Home, Profiles, Routines, Brain) with a separate Chat circle. Content scrolls under the glass. |
| Scrolled · minimized | After scrolling down, the capsule shrinks to the current tab's circle and the task sits inline between the two circles. It restores when you scroll up. |
| States + anatomy | Rest, drag (gel press), minimized, needs you; the seven layers and the performance budget. |

## Build contract

| Piece | Compose / library | Notes |
| --- | --- | --- |
| Shape | `com.kyant.capsule.ContinuousCapsule` (2.1.1, in the build) | Capsule 64 dp / radius 32, Chat circle 64 dp, 10 dp apart, 16 dp side inset, 14 dp above the gesture bar. Minimized: 56 dp circles. |
| Sampled content | `com.kyant.backdrop` 1.0.0 `rememberLayerBackdrop` + `layerBackdrop` on each page's scroll container only | **Never** on `TealMatrixField`: alpha.20/21 lensed the whole animated canvas, which gave dot noise, a re-blur on every frame and a first-frame stall (`b2a1937a`). The field under the bar is painted by the existing analytic `tealGlass` shader. |
| Frost, lens | `drawBackdrop { vibrancy(); blur(6.dp); lens(12.dp, 24.dp, chromaticAberration = true) }` | Lens and colour fringe in the 12 dp edge band only. |
| Rim | `GlassLight` + `litRim` | Same tilt light as Tilt Glass. |
| Selection droplet | `Animatable` offset and width, `pointerInput` drag | Tap: spring (damping 0.8, stiffness 500). Drag: stretches between tabs, the bar scales 1.03, the tab under the finger 1.14. Written in-house on Kyant. `Abdullajon1881/LiquidGlass` is not added: it hasn't been reviewed, and one glass library is enough. |
| Minimize | `NestedScrollConnection` on the page | Scroll down minimizes, scroll up restores. The running task (`WorkIsland`, Tilt Glass) becomes the inline accessory. |
| Needs you | Chat circle gets the warm rim `#E9C78B` and a dot | Opens the owner moment. The approval boundary is unchanged. |
| Tabs | `V32Destination`: Home, Profiles, Routines, Brain in the capsule; AI becomes the Chat circle | Navigation only; Task Kit is untouched. |

## Performance gate (must pass before release)

- Pixel 8, 120 Hz, scrolling Home: 90 % of frames under 8 ms (`adb shell dumpsys gfxinfo com.cyclone.mobile`).
- An idle bar redraws nothing (no infinite animation; the backdrop only updates when the content moves).
- Glass shaders pre-compile on a background thread in `MainActivity.onCreate` (the existing pattern).
- Fallback to painted glass with no capture: Battery Saver, `ActivityManager.isLowRamDevice`, animator scale 0, or
  JankStats reporting sustained over-budget frames.

The Tilt Glass doc gains a "Liquid navigation" section once the owner agrees: the only clear, refracting surface
in the app, used for navigation alone.
