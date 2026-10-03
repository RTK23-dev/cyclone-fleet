# Session plan — alpha.3: "Map an app with one button, watch it on Glass"

**Written:** 2026-09-23, after the dev3 handoff review.
**Target:** Mobile `5.0.0-alpha.3.dev1` (versionCode **144**) · Glass `1.6.0-alpha.3` · Gateway/MCP `5.0.0-alpha.3.dev1`
**Channel:** developer alpha. Physical Pixel acceptance stays **UNVERIFIED** unless the owner runs it.
**Plan milestone:** [09 — alpha.3](../09-cuts-and-milestones.md): one-button mapper, never-pay, mapping persona kept apart from live; Start from the Glass board, live cursor, diffs spawn cards, the secrets card pauses and then resumes.

## Why this chunk

At dev3 every piece of autonomous mapping exists, but nothing connects them:

| Piece | State at `a896b5da` |
|---|---|
| `mapping.start/pause/stop/status`, `atlas.diff` (phone + PC gateway) | works, creates an in-memory job only |
| `MappingSessionController` + `AtlasDiffJournal` | works, journal is never appended in production |
| `SafeMapperWalker` (one mutation → fresh observe → verify, GATE danger) | tested, **zero production callers** |
| Observation / mutation / safety / secrets ports (`AndroidMapperPorts.kt`) | real implementations exist |
| `MappingAtlasPort` (walker → AtlasStore) | **no production implementation** |
| Session → walker driver loop | **missing** |
| Phone UI to start | **missing** (App Maps is a read-only list) |
| Glass Start mapping | button hard-disabled; client has no mapping/diff calls; board loads once |

This build connects them. It is the plan's headline ("one button to learn the house"). It also stops the Atlas from depending on hand-taught Follow Me data, which the next build (Ask reads the Atlas) needs.

## Base and branches

1. Merge PR **#169** into `v5/integration`, so integration equals the released dev3 source plus the release automation. Close **#167** as superseded (#169 contains it). Leave **#168** (key rotation) parked.
2. Work branch: `v5/alpha3-mapper` from the new integration head. One integration PR at the end, with a commit per checkpoint.
3. Set up the Android SDK in the container. `dl.google.com` and `services.gradle.org` are reachable; the build needs JDK 17 and compileSdk 36. Run `./gradlew :app:testDebugUnitTest` once on the base to record a green baseline before touching code.

## Checkpoints

Each checkpoint is committed and pushed so CI runs. C1 and C3 are the visible ones.

### C1 — Phone mapping engine (core)

New package `apps/mobile/.../mapping/run/`:

| File | Job |
|---|---|
| `MappingDriver.kt` | Coroutine loop for one job: enter the place → `walker.step()` → act on the result → repeat until a terminal state or budget. Before every step, reads the controller state so Pause/Stop from phone, Glass or the notification win immediately. |
| `ControllerSessionPort.kt` | `MappingSessionPort` over `MappingSessionController` and the lease. **Per-door danger must not call `controller.markDanger`**, because that pauses the whole job. Record danger in the Atlas and the diff journal instead. Converts the two parallel `MappingBudget`/`MappingDanger` enums (crawl vs session) in one place. |
| `AtlasStoreMappingPort.kt` | Production `MappingAtlasPort`. `recordVerified` upserts screen/edge structure into the **mapping** persona place in `AtlasStore` (structural keys only, no labels) and returns `AtlasStructuralChange`s, which `recordVerifiedProgress` appends to the journal. `hint()` returns known and dark doors from the Atlas. `markPartial`/complete set `AtlasMapStatus`. |
| `MappingNavigator.kt` | **Backtracking.** The walker currently returns `completePartial("no_safe_unexplored_doors")` at the first dead-end room, which on a real app gives about 3 rooms. Change the walker to return a new `MappingStepResult.RoomExhausted`. The driver then tries `phone.back` (verify still in the place, re-observe) up to *k* times to find a room with dark doors, then `phone.open_app` to reset to the start room. It completes only when the start room is exhausted as well: `mapped` if no dark doors remain, else `partial`. |
| `MappingDriverRuntime.kt` + `MappingForegroundService` | Launches and cancels drivers. Ongoing notification: "Mapping Clock · 7 rooms · Stop". The Stop action calls `controller.stop`. Manifest entry. |

Wiring:
- `GatewayV5MappingAdapter.mappingStart` launches the driver after `controller.start` or `resume`.
- **Enter:** `phone.open_app(package)` on `default-foreground` / display 0 (first cut: foreground plane only). Verify with `PlaceResolver` that the place is on screen before the first step.
- **Secrets:** `Run1MappingSecretsPort(onResolution = resume)`. Job goes to `needs-secret`, the phone card fills, then `controller.resume` and the driver relaunches.
- **Human touch / companion control:** already raised as `HUMAN_CONTROL` by authority revalidation. The driver parks and does not retry.
- **Default quick budget:** 12 new screens, 3 min, 6 consecutive non-progress, 2 attempts per door.

Tests (JVM, fake observation/mutation ports, **real** controller + real `AtlasStore` in a temp dir):
- A 6-room fake app with one dead end: maps all rooms using back; the edges appear in `atlas.get` and in `atlas.diff` since `null`.
- A PAY door and a delete-account door are never tapped; both are recorded dark and the job continues.
- A login wall pauses as `needs-secret`, then resumes after the fill and finishes.
- A human touch pauses as `human-control`; Stop mid-run gives `stopped` with no further mutations.
- Leaving the app on back triggers `open_app`, not a tap in a foreign app.
- Budget exhaustion gives `partial`.
- The live persona place is untouched; stored nodes contain no labels.
- Walker unit tests are updated for `RoomExhausted`.

### C2 — Phone App Maps becomes a control surface

`ui/v32/AppMapsSettings.kt`:
- **Map an app:** a picker of launcher apps (manifest `<queries>` already present; confirm it covers launcher intents) plus a Quick budget, then **Start**.
- **Running card:** state (running / needs-secret / human-control / paused), rooms, doors, dark doors; Pause/Resume/Stop.
- Place cards show both personas: "Your map (Follow Me)" vs "Mapping pass".
- Room list per place: purpose plus door count. This is the plan's "small graph"; a real mini-canvas can come later.
- **Share mapping report:** structural-only JSON (job summary, step result reasons, counts, danger classes, no labels or text) through the share sheet. This is how the owner's own test gives us evidence without a connected device.

Test: extend `V5SettingsMountTest`, plus a report privacy test (no label or content strings).

### C3 — Glass watches the crawl

`apps/pc-companion`:
- `services/atlasClient.ts`: `mappingStart/Pause/Stop/Status` and `atlasDiff(placeId, persona, since)` with parse and validation, `session_id` required and secret guards on every payload.
- `pages/mapsPage.ts`:
  - Enable **Start mapping** only in real mode with a focused V5 phone. It stays disabled in demo mode and when the gateway is remote/readonly.
  - Show Pause/Stop while a job is running.
  - Poll about once a second while the job is nonterminal: status moves the **cursor pulse** to `currentAtlasNodeId`; diffs are **applied in place** without re-fitting the view.
  - On a terminal state, refetch with `atlas.get`.
  - `needs-secret` shows "Waiting for password on the phone" (G3 path 1).
- `ui/appMapCanvas.ts`: `setCursor(nodeId)` and `applyDiff(changes)` that add cards and edges without rebuilding the board.
- Gateway: add a test that `mapping.start` is refused when `GATEWAY_MODE=readonly`; implement the refusal if it is missing. No test for this exists today.

Tests: atlas-client mapping and diff parsing; maps-live: start → poll → cursor → diffs spawn cards → terminal refresh; needs-secret banner; Start disabled in demo, readonly and on phones below 5.

### C4 — Versions, CI, downloadable build

- Bump `release/version.toml`, `build.gradle.kts` (144), Glass `package.json`/`Cargo.toml`/`tauri.conf.json`, and the gateway/MCP `pyproject`s. Then run `release_versions.py --check` and `mobile_product_guard.py`.
- Run locally: Android unit tests, `npm test`, gateway pytest, MCP unittest.
- Push and open one PR into `v5/integration`, then get Mobile and PC CI green on the exact SHA.
- Publish through the existing paired publisher as a clearly labelled developer alpha, with the waiver stated honestly ("untested on device").
- **Owner decision needed before publishing:** reuse the legacy development key again (dev3's authorization does not carry over automatically), or ship CI artifacts only.
- Update both `STATUS.md` boards to one honest table.

## Owner's 5-minute test (optional; not a gate)

1. Install the APK as an update, then Settings → App Maps → Map an app → **Clock** (avoid Settings or apps with accounts on the first run). Keep hands off the phone.
2. Watch the notification count rooms; tap Stop or let it finish.
3. On the PC, Glass → Maps → Clock → *Mapping* persona. Or start it from Glass and watch the cards appear.
4. Share the mapping report into the issue or chat.

## Explicitly not in this session

Ask reading the Atlas (next build) · Glass `ask.start` / live Ask mirror (next build) · People memory · mapping websites in Chrome · creating dummy accounts / sign-up · mapping on a named virtual display · Glass→phone encrypted fill · freshness/stale remap · key rotation (#168).

## Risks

| Risk | Handling |
|---|---|
| Real accessibility trees produce poor structural rooms (duplicates, or a feed counted as many rooms) | Conservative budget; the report shows reasons and counts; tune the projection from the owner's report next session |
| Foreground mapping takes over the phone | Started only by the user, visible notification, one-tap Stop, touch pauses it |
| Back navigation leaves the app | Place check after every back; `open_app` reset; never tap outside the place |
| Session length | Stop at a checkpoint boundary. C1 alone still produces a build: engine plus Glass Start via the gateway |
| Settings-style toggles | Switches are classified UNKNOWN/CONTENT and are never tapped; GATE covers pay/send/delete/grant |

## Next session after this

"The map helps Ask": the Atlas becomes a hint sketch for the agent (never a macro), with the plan's required tests (Louella sentence never rewritten into a login-status check, stale edge falls back to looking, dummy identity never reported as live), plus Glass `ask.start` and a live HUD mirror.
