# R0 · Home — example round

Status: **proposal** (not agreed, not built). This is the worked example of the loop in
[../README.md](../README.md): three boards on the canvas, and the contract that would build either proposal.

| Board | Idea |
| --- | --- |
| Now (dev3) | Home as shipped in 5.0.0-alpha.43.dev3: centred greeting, 2 × 2 quick actions, the live task on glass, recent activity, a Teal Matrix Ask capsule and the tab bar. |
| A · task first | The running task is the hero at the top. Quick actions shrink to one row of veil chips. "Earlier today" is one matte list. The Ask bar and the tabs form one glass dock. |
| B · ask first | The Ask bar is the hero in the middle with the actions as chips under it. Routines are two cards. The running task folds into the island above the tab bar, with the plane pill above it. |

Sample content on the boards ("Send Sam tonight's dinner plan", "Booked the 18:30 table", routine names) is
**sample**, not copy: the build shows the real task and history.

## Build contract (per element)

| Element | A | B | Compose | Tokens | Kotlin |
| --- | --- | --- | --- | --- | --- |
| Greeting | left, 26 sp bold + status line 14 sp muted | centred, 15 sp muted over 30 sp question | `CyclonePageHeader` (A: `centered = false`) | Ink `#E0F5F3`, Muted `#A6CCCA` | `ui/v32/CycloneV32App.kt` `V32HomePage` |
| Status line | "One task running · 2 done today" | — | new `HomeStatusCopy.line(...)` (pure, tested) | Muted | new `ui/v32/HomeStatusCopy.kt` |
| Settings | lit round button 44 dp | app bar menu (as now) | `GlassRoundButton` / `CycloneMatrixAppBar` | lit rim | `overlay/glass/GlassKit.kt` |
| Live task | full stack at top: plane pill, card | island above tabs, plane pill above it | `InAppTaskStack` (A) / `WorkIsland` + `PlaneRow` (B) | card 30 dp, bar 66 dp / 33 dp, gaps 10 / 14 dp | `ui/v32/InAppGlass.kt` |
| Quick actions | one row of chips, 40 dp, veil | centred wrap of chips | new `GlassChip` (veil pill, no rim: text, not a pressable edge) | Veil `#04181D` 30 %, hairline teal 10 % | new in `overlay/glass/GlassKit.kt` |
| Recent | "Earlier today", one matte card, 56 dp rows, mint check | — (routines instead) | `CycloneMatrixCard` + `HomeRecentRow` | Success `#4FD9B4` | `ui/v32/CycloneV32App.kt` |
| Routines | — | two cards, 22 dp | `CycloneMatrixCard` + `HomeRoutineRow` variant | Tile `#0E3B41` | `ui/v32/CycloneV32App.kt` |
| Ask bar | Tilt Glass bar in the dock | Tilt Glass bar mid-screen | `GlassComposerBar` replaces `CycloneHomeComposer` | bar 66 dp / 33 dp | `ui/v32/InAppGlass.kt`, `CycloneHomeComposer.kt` |
| Tabs | 48 dp row inside the dock | matte tab bar (as now) | `V32Destination` bar | teal active, muted rest | `ui/v32/CycloneV32Components.kt` |

Buttons and their actions (unchanged by either proposal):
- chips prefill the Ask bar (`seed`), never send;
- task controls go through Task Kit: collapse (local), pause `TaskCommand.Pause`, resume `TaskCommand.Done`,
  stop by hold or double tap `TaskCommand.Stop`, plane pill `PlanePillModel.tap`;
- tabs navigate.

Not shown by CSS, built from the Kotlin: tilt light on every rim, press glow, fingerprint dot focus, island ring
turn. Guards to update when built: `CycloneV39AiChatPageTest`, `CycloneConversationSystem472Test`,
`scripts/ci/mobile_product_guard.py` (home composer strings).
