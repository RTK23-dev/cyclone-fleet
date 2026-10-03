# Cyclone Teal Matrix

Teal Matrix is Cyclone's single in-app visual language, introduced in 5.0.0-alpha.8. It started as the
Ask Cyclone capsule's material and now applies to every screen.

The task surfaces (Ask bar, task card, island, owner moments, plane pill) use [Cyclone Tilt Glass](CYCLONE_TILT_GLASS.md)
since 5.0.0-alpha.43.

## Principles

- **One palette.** Deep teal canvas, teal ink, mint for success, coral for attention. A light device
  theme no longer produces a white/blue variant. The palette lives in `SignatureScheme` and
  `TealMatrix` (`ui/v32/CycloneSignatureGlass.kt`, `ui/v32/CycloneTealMatrix.kt`).
- **One material.** Cards, trays, tiles and the tab bar are the same translucent teal glass as
  the Ask Cyclone capsule (`CycloneSignatureGlass`), at a calmer intensity (no dotted whorls).
- **Quiet canvas.** `TealMatrixBackdrop` draws a layered gradient, two aurora blooms and diagonal
  ribbons of dots. It is cached per size and has no animation loop.
- **State by tint, not by layout.** `MatrixTone` changes the glass rim: coral when Cyclone needs
  you, mint when it's done, bright teal while it's working. Copy stays equally readable.

## Components

| Component | Use |
| --- | --- |
| `CycloneSignatureGlass(accent, focused)` | Ask Cyclone capsule and base material. `focused` lights the rim; a specular top sweep and a bottom bloom make it read as lit glass. |
| `CycloneMatrixCard(tone)` | Any content card. `CycloneSimpleCard`, `CycloneSurface`, `CycloneGlassSurface` and `CycloneHeroCard` all use it. |
| `CycloneMatrixAppBar` | Menu or back, centered Cyclone word mark, spiral mark. |
| `CycloneMatrixQuickAction` | Home quick actions (icon tile + label). They prefill the Ask bar; the user still sends. |
| `CycloneMatrixCheck` / `CycloneMatrixRing` / `CycloneMatrixAttention` | Done, running and needs-you markers for list rows. |
| `CycloneMatrixSectionHeader` | Section title with an optional teal action ("See all"). |

## Home

Centered greeting, four quick actions, the live task card, **Recent activity**, routines, and one
Ask Cyclone capsule pinned above the tab bar.

Recent activity (`CycloneRecentActivity`) is **in memory only**. Task goals can contain personal
text, so it is never written to disk, Brain or diagnostics. User-stopped tasks are dropped.

## Status

Verified by unit tests and by rendering the Home, task-card and Settings screens offline
(Robolectric, native graphics). **Not verified on a physical phone.**

---

# Teal Matrix v2 — living canvas and real glass

## Evidence: what the alpha.9–18 design measured

| Check | Result | Standard |
| --- | --- | --- |
| Body text (ink `E0F5F3`) on cards / canvas | 13.4:1 / 16.4:1 | WCAG 2.2 1.4.3 needs 4.5:1 — large headroom |
| Secondary text (`A6CCCA`) on cards | 8.7:1 | passes AA and AAA |
| Card fill vs canvas | **1.23:1** | layers are indistinguishable; depth reads flat |
| Hairline / control edge vs card | **1.97:1** | WCAG 2.2 1.4.11 asks 3:1 for control boundaries |
| In-app surfaces that sample what is behind them | **0** | the "glass" was an opaque painted gradient |
| Background motion | **none** | static canvas |

Conclusion: contrast headroom can be spent on translucency; the gap is depth, material and life.

## Principles (sources)

1. **Glass is for the controls layer, content stays matte.** Apple's Liquid Glass guidance (WWDC25,
   Human Interface Guidelines › Materials) reserves glass for navigation and controls floating
   above content, and warns against glass-on-glass. Cyclone: Ask bar, tab bar, quick actions and
   liquid panels refract; task cards, recent rows and settings groups stay matte.
2. **Real material, not a picture of one.** Glass samples the live scene behind it (blur, lensing,
   vibrancy) and carries a specular edge highlight that separates it from content — the depth cue
   that 1.23:1 fills cannot give.
3. **Alive but calm.** Ambient motion must stay below attention: slow (tens of seconds), no discrete
   jumps, no flicker (WCAG 2.3.1 limits flashes; "calm technology", Weiser & Brown). Respect the
   system "Remove animations" setting (WCAG 2.3.3 Animation from Interactions).
4. **Readability is measured, never assumed.** Every translucent surface is checked against its
   brightest possible backdrop.
5. **Budget.** Motion capped at 30 fps; the most expensive pass (lens) is dropped on low-RAM phones.

## Wave 1 (this change)

- **Living canvas** (`TealMatrixField`, AGSL): teal base, two aurora ribbons on 40–60 s drifts,
  breathing blooms, and a fixed dot matrix that only brightens where a ribbon passes. Offline at
  30 fps: max per-frame change **2/255** (invisible), while ~45 % of the screen evolves over 10 s.
  1/255 dither prevents banding. Frozen when animations are off; static fallback if the shader
  cannot be created.
- **Real teal glass** (`CycloneSignatureGlass(refract = true)`): Kyant `drawBackdrop` with
  vibrancy, 14 dp blur, lens (10/22 dp), `Highlight.Default` specular edge, continuous
  (squircle) corners, and a thinner teal body so the moving canvas shows through.
- **Touch responds with light:** quick actions spring to 96.5 % and light their rim while pressed.
- **Measured readability on refracting glass** (offline composite over the live field):
  Ask bar ink 8.3:1 / placeholder 5.4:1; tab bar ink 10.3:1 / labels 6.8:1 — AA with margin.

## Next waves (proposed)

- **Wave 2 — depth & motion system:** one spring token set (press, sheet, page); shared-element
  transition from a Home quick action into Ask; tab-bar lens that stretches between tabs; scroll-
  linked app bar that turns into glass only once content passes under it.
- **Wave 3 — overlay parity:** the floating Ask overlay cannot sample other apps' pixels, so it
  keeps painted glass; pair it with cross-window blur (as the tools drawer already does) where the
  device allows it.
- **Verification on a Pixel:** frame timing (`dumpsys gfxinfo`) with the canvas running, and
  on-device contrast screenshots. Not yet verified on a physical phone.
