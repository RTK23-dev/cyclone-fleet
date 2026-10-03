# Cyclone V5 Alpha 70: the AI screen's official scene

Developer alpha for owner testing. It builds on Alpha 69 (the AI screen redesign) and includes it.
- **Mobile:** `5.0.0-alpha.70.dev1` (version code 215).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.70.dev1.exe` (runtime `5.0.0-alpha.68.dev1`, unchanged).
- **Glass:** `1.0.0-alpha.40` (unchanged).

The zoom film the owner approved (from a virus through a flower, a dome, a hurricane and the sun to galaxies) is now
the AI page's looping background, running as code instead of a video.

## What changed

**The scene, 725 KB.**
- `res/raw/ask_scene.mp4` is 80 × 144 pixels at 30 fps. It is not the picture you see. It holds only what the rain
  needs for every digit: its light and its colour, baked with the same exposure, contrast, saturation and whitening
  as the approved render (`docs/design/redesign/rounds/R3/bake_scene.py`). The pre-rendered film was 29 MB.

**The digits are drawn live.**
- The rain shader reads each cell's light and colour from the scene and draws Cyclone's digits (`0–9 A–F · : + /`)
  at full screen resolution, at the mock-up's density (4 × 6 dp, 90 across a 1080 px screen).
- White rain streaks fall through it, each digit has a soft colour glow behind it, and a faint ghost of the film shows
  through, as in the render.
- The glass still blurs and darkens it, so copper panels sit over the dome and white over the flash.

**Battery and safety.**
- The scene is decoded on one background thread, only while the AI page is on screen. It stops when you leave the
  app.
- With Android animations off it shows one still frame.
- If a phone cannot decode it, the drawn dome from Alpha 69 takes over.
- A video you pick yourself (logo panel › Background video) still overrides it.

## Provenance

The owner judged the source clip (a "Powers of Ten"-style zoom found on Pinterest, pin 1070801248913334924) to be
AI-generated with no copyright claim, and chose to ship it. No creator or licence could be found. If a rights holder
objects, one file changes: bake another clip into `ask_scene.mp4`. See the R3 contract.

## Tests

- `AskSceneColorTest`: the BT.709 decoding and each cell's light.
- `AskScreenR3ContractTest`: the scene stays under 1 MB; the shader reads light and colour from it; decoding stops on
  pause; one frame in still mode; the dome fallback; no network or file writes.
- Before building it, a Python simulation of the shader reading the baked scene matched the 1080 × 1920 render at
  the ladybird, the dome and the white flash.

## Physical acceptance

UNVERIFIED. Not yet seen on a phone: decoding smoothness, battery use, and how the digits look at 4 × 6 dp.
