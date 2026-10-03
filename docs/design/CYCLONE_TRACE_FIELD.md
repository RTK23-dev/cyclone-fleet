# Cyclone Trace Field — a working-state overlay

Status: **shipped in Mobile 5.0.0-alpha.5.dev1** (developer alpha, not verified on a physical device). API 33+.
See [§10](#10-what-shipped-in-500-alpha5dev1) for what the alpha implements and what is deferred.

> The phone looks the way it always does, until you look closely. Then you see that Cyclone is reading it.

## 1. The idea in one paragraph

While Cyclone is working, a field of very small, sharp numerals sits on top of the screen. It is
almost invisible. The field is not the movie's rain. It stays still and dark everywhere except
under a soft **lens**, a searchlight that drifts to whatever Cyclone is actually looking at. Under
the lens the digits light up briefly, roll to new values like a split-flap board, and fade out
behind it. The lens follows the agent's real attention, and the digits come from the agent's own
data (screen fingerprints and node hashes), so the effect is a live readout of the work and not
decoration. When Cyclone taps something, the lens tightens around it and a single ring of digits
ripples out. When the task is done, the field falls away downward in one last short "rain" and
is gone.

We use the reference images for their **grain** (tiny, detailed glyphs with depth falloff) and
**motion** (vertical glyph flow), and drop the rest: no green, no full-screen noise, no permanent
motion.

## 2. Is this possible on Android? Yes. Here is how.

| Question | Answer | Notes |
|---|---|---|
| Full-screen overlay above every app | ✅ | Cyclone already hosts chrome on `TYPE_ACCESSIBILITY_OVERLAY` (`OverlayChromeController.kt`). Add one more window: `MATCH_PARENT`, `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`, `PixelFormat.TRANSLUCENT`, `layoutInDisplayCutoutMode = ALWAYS`. |
| Touches pass through to the app below | ✅ | Since Android 12, untrusted overlays (`TYPE_APPLICATION_OVERLAY`) with opacity above 0.8 block pass-through touches. **Accessibility overlays are trusted and exempt**, and the field stays well under 0.8 anyway. |
| GPU shader effects in an overlay | ✅ | `RuntimeShader` (AGSL) arrived in **API 33, which is Cyclone's `minSdk`**. It works in a `View` (`Paint.shader`) or from Compose (`ShaderBrush` / `Modifier.graphicsLayer { renderEffect = RenderEffect.createRuntimeShaderEffect(...) }`). |
| Crisp tiny glyphs inside a shader | ✅ | Pre-render a glyph atlas (0–9, A–F and a few katakana-inspired strokes; see §5) into an `ALPHA_8` bitmap at device density. Pass it to AGSL as a `uniform shader` via `BitmapShader`. Sample with `FILTER_MODE_NEAREST` at 1:1 for razor-sharp 6–7 dp digits. |
| Real blur/glass under the lens | ⚠️ Partial | `FLAG_BLUR_BEHIND` + `blurBehindRadius` (API 31+) blurs the **whole** window region and depends on the device (`WindowManager.isCrossWindowBlurEnabled`). It can't be applied under a moving lens alone. Recommendation: **no blur**. Additive light reads as more premium and costs far less. |
| 120 Hz smoothness | ✅ | A shader that is mostly transparent and does one atlas sample per cell is cheap on Tensor/Adreno. Drive it from `Choreographer`/`withFrameNanos`. Glyph *value* changes are quantized to ~12 Hz (it's a split-flap, not noise), while lens position interpolates at full refresh rate. |
| Haptics tied to the visuals | ✅ | `VibrationEffect.startComposition()` with `PRIMITIVE_LOW_TICK` (lens lock) and `PRIMITIVE_TICK` (tap ripple). Check `areAllPrimitivesSupported` and degrade silently. Off by default. |
| Respecting system accessibility | ✅ | Read `Settings.Global.ANIMATOR_DURATION_SCALE`. At 0 (Remove animations), show a static edge glint and no motion. Also honor "High contrast text" and battery saver: halve fps and drop to edge-only mode. |

### 2.1 The one hard constraint: Cyclone must not see its own field

Cyclone perceives the phone through screenshots and the accessibility tree. The existing chrome
uses `FLAG_SECURE` to keep itself out of capture, and on a small pill that is fine. **On a
full-screen window it is a trap**: capture paths render secure layers **black**, so every
screenshot the agent takes would come back black and vision would break.

The rules for the field window:

1. **Do not set `FLAG_SECURE`** on the field window.
2. **Capture gate:** extend the existing "minimize own overlay before observation" path. The
   observer asks the field to hide, the field sets its alpha to 0 on the next frame (no fade, one
   frame is ~8 ms at 120 Hz) and acknowledges, the screenshot is taken, and the field is restored
   with a 120 ms ease-in. Nobody notices a one-frame blink at 4 % opacity. Budget: +1 frame per
   observation.
3. **Keep it out of the a11y tree:** set `importantForAccessibility = NO_HIDE_DESCENDANTS`, give it
   no content description, and filter this window id out of `AccessibilityService.getWindows()` in
   the scene builder (same bucket as the other Cyclone chrome that `taskSurfaceLooksCycloneOwned`
   already handles).
4. Add a unit test next to `OverlayChromeWindowPolicyTest` asserting the field's flags contain
   `NOT_TOUCHABLE` and do **not** contain `SECURE`, plus an observation test proving that capture
   waits for the hide acknowledgement.

## 3. Design language

**Principle: it has to earn attention, not ask for it.** At arm's length the screen looks normal,
with at most a faint shimmer at one edge. At reading distance you find the detail.

| Token | Value | Why |
|---|---|---|
| Glyph size | 6 dp cap height (≈ 16 px on Pixel 8) | Readable only when you look for it |
| Cell | 9 × 12 dp grid, jittered ±0.5 dp per column | Removes the "spreadsheet" look |
| Typeface | Monospace with tabular figures, slashed zero (e.g. JetBrains Mono / Google Sans Mono), rendered into the atlas | Engineered, not cinematic |
| Base field opacity | **0 %** (the field is invisible outside the lens) | Nothing on screen but the work |
| Lens peak opacity | 18 % on dark UI, 10 % on light UI (luminance-adaptive, see §4.4) | Visible but never covers content |
| Color | Cyclone accent with chroma pulled down: cool white core → accent-tinted falloff. **Not green.** Blend: additive (`BlendMode.PLUS`) on dark, `MULTIPLY` at half strength on light | Light, not paint |
| Depth | 3 layers (6 dp, 5 dp, 4 dp glyphs) with 100 % / 55 % / 25 % intensity and slight parallax | The detailed "deep" feel of reference image 2, but restrained |
| Lens shape | Soft superellipse, radius 72–140 dp, smoothstep edge 40 dp wide | Organic, not a hard circle |
| Motion curve | Critically damped spring (ζ = 1, ~380 ms settle). No overshoot except on the tap pulse | Calm and deliberate |

## 4. Choreography: the field shows the real agent state

Everything maps to states Cyclone already has (`OverlayChromeState`, session kernel phases,
GATE). No new agent logic is needed, only a read-only stream of **attention events**.

| Agent phase | What the field does | Signal source |
|---|---|---|
| **Wake** (task accepted) | A single hairline of digits runs up from the Aurora pill at the bottom center to the top of the screen in 450 ms, then fades. The field is now "on". | Chrome → LIVE |
| **Observe** | The lens makes one slow **scanline sweep** top→bottom (≈ 700 ms), a thin band of digits flipping as it passes, like a document scanner. Afterward the lens rests on the most salient region. | Observation start/end, page fingerprint |
| **Think** (waiting on model) | The lens *breathes* in place: radius ±8 %, 2.4 s period. Digits inside roll slowly. A faint Lissajous drift (a = 3, b = 2, ~20 dp amplitude) keeps it alive without wandering. | Provider call in flight |
| **Target** (plan chose a node) | The lens glides along a smooth path to the chosen node's bounds and **morphs from circle to that node's rounded rect**, digits aligned to its edges like a bounding-box readout. | Planned action target bounds from the a11y node |
| **Act** (tap/type/scroll) | Tap: one ring of digits ripples outward (320 ms) + optional `LOW_TICK`. Type: digits roll only along the text field's baseline. Scroll: the column flow inside the lens follows the scroll direction and velocity; this is the one moment it really looks like "rain". | Executed tool + gesture vector |
| **Verify** | The lens pulls in, the digits settle and **freeze** for 200 ms, then turn off one by one from the edges in: a visual "checksum". | Verification pass |
| **Recover** (verify failed) | The digits scramble briefly (fast roll, 150 ms) and the lens widens: "looking again". Never red, never alarming. | Recovery ladder step |
| **GATE** (needs the user) | The field **stops**. All motion freezes, the lens slides onto the confirm card and holds a steady warm tint. Stillness is the call to action. | GATE state |
| **User takes control** | Everything fades out in 180 ms. It must never compete with the user's hand. | Take control |
| **Done** | The finale: every lit digit falls downward with slight per-column delays (≈ 600 ms, gravity easing), the only full-width moment of the whole task, and dissolves into the Aurora pill. | Task complete |
| **Background workspace session** | No lens on the foreground app, because the user is doing something else. Only a 2 dp **edge filament** of digits slowly travels around the screen perimeter (40 s per lap) as ambient proof of life. | Background session active |

### 4.1 The digits mean something

This is what separates it from a screensaver. The numerals are not random:

- In **Observe**, the scan band shows hex from the new **page fingerprint** that the Stage 1
  fingerprint ladder already computes.
- In **Target**, the digits around the node are its stable **node hash / skill-step id**.
- In **Verify**, the frozen "checksum" is the first 8 hex chars of the verified effect's digest.

To a normal user this is just beautiful texture. To a developer who screen-records a run, the field
**is** a trace, and it can be matched against Brain → Recent runs. Easter egg: long-press the Aurora
pill during a run to freeze the field and show the plaintext meaning in a tooltip.

### 4.2 Motion quality rules

- The field never moves in more than one place at a time (one lens, one ripple).
- No motion lasts longer than 3 s without a state change, which rules out idle loops that look
  like an animation playing.
- Glyph changes are **rolls** (the value steps through intermediate values over 3–4 ticks), never
  hard swaps. That is what makes it feel "sharp and satisfying" and not "noisy".
- Freeze on GATE and fade on user touch. The user always outranks the effect.

### 4.3 Where it never draws

A mask of **exclusion rects** (max 8, passed as a uniform array) removes the field from:
Cyclone's own chrome, the IME window, status/nav bars (unless the target lives there), and any
node flagged as password or `FLAG_SECURE` content. Text the user needs to read stays clean.

### 4.4 Adaptive to light and dark content

On each observation (screenshot already in memory), compute mean luminance per 8×16 coarse tile
(128 floats, trivial cost) and upload it as a tiny texture. The shader chooses additive glow over
dark tiles and a soft subtractive ink over light tiles, so the field reads the same over Maps,
Gmail white, or a black video player.

## 5. Glyph set

Keep it technical and modern, not cinematic:

- Primary: `0–9 A–F` (hex matches the meaning in §4.1).
- 5 % "texture" glyphs: `· : ⁄ ∙ ⌐` plus 4 custom Cyclone strokes (fragments of the logo's spiral),
  so a sharp-eyed user notices the rain is made of little Cyclone pieces.
- No katakana. It is the movie's signature, and we want our own.

## 6. Rendering architecture

```
AgentAttentionBus  (SharedFlow<AttentionEvent>, read-only tap on session kernel + chrome machine)
        │   Wake · Observe(fingerprint) · Think · Target(bounds, hash) · Act(kind, vector)
        │   Verify(digest) · Recover · Gate(bounds) · Handoff · Done · Background
        ▼
TraceFieldChoreographer  (pure Kotlin state machine, unit-testable, no Android deps)
        │   → FieldFrame(lensCenter, lensShape, radius, rippleT, scanY, mode, seedHex, exclusions)
        ▼
TraceFieldWindow  (TYPE_ACCESSIBILITY_OVERLAY, full screen, not touchable, not secure)
        └─ View with onDraw → canvas.drawPaint(paint with RuntimeShader)
             uniforms: time, lens*, ripple, scanY, glyphAtlas (BitmapShader),
                       luminanceGrid (BitmapShader), exclusions[8], seed[4]
        ▲
CaptureGate  (observer → hide → ack → capture → restore)
```

Following the repo's pattern, the choreographer is pure and gets JVM tests like
`OverlayChromeMachineTest`: each event sequence produces a deterministic frame timeline.

### 6.1 AGSL sketch (core of the shader)

```glsl
uniform shader atlas;        // ALPHA_8 glyph strip, 16+ glyphs, cell = glyphPx
uniform shader lum;          // coarse luminance grid
uniform float2 res;
uniform float  t;            // seconds
uniform float2 lensC;        // px
uniform float2 lensHalf;     // px, half-size of rounded-rect lens (circle when equal)
uniform float  lensR;        // corner radius px
uniform float  ripple;       // 0..1, -1 when idle
uniform float  cellW, cellH, glyphPx;
uniform half4  tint;
uniform float  seed;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7)) + seed) * 43758.5453); }

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 xy) {
    float d = sdRoundRect(xy - lensC, lensHalf, lensR);
    float lens = 1.0 - smoothstep(0.0, 110.0, d);           // soft falloff
    if (ripple >= 0.0) {
        float ring = abs(length(xy - lensC) - ripple * 420.0);
        lens = max(lens, (1.0 - smoothstep(0.0, 18.0, ring)) * (1.0 - ripple));
    }
    if (lens < 0.004) return half4(0);                       // early out: most pixels

    float2 cell = floor(xy / float2(cellW, cellH));
    float2 inCell = xy - cell * float2(cellW, cellH);
    // split-flap roll: value advances at ~12 Hz, phase offset per cell
    float tick = floor(t * 12.0 + hash(cell) * 12.0);
    float g = floor(hash(cell + tick * 0.013) * 16.0);       // glyph index 0..15
    float a = atlas.eval(float2(g * glyphPx + inCell.x, inCell.y)).a;

    float twinkle = 0.55 + 0.45 * hash(cell + floor(t * 3.0));
    float k = a * lens * twinkle;
    return half4(tint.rgb * k, k) * tint.a;                  // premultiplied, additive-friendly
}
```

The shipped version adds the three depth layers, exclusion masking, luminance-adaptive blending,
and seeding the glyph choice from the real `seedHex` instead of `hash` inside the lens core.

### 6.2 Performance budget (Pixel 8 class)

- Target: **< 0.6 ms GPU per frame** and **< 2 % extra battery per hour of active agent time**.
- Early-out outside the lens means over 90 % of fragments do one SDF and return.
- Frames are redrawn only while the choreographer is animating. When the field is still (GATE
  freeze, idle think plateau), stop invalidating.
- Battery saver / thermal status ≥ `THERMAL_STATUS_MODERATE`: drop to edge-filament mode at 30 fps.
- Measure with `adb shell dumpsys gfxinfo com.cyclone.mobile framestats` and a Perfetto trace
  before shipping. The physical Pixel 8 is still UNVERIFIED on this tree, so this budget is a
  hypothesis until it is measured on device.

## 7. Settings and product surface

`Settings → Appearance → Working indicator`:

- **Trace Field** (default for new installs once validated)
- **Edge only** (just the perimeter filament)
- **Aurora only** (today's behavior)
- Intensity slider (50 % – 150 %) and "Haptic ticks" toggle

A one-time onboarding moment: the first time a task runs, the Done-rain finale plays at 1.5× length
with a single line under the Aurora pill: *"That was Cyclone reading your screen."*

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Field pollutes agent vision | Capture gate + no `FLAG_SECURE` + a11y exclusion (§2.1), enforced by tests |
| Users read it as "hacked phone" or malware | No green, no full-screen rain except the 600 ms finale, always paired with the Aurora pill and the ongoing task notification |
| Photosensitivity | No flashing: max luminance change 10 %/frame, no full-screen flicker, fully static under Remove animations |
| OEM overlay quirks (Samsung/Xiaomi throttling) | Feature flag + automatic fallback to Edge-only if dropped frames exceed 5 % over a run |
| Play policy on accessibility overlays | The field is purely visual feedback for an agent the user explicitly started. Disclose it in the accessibility-use description |

## 9. Suggested build plan

1. **Spike (1–2 days):** `TraceFieldWindow` + static AGSL shader with a lens that follows your
   finger in a debug activity. Validate glyph crispness and cost on device.
2. **Choreographer:** pure state machine + JVM tests; wire `AgentAttentionBus` from existing chrome
   and session kernel events.
3. **Capture gate:** integrate with the observation path; add the policy and observation tests.
4. **Meaningful digits and adaptive luminance.**
5. **Polish pass:** haptics, finale, settings, reduced motion, thermal fallback, device perf
   evidence under `docs/evidence/`.

## 10. What shipped in 5.0.0-alpha.5.dev1

Code: `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/tracefield/`.

| Piece | File | Notes |
|---|---|---|
| Choreographer (pure state machine) | `TraceFieldChoreographer.kt` | Wake, Observe scan, Think breathing, Target lens-morph, Act ripple/flow, Verify freeze, Recover scramble, Gate freeze + warm tint, Handoff fade, Done rain, Stop fade, background edge filament. Reduce-motion and Edge-only modes. |
| Presence and capture policy | `TraceFieldPolicy.kt` | Task and chrome state are mapped to transitions. The window is never `FLAG_SECURE`. `TraceFieldCaptureGate` hides the field before any full-display capture. |
| AGSL shader | `TraceFieldShader.kt` | Glyph atlas sampled 1:1, column rhythm, sparse cells, far depth layer at 24 %, perimeter filament. Compile-checked and rendered offline with Skia's SkSL compiler. |
| Window + view | `TraceFieldRuntime.kt`, `TraceFieldView.kt` | `TYPE_ACCESSIBILITY_OVERLAY`, not touchable, INVISIBLE whenever there is nothing to draw. It stops redrawing while frozen. It turns itself off if the shader fails. |
| Signals | `CycloneAccessibilityService` | `observe()` → Observe(fingerprint); `click()` → Target(node bounds, node path) + Act; coordinate tap/long-press/swipe, `scroll()` and typing → Act; a failed click → Recover. Foreground display only. |
| Capture gating | `CycloneAccessibilityService.takeScreenshot`, `LiveVisionRuntime.capture` | Full-display screenshots wait for a frame without the field (120 ms failsafe). Live MediaProjection frames are accepted only if they were captured after the hide. The `takeScreenshotOfWindow` path already excludes overlays. |
| Setting | AI settings → Working indicator | Trace Field / Edge only / Off, plus **Preview**. Battery Saver forces Edge only. |

### Styles (AI settings → Working indicator → Style)

Android composites overlays with plain alpha, so real Difference/Burn blend modes against other
apps are impossible. Instead `TraceFieldBackdrop` shrinks the screenshot Cyclone already takes for
each observation into a 16×32 colour grid (memory only, never persisted, no extra capture) and
the shader reads it.

| Style | Look | How |
|---|---|---|
| **Obsidian** (default) | Outlined digits that glow on dark content and turn to ink on light content; a 1.5 px red/blue fringe on the lens rim | Halo atlas row, backdrop luminance, per-channel offset sampling on the rim only |
| **Forge** | Digits appear white-hot, cool to Cyclone blue, fade as embers; the previous digit lingers as an afterglow | Heat ramp from each digit's age; ghost from the previous tick's glyph |
| **Chameleon** | Takes on the colour of the app around the target; soft depth of field | `TraceFieldColor.accentFrom` on the backdrop colour at the target; blurred atlas row for the far layer |
| **Signal** | Digits break into print dots toward the lens edge; the finishing rain is iridescent | Halftone mask from lens distance; hue ramp during rain |

Deferred from the concept: haptic ticks, custom Cyclone glyph
strokes, per-node exclusion rects (only the Aurora pill area is excluded), and the long-press
"what do these digits mean" tooltip. Physical Pixel 8 frame-time and battery measurements are
still UNVERIFIED.

## 11. alpha.6: living aurora and focus contrast

Owner feedback on alpha.5 on a real phone: the lens read as a carved-out block in the middle ~20 % of
the screen, and while Cyclone thought, opened apps or clicked through the accessibility tree (no
gestures) the field was a still circle.

- **Two broad gradients** carry the digits far into the page at near-invisible opacity: an oval
  around the lens (natural, not rectangular) and a soft vertical band reaching about half the screen.
- **Ambient aurora**: domain-warped value noise shaped into slow curtains flows across the whole
  screen (faint floor ~5 %, patches up to ~39 % of peak). It uses a seed-free hash, so a new page
  fingerprint never makes it jump.
- **Focus** (0 to 1) from the choreographer: target, act and verify = 1, recover 0.8, observe 0.6,
  gate 0.5, otherwise 0.12. Focused: the sharp rounded-rect lens plus the oval, with the aurora
  dimmed by 55 %. Thinking: a wide diffuse oval that drifts on incommensurate 19 s/7.3 s/23 s/8.9 s
  periods, so it never visibly repeats. Focus arrives in ~0.12 s and relaxes over ~0.6 s.
- **Cost**: glyph lookup now runs before any field maths, so the ~90 % of pixels without a digit
  still exit after a few hashes and one texture read. The aurora (4 noise lookups) runs only on
  digit pixels. Still unmeasured on a device.
