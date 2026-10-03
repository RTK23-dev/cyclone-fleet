# R5: the whole app on one glass world

**Status:** built in 5.0.0-alpha.72.dev1. Physical acceptance UNVERIFIED.
**Scope:** every in-app page (Home, Profiles, AI, Routines, Brain, Marketplace, Settings). The floating overlay is
unchanged ("no overlays yet"): it stays teal, and its theme never enters the glass world.

R3 gave the AI page smoked glass over the Cyclone rain; R4 put Home on the same material. R5 makes it the app's one
material and adds two things the owner asked for: navigation that clearly sits above content, and a Lite version for
older phones that keeps the design.

## 1. One world, drawn once

`AskGlassWorld` (ui/v32/ask/AskGlassWorld.kt) wraps the app's Scaffold in `CycloneMobileV32App`.

- It draws the rain once for the whole app and records it as the backdrop every smoked-glass surface blurs.
  Switching tabs never restarts it.
- With the AI page in front (`aiStage`), the rain plays the official scene, or the owner's own video if they picked
  one. Everywhere else it is the drawn dome, and the decoder is never created.
- One scrim for the whole app deepens the top and bottom. Home and the AI page's empty canvas add only their greeting
  pool (`AskGreetingPool`).
- Home (`AskGlassPage`) and the AI page detect the world (`LocalAskWorld`) and reuse it. Standing alone they still
  draw their own, as in R3 and R4.
- `CycloneTheme(glass = true)` draws no teal canvas and gives Material parts `AskGlassScheme`: white primary,
  graphite surfaces, and the glass state colours (done green, waiting amber, problem red).
- The system bars take the world's graphite (`CycloneSignatureSystemBars(glass = true)`).

## 2. Two tiers: content and chrome

`Modifier.askGlass(radius, tier, shineOffset, shape, smoke)` is the one call surfaces use. It reads the backdrop,
the shared blur, the shine and the quality from the page.

| | Content | Chrome |
|---|---|---|
| Used by | cards, lists, chips, run tiles, grouped settings | tab bar tray, header chips, model pill, sheets, both Ask bars, back chips, status chip |
| Smoke | 0.52 | 0.72 (sheets 0.44, over a dimmed page) |
| Blur | 20 dp | 26 dp |
| Rim lens | 12 / 20 dp | 16 / 28 dp |
| Highlight | 0.9 dp at 0.85 | 1.2 dp at 1.0 |
| Shadow | 18 dp, y 6, 32 % | 26 dp, y 10, 45 % |

Navigation is darker, blurrier, more bent at the rim, brighter at the edge and casts a deeper shadow, so it always
reads as the layer above.

## 3. Pages follow the primitives

Pages were not rewritten one by one. The shared primitives take the glass branch when a backdrop is present
(`inAskGlass()`), so every page that uses them follows:

- `CycloneSignatureGlass` (cards, surfaces, quick actions): smoked content glass. Textured capsules keep the silver
  dots.
- `CycloneSignatureTheme`: `AskGlassScheme`.
- `CycloneLiquidTray`: chrome. `CycloneLiquidPanel`: content. The selection lens is a white veil.
- `CycloneMatrixIconTile`: a soft white square, or a colour tile (`fill`).
- `CycloneMatrixRing`, `CycloneMatrixSectionHeader` and `matrixAccent()`: white instead of teal.
- `CycloneBackRow` and the Settings back button: chrome glass chips.
- `CycloneStatusPill`: a glass capsule with a green or amber dot.
- `CycloneSectionTitle`: a quiet label. `CyclonePageHeader`: large title with the words' shadow. `CycloneHairline`:
  the glass hairline.
- Settings: each group has its own tile colour (AI violet, Drive green, Appearance blue, Phone orange, Profiles
  cyan, Knowledge indigo, Connections sky, Privacy blue, About grey).

## 4. Full, Lite and Auto

Settings › Appearance › **Visual quality**: Auto, Full or Lite, with a line saying what Auto chose and why.

- **Full**: the design as above.
- **Lite**: the same look with less work. Words, layout, colours, rims, the highlight and the shine are identical.
  - The rain is blurred once per frame into a shared layer, drawn under the rain so it is never seen itself. Content
    glass samples that layer: one blur instead of one per panel.
  - Content glass drops its own lens and cast shadow. Chrome keeps the full recipe.
  - The rain draws 20 frames a second instead of 30.
- **Auto** (`QualityPolicy.autoTier`) starts Lite on phones that are clearly older or budget:
  - Android's low-RAM flag;
  - under 4.5 GB of RAM;
  - fewer than 6 cores;
  - no declared media performance class together with under 6 GB.

  Everything else starts Full.
- **The frame watch** (`AskFrameWatch`, Auto on Full only):
  - It waits 4 s, then times frames over a rolling 3 s window.
  - It steps down to Lite when more than 12 % of at least 90 frames run over 1.5 × a 60 Hz frame.
  - Gaps over 250 ms are idle time, not slow frames.
  - The step-down is remembered until the owner picks a mode again. Choosing Auto again gives Full another chance.
- **What is stored:** only the choice (`visual_quality`) and whether Auto stepped down, in the `cyclone_ui`
  preferences. Nothing about the phone or its frames is kept.

## 5. Always on, in both qualities

- **The rain holds still while a list scrolls.** A nested-scroll hook records the scroll (`AskMotion`), and the rain
  resumes 350 ms after the last one, where it stopped.
- **The shine** is painted on its own small layer above the glass, so its movement never re-runs a blur.

## Tests

- `AskQualityTest`: Auto's device rules, the owner's choice winning, the explanations, the jank window and the
  scroll hold.
- `AppR5ContractTest`:
  - one world, reused by Home and the AI page;
  - navigation on the chrome tier;
  - Lite's shared blur and its drop of lens and shadow on content only;
  - the frame watch and the setting;
  - only the choice stored;
  - no teal in the new files;
  - the overlay untouched.
- Updated: `AskScreenR3ContractTest`, `CycloneTealMatrixTest`.
