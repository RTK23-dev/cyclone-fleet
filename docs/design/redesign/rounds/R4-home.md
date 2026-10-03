# R4 · Home — the AI page's style, without the video

Status: **agreed** by the owner ("make the redesign in this same style without the video for the home screen",
2026-09-29). Home moves to the R3 material: the Cyclone rain behind dark smoked glass. Unlike the AI page, Home
draws the rain alone (the drifting dome), never the zoom scene.

## What changes

- **Background:** `AskGlassPage(withScene = false)`: the rain shader, recorded as the backdrop the glass blurs, the
  scrim, the shared shine and the neutral glass palette. No decoder is created.
- **Header:** `AskHomeHeader`: the burger (Settings) and the Cyclone mark (opens Ask Cyclone) as glass chips, the
  Cyclone word between them.
- **Greeting:** `AskGreeting` with the readiness line; when phone control is not ready, a glass status chip
  (Setup / Repair) opens Settings.
- **Quick actions:** four `AskChip`s (Plan my day, Research a topic, Open an app, Create a routine) with a detail
  line. The first three only fill the Ask bar; Create a routine opens Routines.
- **Current task:** `InAppTaskStack` on the smoke palette.
- **Recent activity** and **Your routines:** one `AskGlassList` each, rows with the app icon on a glass tile, the
  status line and a state mark (done tick, working ring, needs-you !). "Open chat" and "See all" stay.
- **Ask bar:** `CycloneHomeComposer` becomes the AI page's smoked bar (dots and shine) when it sits on the rain.

## Build contract

| Element | Compose | File |
| --- | --- | --- |
| Page shell | `AskGlassPage` | `ui/v32/ask/AskGlassKit.kt` |
| Header, greeting, chips, lists, rows, tiles, state marks, status chip | `AskHomeHeader`, `AskGreeting`, `AskChip`, `AskSectionHeader`, `AskGlassList`, `AskListRow`, `AskTile`, `AskStatePip`, `AskStatusChip` | `ui/v32/ask/AskGlassKit.kt` |
| Home | `V32HomePage`, `HomeQuickActions`, `HomeRecentRow`, `HomeRoutineRow` | `ui/v32/CycloneV32App.kt` |
| Ask bar | `CycloneHomeComposer` smoked branch | `ui/v32/CycloneHomeComposer.kt` |

Behaviour stays: the same data (readiness, the live task, recent activity, routines), the same actions, quick
actions that only fill the bar, requests handed to Ask Cyclone through `V39AiChatSessionRuntime.pendingRequest`.
Pinned by `HomeR4ContractTest`.
