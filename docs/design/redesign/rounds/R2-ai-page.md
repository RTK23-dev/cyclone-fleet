# R2 · AI page — cleaner recent missions

Status: **proposal**. Uses R1's liquid glass navigation, with the Chat circle selected.

**The problem (dev3):** `CycloneRecentMissions` draws a full card for each mission: the goal (2 lines), status and
summary (3 lines), the learned note, **Learn / Save skill** and **Remove / Resume** as two rows of 44 dp buttons.
That is about 220 dp per mission. The empty state shows 2 and the chat shows 4, below every conversation.

| Board | Idea |
| --- | --- |
| Now (dev3) | As shipped. |
| A · one-line recents | Clean, centred start (orb, greeting, three quick-action chips). "Recent" is three 52 dp rows: a status dot, the goal on one line, the time, or **Resume** when it can continue. "All missions" opens the full list. |
| B · continue pill | Only the greeting. One **Continue** pill above the Ask bar for the newest resumable mission. Everything else sits behind a History button in the header, which gets a dot when something can resume. |
| Mission sheet | Tapping a mission (A's row, B's pill or History) opens a Tilt Glass sheet with the status chip, meta line, goal, summary, **Resume** (primary) · **Learn from this run** · **Save skill** (completed only) and **Remove from history**. |

Both proposals take the missions out of the chat thread entirely (`if (liveMission == null) item("missions")` goes).
The live mission keeps its glass card in the thread.

## Build contract

| Piece | Compose | Notes |
| --- | --- | --- |
| Recent rows (A) | new `MissionRow` in `CycloneMissionPanel.kt` | Dot colour from `MissionStatus`: waiting/stopped/paused/interrupted `#E9C78B`, completed `#4FD9B4`, failed/not possible `#FF7A74`. The trailing slot shows Resume when `status.resumable` and no mission is live. |
| Continue pill (B) | `tiltGlass(20.dp, dots = false, thin = true)` pill | Newest resumable mission only; hidden when none. |
| History (B) / All missions (A) | a full-height sheet listing `MindMissions.history` as `MissionRow`s | Replaces the in-thread cards. |
| Mission sheet | reuses `InAppGlassSheet` + `GlassCapsuleButton` | Actions call the existing `MindMissions.resume/delete`, `MissionLearning.learn`, `Marketplace.saveSkillFromRun`; nothing new in the engine. |
| Header | `GlassRoundButton` New chat (+ History in B) | Settings stays the menu icon. The spiral leaves the header; it is the Chat circle in the nav. |
| Copy | `MissionCopy` (pure, tested): status words, time, meta line | e.g. `12 STEPS · CHROME`. |

The spiral symbol: in dev3 it appears as the Home app bar's mark (opens the AI page), as the AI header's "Profile"
button (which actually opens Settings), on the overlay's idle bubble and in notifications. R1/R2 give it one
meaning inside the app: **Chat with Cyclone** (the nav circle).
