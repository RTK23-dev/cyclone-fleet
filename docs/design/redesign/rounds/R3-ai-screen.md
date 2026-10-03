# R3 · AI screen — rain behind dark glass

Status: **agreed** by the owner ("start building this final redesign", after the five-state board and the moving
home mock-up). Board: [`R3/ai-screen-states.jpg`](R3/ai-screen-states.jpg), left to right: home · model selector ·
burger menu · logo menu · running task. Supersedes R2's proposals for this page (R2 was never built).

## What changes

1. **Background:** the AI page's teal gradient and breathing dot field give way to the **Cyclone rain**. The rain uses
   the Trace Field's own glyphs (`0–9 A–F · : + /`). Each digit is lit by a slow, colourful scene: a dome of rings
   and spokes that drifts through copper, violet and blue. Rain streaks fall through it.
   The board's film was a third-party clip, used only to judge the look; the shipped scene is drawn by our shader.
2. **Glass:** every surface on the page is **smoked glass**. It is a live blur of the rain beneath it (so it takes
   the rain's colours), refracted at the rim like thick glass, and clearly darkened. A thin white highlight runs
   along the rim, and a soft shine band crosses it every 6 s. No teal anywhere on this page.
3. **Header:** the burger sits top left and the Cyclone mark top right, both round glass chips. Between them, the
   **model selector** pill (current model + chevron) replaces the word "Cyclone".
4. **Home:** the orb dot is gone. The greeting sits just under the header. Under it: **Suggestions** (2 × 2 glass
   chips that fill the Ask bar) and **Recent runs** (one glass card with the last three runs, "See all" opens the
   menu).
5. **Ask bar:** the same bar (`GlassComposerBar`) on smoked glass. It keeps the fingerprint dots at both ends
   (silver now) and the rim shine.

## Build contract

| Element | Compose | File | Notes |
| --- | --- | --- | --- |
| Rain | `AskRainField` (AGSL `RuntimeShader` via `ShaderBrush`) | `ui/v32/ask/AskRain.kt` | Glyph atlas from `TraceFieldShader.GLYPHS`, 5 × 7.5 dp cells. About 30 fps while the page is visible. Still with Android animations off. Falls back to a dark gradient if the shader fails. |
| Official scene (alpha.70) | `AskScene` + `AskRainField` | `ui/v32/ask/AskScene.kt`, `res/raw/ask_scene.mp4` | A 80 × 144, 30 fps, ~725 KB video baked by `R3/bake_scene.py` (same exposure, contrast, saturation and whitening as the owner-approved dome render). It holds only each cell's light (brightest channel) and colour; the shader draws the digits live at 4 × 6 dp, with white rain heads, a 0.42 colour tile and a 0.09 ghost, as in `render.py`'s colour mode. Decoded on one background thread, only while the page is visible; one frame with animations off; the drawn dome if the phone cannot decode it. |
| Owner's video | `AskVideoField` + `AskBackground` | `ui/v32/ask/AskBackground.kt` | Logo panel › **Background video** picks a video on the phone (`OpenDocument`, `video/*`). Cyclone keeps only Android's read grant and the URI, plays it muted, looped and centre-cropped in a `TextureView` the glass blurs, pauses it in the background, and falls back to the rain if it cannot be read. **Use the rain** hands the grant back. Nothing is copied or uploaded; no video ships in the APK. |
| Scrim | `AskScrim` | `ui/v32/ask/AskRain.kt` | Top 45%, bottom 35%, overall 12% black, plus a soft shade behind the greeting. |
| Smoked glass | `Modifier.smokedGlass(backdrop, radius, smoke)` | `ui/v32/ask/SmokedGlass.kt` | Kyant `drawBackdrop`: `vibrancy()`, `blur(20 dp)`, `lens(12 dp, 20 dp)`, white highlight, soft shadow; surface black at `AskGlass.SMOKE` (0.52), shine band from `LocalAskShine`. Without a backdrop it is painted (`#E0121418` + white hairline). |
| Tilt Glass on this page | `tiltGlass(…, palette = LocalGlassPalette.current)` | `ui/overlay/glass/TiltGlass.kt` | New `GlassPalette.SMOKE`: graphite body, white hairline and rim glow, silver dots. The page provides `SMOKE`, so the working card, island, owner card and sheets turn neutral. The floating overlay keeps `TEAL`. |
| Header | `AskHeader` | `ui/v32/ask/AskHeader.kt` | Burger → menu drawer · model pill → model sheet · mark → logo panel. 44 dp targets. |
| Home | `AskHome` | `ui/v32/ask/AskHome.kt` | Greeting (`AskCopy.greeting`), `AskCopy.SUGGESTIONS` fill the composer (they never send), recent runs from `MindMissions.history`. |
| Model sheet | `AskModelSheet` | `ui/v32/ask/AskSheets.kt` | `OpenRouterCatalogStore.picker/setActive`; thinking levels from `reasoningOptions/reasoningSelection/setReasoningEffort` (exact provider tokens). |
| Menu drawer | `AskMenuDrawer` | `ui/v32/ask/AskSheets.kt` | New chat, search runs, runs grouped by `AskCopy.dayGroup`. A run opens inline: summary, **Resume** (`MindMissions.resume`, only when resumable and nothing is live), **Remove** (`MindMissions.delete`). Links: Routines, Brain, Settings. |
| Logo panel | `AskLogoPanel` | `ui/v32/ask/AskSheets.kt` | Phone control status (→ Settings › Phone control), Driver mode switch (same rules as Settings: asks for the microphone), User notes switch, Model & API, Settings. |
| Ask bar | `GlassComposerBar` smoked branch | `ui/v32/InAppGlass.kt` | When `LocalAskBackdrop` is set: `smokedGlass(33 dp)` + `askWhorls()` + dark words pill. |
| Copy | `AskCopy` (pure, tested) | `ui/v32/ask/AskCopy.kt` | Greeting, suggestions, day groups, run lines ("Done · 2h ago"). |

## Behaviour that stays

- Requests go through `RequestIntentRouter` exactly as before (chat, phone task, queue); nothing new sends.
- Task buttons (stop, pause, take over, approve…) keep going through Task Kit (`TaskCommands`).
- The page reads no screen pixels: the glass blurs only Cyclone's own rain, inside Cyclone's window.
- Nothing new is stored. Search text and the open drawer live in memory only.
- With Android animations off, the rain and the shine stand still.

## Not in R3

- The bottom tab bar keeps its current look (R1's liquid navigation is a separate round).
- The floating overlay over other apps keeps Tilt Glass teal: an overlay cannot blur another app's pixels.

## Scene provenance

The official scene is baked from the "Powers of Ten"-style zoom the owner supplied (Pinterest pin
1070801248913334924, downloaded via Klickpin). The owner judged it AI-generated with no copyright claim and chose to
ship it (2026-09-29). No original creator or licence could be found: the pin carries no credit and its file no
metadata. If a rights holder objects, replace `res/raw/ask_scene.mp4` with a bake of another clip
(`python3 R3/bake_scene.py SRC ask_scene.mp4`); nothing else changes.
