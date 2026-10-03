# Cyclone redesign loop

How the owner and Claude redesign the app together, round by round, from a proposal on the canvas to a signed APK
on the phone.

- **Canvas:** [Cyclone Redesign Studio](https://claude.ai/artifact/JXTcdUNJdw8M4T3BUhCBo6) (private to the owner).
- **Materials:** [Tilt Glass](../CYCLONE_TILT_GLASS.md) for task surfaces, [Teal Matrix](../CYCLONE_TEAL_MATRIX.md)
  for ordinary screens. A proposal uses only their tokens. A new colour, radius or size is proposed on the canvas
  and written into those docs before it is built.

## One round

| Step | Who | What happens |
| --- | --- | --- |
| 1. Brief | Owner | Names the screen and what is wrong, in chat or as a sticky on the canvas. Phone screenshots help. |
| 2. Proposals | Claude | Adds one row to the canvas: **Now** (the screen as shipped) · **A** · **B**. Phone size 412 × 915 dp (Pixel 8), real copy, real tokens, every control a real button. |
| 3. Reply | Owner | Comments on the exact element, edits a board directly (copy, colour, position), or picks: "A, with B's island". |
| 4. Next round | Claude | Reads the canvas, including the owner's edits, and adds the next row under it. Repeat 2–4 until agreed. |
| 5. Agree | Owner | Says "build R*n*". |
| 6. Contract | Claude | Writes `rounds/R<n>-<screen>.md`: every element → Compose component → tokens → Kotlin file, plus copy in a tested object. Copies the agreed board's source into `rounds/R<n>/`. |
| 7. Build | Claude | Implements in `apps/mobile`, adds tests and guards, bumps the version and pushes to the release branch (fast lane). The owner approves the push. |
| 8. Check | Owner | Installs the APK and sends a phone screenshot. Claude puts it on the canvas beside the agreed board. Any difference starts the next round. |

## Rules that keep a proposal buildable

1. **Only existing components or a named new one.** Every element on a board is either an existing composable
   (`OverlayWorkCard`, `WorkIsland`, `GlassComposerBar`, `CycloneMatrixCard`, …) or a new one named in the contract.
2. **Only tokens from the design docs.** Colours, radii, sizes and type come from Tilt Glass / Teal Matrix. The
   board's CSS uses the same numbers (`#E0F5F3` ink, 30 dp card, 33 dp bar, 66 dp bar height, 10 / 14 dp gaps).
3. **Real copy.** Copy on a board is the copy that ships. Sample task text is marked as sample in the contract.
4. **What CSS can't show is written down.** Tilt light, press glow, the voice orb's motion and the fingerprint dots'
   focus are approximated on the canvas; the contract points to the Kotlin that draws them for real.
5. **Behaviour stays.** Buttons go through Task Kit, approval boundaries and privacy rules do not change in a
   redesign. The contract lists every button and the `TaskCommand` it sends.

## Files

```
docs/design/redesign/
  README.md                  this loop
  rounds/R<n>-<screen>.md    the build contract of an agreed round
  rounds/R<n>/*.dc.html      the agreed boards' source, as shipped to the build
```
