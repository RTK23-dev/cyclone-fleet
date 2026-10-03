# Cyclone Tilt Glass — design principles

Status: **shipped** in the overlay in Mobile 5.0.0-alpha.43.dev1 and in the Cyclone app's Ask page and home task in
5.0.0-alpha.43.dev2. The design was agreed over 13 Overlay Studio rounds (plan 27, `Cyclone V5 plan/27-overlay-redesign.md`).
Physical Pixel 8 acceptance is UNVERIFIED.

This file is the reference for any new Cyclone surface: what the glass is, the rules it follows, the exact values,
and the ideas we tried and dropped. [Teal Matrix](CYCLONE_TEAL_MATRIX.md) is still the calm material for ordinary app
screens (lists, settings, Brain). Tilt Glass is for the surfaces where **Cyclone is acting or talking with you**: the
Ask bar, the task, the moments that need you, and the small status pills around them.

> One dark teal glass, lit by how you hold the phone. Words float on soft pills; only the things you press have a
> lit edge.

## 1. Principles

1. **One material.** Every task surface is the same dark teal glass (`Modifier.tiltGlass`). Hierarchy comes from size,
   position and type, never from a second material or a second palette.
2. **Light is physical and shared.** One light (`GlassLight`) lights every glass surface on screen, so they agree.
   The light comes from how the phone is tilted. The rim facing it catches a sharp line, the opposite rim a weaker
   one, as real glass refracts. At rest the light comes from the upper left.
3. **Sharp, not thick.** Edges are thin, crisp lines, never a wide soft highlight band. Panels get a narrower, sharper
   shine than small pills.
4. **Depth from the dots.** Fine halftone "fingerprint" dots hug the inner edge of large surfaces. On the lit side they
   are crisp and bright; on the far side they swell and fade, as if out of focus. They carry the depth; nothing else
   needs to.
5. **Words sit on veils.** Text never sits directly on the dots. It sits on a soft, edge-less, fully rounded veil pill,
   so it stays sharp and readable whatever the light does.
6. **Lit edges mean "press me".** Only round buttons, action capsules (approve, send, stop) and the plane pill get a
   lit rim. Text pills, chips and the Ask field do not. A lit edge is an affordance, not decoration.
7. **Everything answers a touch.** Every button flashes its rim and blooms a soft teal glow when pressed, about 0.75 s.
8. **Still unless it means something.** Nothing loops for looks. Motion is reserved for real state: a task working
   (spinner, ring, sliding progress), voice listening (the orb), light following your hand. No flicker, no idle
   shimmer, no streaming particles.
9. **Soft geometry.** Bars and pills are fully rounded capsules. Cards have 30 dp corners, inner veils 22 dp. Nothing
   has a hard corner.
10. **Show where the work is.** Real app logos (the current app in front) replace generic Cyclone branding wherever a
    task works in an app. Cyclone's own mark appears only when there is no app (idle, a fresh ask).
11. **The message is the hero.** When Cyclone needs you, the thing you are approving (the text to send, the action to
    take) is the largest, brightest element. Buttons follow it.
12. **Collapse, don't hide.** A surface shrinks through heights (card → island → notification) instead of vanishing;
    each smaller height keeps what is essential.
13. **Respect the owner's settings.** With Android animations off, the light rests and nothing animates. Every control
    has a content description.

## 2. The material

`Modifier.tiltGlass(cornerRadius, dots = true, thin = false, seeThrough = 1f)` in
`apps/mobile/.../ui/overlay/glass/TiltGlass.kt`. It draws, in order:

| Layer | Value |
| --- | --- |
| Body | vertical gradient `#10383E` → `#062228` (55%) → `#08282E`, alpha 0.94–0.95 × `seeThrough` |
| Sheen | linear teal `#83DBD7` from the light side, 16% × power, fading to clear by 45%, 6% returning on the far edge |
| Dots | hex grid, 4.3 dp step, band of `min(30 dp, 46% of height)` inside the rim, colour `#83DBD7`, alpha ≤ 0.85 |
| Hairline | 0.8 dp, `#A0E2DE` at 16% |
| Rim glow | 3.2 dp, `#83E6DE` at 30% × intensity × power (panels only; skipped when `thin`) |
| Rim line | 1.15 dp panels / 0.6 dp thin, `#F0FFFD`, up to 100% (80% thin) |

Shine along the rim (`GlassOptics.rimIntensity`), with `face = cos(normal − lightAngle)`:

- panels: `max(0,face)^5.5 + 0.7 · max(0,−face)^7.5`, a narrow sharp line plus a weaker opposite one;
- thin pills: `max(0,face)^3.2 + 0.7 · max(0,−face)^4.5`, a gentler, wider arc.

Dots (`GlassOptics.dotAlpha/dotRadius`), with `fall` 1 at the rim and 0 at the band's inner edge:

- lit side: `alpha ≈ (0.06 + 0.7 · lit^1.3 · power) · fall^0.8`, small crisp radius;
- far side: radius grows up to 1.9× and alpha drops by up to 45%, so they read as blurred.

The light (`FollowPhoneLight()` + `GlassOptics.lightFromGravity`):

- gravity sensor (accelerometer fallback) at `SENSOR_DELAY_UI`;
- the grip you start with is the baseline and re-centres slowly (0.4% per reading), so the light follows movement,
  not posture;
- tilt / 3.2 m/s² moves the light from rest (−0.45, −0.70), clamped to ±1, eased at 22% per reading;
- power = light distance clamped to 0.45–1, so the glass never goes fully dark;
- reads happen in the draw phase: a light change redraws, nothing recomposes;
- call `FollowPhoneLight()` once per screen that shows tilt glass. It rests when `ANIMATOR_DURATION_SCALE` is 0.

Variants:

| Variant | Call |
| --- | --- |
| Panel (card, owner card) | `tiltGlass(30.dp)` |
| Bar / island | `tiltGlass(33.dp)` at 66 dp high |
| Small pill (plane pill, outcome line) | `tiltGlass(17.dp, dots = false, thin = true)` at 34 dp high |
| Idle bubble | 58 × 50 dp oval, `tiltGlass(25.dp, dots = false, thin = true, seeThrough = 0.42–0.72)` |

### 2b. Smoke palette (R3, the AI screen)

`tiltGlass(…, palette = GlassPalette.SMOKE)` keeps every optic above and swaps the teal for graphite and white:
body `#1C1E23` → `#0F1013` → `#14161A`, white sheen, hairline and rim, rim glow `#E8ECF2`, dots `#D6DCE4`. The AI
screen provides it through `LocalGlassPalette`, so its task card, island, owner card and sheets carry no teal. The
floating overlay never sets it and stays teal. The AI screen's own surfaces are smoked glass (a live blur of the
rain, see `docs/design/redesign/rounds/R3-ai-screen.md`), not Tilt Glass.

## 3. Palette and type

| Token | Hex | Use |
| --- | --- | --- |
| `GlassInk` | `#E0F5F3` | titles, primary text, icons |
| `GlassMuted` | `#A6CCCA` | steps done, secondary lines |
| `GlassDim` | `#6F9896` | meta line |
| `GlassTeal` | `#83DBD7` | working, progress, current-app ring, cursor |
| `GlassWarm` | `#E9C78B` | needs you |
| Veil | `#04181D` at 30% | text pills (`veilPill()`) |
| Hero text | `#F2FFFD` | the approval message |
| Primary capsule text | `#052528` | on the teal primary button |

| Role | Size |
| --- | --- |
| Card title | 23 sp bold, 28 sp line, max 2 lines |
| Approval message | 20 sp medium, 27 sp line, on a white 7.5% pill |
| Ask field | 17 sp, 22 sp line |
| Island first line | 15.5 sp semibold; second 12.5 sp muted |
| Capsule button | 15 sp |
| Steps | 14.5 sp (current in ink, done in muted) |
| Plane word | 14 sp semibold |
| Status chip | 13.5 sp semibold |
| Meta line | 12 sp monospace, uppercase, 0.3 sp tracking |

## 4. Components (`ui/overlay/glass/GlassKit.kt`, `ui/overlay/OverlayGlassStack.kt`)

| Component | What it is |
| --- | --- |
| `GlassRoundButton` | 46 dp (40 dp in headers) circle, faint white radial fill, `litRim()` whose bright point faces the light, `pressGlow` |
| `GlassCapsuleButton` | ≥ 44 dp capsule, radius 22, `litRim(cornerRadius = 22.dp)`, `pressGlow`; `primary` = teal fill |
| `veilPill()` | the edge-less text pill |
| `VoiceOrbButton` | a normal round button until listening; then a living teal orb: a lit sphere whose highlight faces the light, a 7 s swirl inside, and a soft 2.4 s breath of light that rises into the bar and fades to nothing before its edge (every glow is a radial gradient that reaches zero, so nothing has an edge); tight below. The mic turns dark (`#052528`) on the orb. One control in both states. Voice starts as the finger goes down: tap to talk, or hold ≥ 500 ms and let go to finish (push to talk); presses within 400 ms are one press, and a stop only counts after 800 ms of listening (`VoicePress`) |
| `GlassToolTile` / `GlassToolRow` | the tools drawer's controls: a tile is a lit capsule (radius 22) with the icon on a soft disc; a row is a lit round button (40 dp) and its label, and pressing the row flashes the button |
| `TiltGlassTheme` / `LocalTiltGlass` | the glass palette for Material text inside a glass surface; shared Teal Matrix controls (model picker, choice bar, menu trigger, text action) draw their glass version inside it: veils for trays, lit capsules for what you press |
| `AppLogo` / `AppLogoStack` | real launcher icons, round, dark 2 dp ring; current app 34 dp in front with a teal ring, earlier ones 28 dp at 82% overlapping by 9 dp (last 3 distinct apps, never Cyclone itself) |
| `GlassSpinner` | dotted ring, one turn per 1.6 s |
| `GlassProgress` | 3 dp line; sliding segment (1.4 s) when the length is unknown |
| `PlaneRow` | the one-word plane pill (*Screen*, *Background*, *Moving*, *Waiting*), right-aligned, with the outcome line beside it for 4 s when a move did not happen |
| `OverlayWorkCard` | panel: grabber (32 × 3 dp), inner veil (radius 22, 14 dp padding), header (logos · status chip · collapse), title, progress, last 3 steps, meta line |
| `WorkIsland` | 66 dp bar: current app logo in a turning ring (42 dp), two centred lines on a full-round veil, 2 dp progress along the bottom |
| `OverlayOwnerCard` | panel: where ("Send to **Sam** in WhatsApp"), then the message as hero, then lit capsules (Send, Change, Not now) |
| `GlassComposerBar` (in-app) / `OverlayAppleComposerBar` | 66 dp bar: + · Ask field on a veil filling the space · voice orb · send |
| Tools drawer (`OverlayToolsSheet`, in-app `InAppGlassSheet`) | a working card: panel glass (radius 30), the card's grabber, one inner veil (radius 22); tiles, rows and the model page on it |

## 5. Layout: the stack

Top to bottom, right-aligned small pill first:

```
                [ Background ]      plane pill, 34 dp
                    10 dp
┌──────────────────────────────┐
│  (logos)  Working        ⌄   │    card, radius 30
│  Title                       │
│  ───── progress              │
│  ✓ step  ✓ step  ◌ step      │
│  STEP 3 OF 8 · CALENDAR → …  │
└──────────────────────────────┘
                    14 dp
(  +  (     Ask Cyclone     )  🎙  ➤ )   bar, 66 dp, radius 33
```

`OverlayStackGeometry`: `PILL_GAP_DP = 10`, `CARD_GAP_DP = 14`, `PILL_HEIGHT_DP = 34`, `CARD_RADIUS_DP = 30`,
`BAR_RADIUS_DP = 33`, `BAR_HEIGHT_DP = 66`. Keep new stacks on these numbers.

Heights, one gesture (drag down to go smaller):

1. **Card**: the full stack.
2. **Island**: the card folds into a bar the size of the Ask bar; the plane pill stays above it as its own capsule.
3. **Notification only**: the live notification carries the task; its *Show* action brings the island back.
4. **Idle**: the small see-through oval with Cyclone's mark.

When a task needs you, the owner card takes the card's place and rises, whatever height you were at.

## 6. Motion

| Motion | Timing |
| --- | --- |
| Press flash | rim to full in 130 ms, glow fades over 620 ms |
| Height / content swap | fade in 220 ms, out 140 ms (`AnimatedContent`) |
| Outcome line | fade 200 ms, shown 4 s |
| Drag | the stack follows the finger; release past the threshold commits the next height |
| Light | eased per sensor reading; no animation clock |
| Working | spinner 1.6 s/turn, island ring 1.4 s/turn, indeterminate progress 1.4 s |
| Voice | orb swirl 7 s, breathing 2.4 s, only while listening |

## 7. Words

- Status chips: *Working*, *Needs you*, *Done*, *Couldn't finish*.
- The plane pill is one word. Its reason is a sentence beside it, never silence: "Couldn't move WhatsApp: it stayed
  on your screen".
- Meta line: `STEP 3 OF 8 · CALENDAR → WHATSAPP`; drop parts you don't know.
- Island: what Cyclone does now, then the task with "· 3 of 8".
- Approvals: when the request quotes text, the quote is the message and the rest is the line above it.
- Copy lives in pure objects (`OverlayGlassCopy`) so it is unit-tested apart from the composables.

## 7b. Drive (plan 32): the AI button and AI mode

- **The AI button** (`ui/overlay/DriverButton.kt`) replaces the idle bubble in Driver mode:
  - the drive orb on idle-bubble glass (`tiltGlass(size / 2, dots = false, thin = true, seeThrough = 0.78f)`), 84 dp
    by default (72 / 96);
  - a 16 dp margin around it holds the glow;
  - a thin teal ring turns around the glass while a task works;
  - the glow turns `GlassWarm` when Cyclone needs the owner.
- **The orb** (`DriveOrb`) is the voice orb's lit sphere, with the same gradient, swirl and highlight towards
  `GlassLight`. Its motion follows real state only:
  - calm breath (2.8 s) at rest;
  - swells with the owner's voice while listening, with one ring riding out on loud speech;
  - fast swirl (1.3 s) while thinking;
  - pulses with Cyclone's voice while speaking;
  - still when Android animations are off.
- **AI mode** (`ui/overlay/AiModeOverlay.kt`) is a `tiltGlass(30.dp)` panel rising from the bottom (slide + fade,
  260 ms). Top to bottom:
  - the status word (12 sp, spaced capitals; warm when Cyclone needs you);
  - the owner's words on a veil pill (`GlassMuted`, 20 sp) and Cyclone's line on a veil pill (`GlassInk`, 23 sp),
    each two lines at most;
  - the 136 dp orb;
  - one 56 dp **Stop** (`GlassCapsuleButton` primary), with **Not now** beside it when Cyclone asks.
- **The dim:** a non-touchable window darkens the screen (46 %, deeper at the bottom), only while a request is live.
- **Copy and state** live in `voice/VoiceCopy` and `voice/VoiceFace` (pure, tested); the composables only draw them.

## 8. Tried and dropped

These came up in the design rounds and were rejected. Don't bring them back without a reason:

- **Circular fingerprint whorls** on the panels: too busy, inconsistent between surfaces.
- **A thick, light top edge**: read as plastic. Replaced by thin sharp rim lines.
- **Streaming or tunnelling dots** ("warp", infinite stream): too fast and distracting. Replaced by still dots whose
  focus follows the light.
- **Anything that loops for looks** (a pulsing Screen pill, shimmering pills): read as flicker.
- **Lit edges on everything**: the Ask field, text pills and chips lost their calm. Lit edges are for pressables.
- **A flat one-colour blur for voice**: looked dead next to a real orb. The orb has depth, swirl and light that rises
  upwards.
- **Cyclone logo and "Cyclone Mind" title on the card**: said nothing. The app logos say where the work is.
- **A one-tap stop square on the island**: too easy to hit by accident. The island keeps pause, and hold or double
  tap to stop.

## 9. Applying it to a new surface

1. Is Cyclone acting or talking with the owner here? Use Tilt Glass. Otherwise use Teal Matrix.
2. Start from `tiltGlass` with the variant in §2; don't draw your own glass.
3. Put every text block on a veil (`veilPill()` or the 22 dp inner veil).
4. Give lit rims only to things you press: `GlassRoundButton`, `GlassCapsuleButton`. Add `pressGlow` to any custom
   pressable.
5. Use the tokens in §3 and the geometry in §5; no new colours or radii without updating this file.
6. Show app logos when the surface is about an app.
7. Call `FollowPhoneLight()` once on the screen.
8. Animate only real state; check the screen with animations off.
9. Keep button actions going through Task Kit (`TaskCommands`), never a direct engine call.
10. Keep copy in a pure, tested object.

Performance notes: dot positions are computed once per size in `drawWithCache`; each frame only buckets dots by
alpha × radius (16 × 8 levels) and draws each bucket with one `drawPoints` call. Rim segments (260) are precomputed.
Keep new glass inside `drawWithCache` and read `GlassLight` only in the draw phase.

Prototype: the design rounds used the Overlay Studio web prototype. Iterate new designs there (or a similar HTML
mock) before building the APK.
