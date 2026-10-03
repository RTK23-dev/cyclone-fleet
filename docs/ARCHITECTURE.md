# Cyclone architecture

Cyclone is an Android-first agent system. The phone owns perception, action policy, verification, learned routines and user-visible execution state. PC components extend the same phone runtime; they do not create a second control engine.

## Product components

### `apps/mobile`
The Android app (`com.cyclone.mobile`). It contains:

- Android Accessibility observation and action tooling
- the canonical `PhoneToolExecutor` mutation path
- Ask Cyclone task execution and model selection
- verification, recovery and learned app/routine knowledge
- Brain run history and sanitized downloadable diagnostics
- the persistent Aurora activation overlay
- the local gateway surface used by PC integrations

### `apps/device-gateway`
The PC-side device bridge. It handles device discovery/ADB forwarding and exposes a constrained local interface to Cyclone on the phone.

### `apps/pc-companion`
Cyclone One — the Windows glass: JPEG live view, human/AI handoff, and session tiles bound to `session_id`. It does not run a second phone-control engine.

### `tools/codex-phone-mcp` and `tools/cyclone-agent-mcp`
Constrained MCP adapters for external coding/agent clients. They route through Cyclone's gateway contract rather than exposing a generic shell.

## Runtime rule

The normal execution order is:

```text
compiled skill replay (goal + pageKey + sessionId + displayId)
→ known route / learned routine / open_app|intent landing / Fast Path LLM
→ a11y Page Card (elementIndex)
→ one screen-changing phone tool (or same-page form batch)
→ settle 300ms + fingerprint (+500/+1000 if Unchanged)
→ local tap verification (fingerprint is authority for ordinary taps)
→ recovery or next action
→ final result
```

A matching compiled skill hits without an LLM turn. Miss falls back to known route / Fast Path LLM / UI sub-agent. Vision is only on miss: empty accessibility tree or `perceptionMode=vision_escalate`. Unchanged after settle is `verified=false` and must not dispatch a second click channel. Consequential actions retain explicit approval boundaries. See [`V4_STAGE3_SKILL_COMPILER.md`](V4_STAGE3_SKILL_COMPILER.md) and [`V4_STAGE1_FASTPATH.md`](V4_STAGE1_FASTPATH.md).

### Task Kit (since 5.0.0-alpha.29)

Every task has exactly one engine (`TaskEngine`: Cyclone Mind, classic foreground agent, background workspace). Every
task button on every surface sends a typed `TaskCommand` through `TaskCommands`; the bus resolves the owning engine's
`TaskController`, which carries the command out or refuses with a reason, and records the outcome in the run trace
(`TASK_COMMAND`). Surfaces never call an engine directly. A contract test checks every engine against every command
and every button a surface can show. Structure plan: `Cyclone V5 plan/17-structure.md`.

### Learn (since 5.0.0-alpha.36)

Every Mind mission records a structural trail (`mind/learn/MissionTrail.kt`): each screen it read with all its
controls (labels and selectors filtered by `AtlasPrivacy`, text fields named by hint, never by value) and each action
with its before/after screen. One **Learn** press per run (`MissionLearning`, on the mission card, Brain → Outcomes
and Glass's run inspector through `learn.run`) turns the trail into the app knowledge store: screens, every control as
a learned action, and the same-app moves that worked, then projects it into the Atlas. The next run's screen reads
carry *Learned before* advice for known screens (`LearnedHints`); the Mind still acts through live refs and
re-observes. **Save skill** stores a completed run's goal as the owner's recipe (`market/OwnerSkills.kt`, "Your
skills"). Plan: `Cyclone V5 plan/20-app-mapping-build-plan.md`.

Since 5.0.0-alpha.37 the Mind also **runs from the map**: `mind/map/MindMap.kt` builds per-app routes from the same
learned knowledge (safe, reliable, non-stale moves only). The first screen read in a learned app carries the app's map
card, and the `go_to` tool walks a route through the ordinary act path, re-reading the screen after each move and
stopping at the first surprise; walks and surprises feed back into the store. The Lab variant knob `useMap` A/B-tests
it.

### Glass overlay (since 5.0.0-alpha.43)

The overlay's surfaces share one material, `Modifier.tiltGlass` (`ui/overlay/glass/TiltGlass.kt`). It draws a body, a
sheen, halftone edge dots batched with `drawPoints`, a hairline and the rim shine. Its light comes from `GlassLight`,
which `FollowPhoneLight` drives from the gravity sensor while the chrome is open; it rests when animations are off.
The optics are pure (`GlassOptics`) and unit-tested.

Round controls use `litRim` and `pressGlow`; the voice button becomes `VoiceOrbButton` while listening.

`SignatureOverlayDrawer` stacks the plane pill (`PlaneRow`), 10 dp, the card (`OverlayWorkCard`,
`OverlayForegroundCard`), 14 dp, then the Ask bar or `WorkIsland`. A drag down anywhere on the stack is
`OverlayUserAction.MINIMIZE`: card → island → notification only. While a task runs, the second fold sets
`idleChipVisible = false`, and the notification's **Show** (`OverlayShowReceiver`) brings the island back.

Owner Moments render as `OverlayOwnerCard`. Approvals are split by `OverlayGlassCopy.approval`; other kinds keep
`CycloneOwnerCard`'s bodies. `TaskAppTrail` keeps the apps a task has worked in, for the header logos.

`MissionPlaneSession` publishes:
- `PlaneUi.outcome`, the reason a move did not happen, shown beside the pill;
- `movedTo`/`moveSeq`, after which an owner's background move goes home through `guidedHome()` and folds the stack.

An owner's tap waits up to `OWNER_PAUSE_MS` for the step boundary. Plan: `Cyclone V5 plan/27-overlay-redesign.md`.

### Direct first (since 5.0.0-alpha.45)

Tasks that need no screen run first. The Mind's `calendar_find`, `calendar_add` and `contact_find`, plus `set_timer`
and `set_alarm`, call `MindDevicePort.direct`, which reaches the executor's `phone.direct_*` tools. Those tools use
`direct/DirectActions` (Android's calendar and contacts providers, and the clock's `EXTRA_SKIP_UI` contract). The pure
rules live in `DirectPlan`: time parsing, event checks, windows, alarm proof. Every result is proven or said as
unconfirmed: an event is read back, an alarm is matched against `AlarmManager.nextAlarmClock`, a timer against the
clock's notification. A missing permission is asked for by `DirectAccessActivity` (Android's dialog only) and the
action runs once more. These tools never observe a screen, start a plane or wait for an unlock. Guard:
`scripts/ci/tests/test_mobile_direct_first.py`. Plan: `Cyclone V5 plan/29-one-tap-engine.md`.

### Background that stays working (since 5.0.0-alpha.44)

Only a real loss ends a background screen: `onDestroy` of the Accessibility service, not `onInterrupt`. A locked phone
refuses an action (`SCREEN_LOCKED`) without pausing the screen, and the mission waits for the unlock. Task ownership is
`WorkspaceCommands.ownedTask`: the app visible on the main screen means the owner has it; Recents leftovers do not
count, and several tasks on Cyclone's display are all Cyclone's. Background observation shows whatever application
window is on top of Cyclone's display. Approvals for a Mind mission's background screen go through
`PhoneToolExecutor.workspaceGate`, with the overlay card and one-shot grant and no pause. Background failures carry their
reason code; `BackgroundFailure.classify` lets only `UNSUPPORTED` move the task to the screen. A paused screen that
Cyclone still holds is taken back (`WorkspaceRuntime.reclaim`). A gesture Android never queued goes once through the
helper's `input -d`. `BackgroundCheck` runs the whole path on a hidden screen (Settings → AI → Background work, and
once per build by itself); an engine failure keeps Automatic on the screen with the reason. Guard:
`scripts/ci/tests/test_mobile_background_stable.py`. Plan: `Cyclone V5 plan/28-background-stable.md`.

### Background always (since 5.0.0-alpha.42)

`BackgroundCapabilities` turns the setup facts into one level (ready, needs start, needs setup, off, unsupported) with
one action; the Background work switch, the `CycloneBackgroundTile` Quick Settings tile and `BackgroundWatch` (boot,
update and app-start checks, one quiet notification) read it. The gateway's `POST /v1/devices/{id}/background/keep-on`
(`cyclone_device_gateway/background/keep_on.py`) grants only the helper's start-on-boot permission and proves it.
`StartPolicy.begin` decides the first plane from the mode, the fallback (`PlaneFallback`), the per-app choice
(`PlaneOverride` in `AppPlaneCompat`) and who holds the app (`TargetHolder`: nobody, Recents, owner): launch, adopt
from Recents, a second window (`WorkspaceUserService.launchSecond`, the owner's window restored if moved), wait, ask
or the screen. `MissionPlaneSession` waits for the owner (Start now through Task Kit) and yields an app the owner
opens, taking it back when they leave. The workspace path types with the Hands ladder on the background display, swipes
sideways with the approval check, opens https/geo links in the app itself (`view`), sizes the display like the phone
(`WorkspaceShape`) and accepts an existing frame on a still page. `reply_notification` answers through the message's
reply action with approval (tier 0). `MissionQueue` holds separate tasks as Runs next. Plan:
`Cyclone V5 plan/26-background-always.md`.

### Hands: text delivery (since 5.0.0-alpha.41)

A Mind mission's environment is built with `ownerMission = true`: `phone.type` into an ordinary editable field is
authorized by the mission itself (`OwnerMissionTyping`; never password or secret-looking fields), and the step agent
keeps `TaskTypingAuthorization`. `PhoneTypeEngine.perform` owns delivery: set-text, strict read-back, then
clipboard + `ACTION_PASTE` (sensitive clip, owner's clip restored) and read-back; the result carries `method` and
`textVerified`, and unverified text is reported to the Mind as unverified. `CurrentTargetRevalidation` treats nodes
nested with an editable target as the same control and matches a text box whose label changed by resource id or raw
path. `type_text focused=true` types into the focused editable. The toolbox names each refusal, reports a tap that gave
focus, and `TypingTracker` hints after two identical refusals and hands the draft to the owner (clipboard + owner
question) after four. Mind missions appear in `RunInsight` step by step. Plan: `Cyclone V5 plan/21-mind-hands-and-desk.md`.

### Planes: the screen or a background screen (since 5.0.0-alpha.40)

A Mind mission works on one `TaskPlane` at a time: `Screen` (display 0) or `Background` (a Shizuku-backed private
virtual display). `runtime/plane/` holds the pure core — `PlaneSwitcher` (pause at a step boundary → move → verify →
grant, or restore and roll back; journaled in `FilePlaneJournal`, closed after a restart by `recover()`),
`PlanePolicy` (Automatic start and escalation signals), `AppPlaneCompat` (per app and version) and `BackgroundHealth`
with its `RecoveryLadder` — and `MissionPlanes`, the Android side: the mission's `MissionPlaneSession` implements the
Mind's `MindPlanes` hooks and a `PlanePort` over `WorkspaceRuntime` (`adopt` moves the app's task from display 0 into
the background display with `am display move-stack`; `handoff`/`resume` move it back and forth). Phone tools run inside
`MindStepGate`; after a move the toolbox is rebound to a `CycloneAgentEnvironment` for the new `ExecutionContext`, so
every action still goes through `PhoneToolExecutor` with its session and display. The pill (`ui/overlay/PlanePill.kt`),
and the task notification move tasks only with Task Kit (`MoveToBackground` / `MoveToForeground` /
`AllowBackground`). Plan: `Cyclone V5 plan/25-planes-background-foreground.md`.

### One map, grounded skills and routines (since 5.0.0-alpha.39)

A mapping pass records a Mind-style trail (`mapping/run/MappingTrailTap.kt`) and, when it ends, learns it into app
knowledge (`MappingLearning` → `MissionLearner`), so the map runs route on includes what missions walked; test-account
passes are learned at confidence 0.5 and `MindMap` prefers confirmed moves. Owner skills carry an anchor
(`market/SkillAnchor.kt`: app, way in, destination, finish steps) saved from the run's trail; `SkillGrounding` derives
health from the current map and writes the Mind's skill card, and a finished skill run re-grounds its anchor. Routines
run skills with `StepType.RUN_GROUNDED_SKILL` through `Marketplace.run` (the Ask entry); `RoutineGrounding` labels
routines grounded or scripted. `skills.list` (`GET /v1/devices/{id}/skills`) feeds Glass's Skills tab, the way on the
Taught map and the fleet. Plan: `Cyclone V5 plan/23-one-map-grounded-skills.md`.

### Mapping missions and the Glass Atlas (since 5.0.0-alpha.38)

`mapping.start` takes an **identity**: `own` (the owner's account, look only: a sign-in wall ends the pass as
`sign_in_needed`, and account/sign-in doors are refused) or `test` (sign-in screens allowed, secrets only through the
Secrets Card). Glass sends a mission budget (10 min / 30 min / 2 h → `maxElapsedMs`, `maxNewScreens`); the phone
enforces it. Mapper safety v2 (`mapping/crawl/MapperDoorRisk.kt`, called first by `ExistingGateMappingSafetyPort`)
refuses checkable controls and rows holding one, state-changing actions and security areas, on top of the GATE
classes. Glass derives **zones** client-side and deterministically from `atlas.get` (`apps/glass/src/maps/zones.ts`:
breadth-first from the entry, one zone per base page, at most 8) and draws the overview, zone views, the Coverage tab,
mission control (start sheet, live panel, report), the fleet (`#/apps`) and run replay. Glass still decides nothing.
Plan: `Cyclone V5 plan/22-glass-atlas-and-mapping-missions.md`.

### Cyclone Marketplace (since 5.0.0-alpha.35)

The phone's `market/` package is the one authority for the store: validated listings (data only; a recipe is a goal
template with typed inputs), what the owner added (`Cyclone Brain/Marketplace/installed.json`), suggestions from
installed apps, and the phone's connections (AI provider state, PC link; never keys). A recipe runs through the same
entry as a typed Ask (`OverlayChromeRuntime.submitRequest`), so the Mind keeps GATE, approvals and the Secrets Card;
runs are refused while the phone is busy or the owner has control. Glass (`#/market`) reads and changes it through
`market.*` gateway ops and shows this PC's MCP agents through the agent connector with fixed arguments
(`/v1/pc/connections`). Plan: `Cyclone V5 plan/19-marketplace.md`.

### Cyclone Lab (since 5.0.0-alpha.33)

Cyclone measures itself. The gateway's lab (`cyclone_device_gateway/lab/`) runs experiments — missions × variants ×
repetitions — on a paired phone: it prepares the phone through typed, allowlisted ADB probes (never a route, never the
model), starts a Mind mission with a variant over `lab.start`, plays the owner through Task Kit (it never approves and
never supplies a secret), and judges the result from the phone's real state, then restores every setting it changed.
Results carry the mission's metrics and redacted record; rates come with confidence intervals and A/B with an exact
test. Glass (`#/lab`) and the agent MCP (`phone_lab_*`) start and read experiments. Plan: `Cyclone V5 plan/18-cyclone-lab.md`.

### Owner Moments (since 5.0.0-alpha.31)

Whenever a task needs its owner, whichever engine runs it, the app derives one `OwnerMoment` (question, values
check-in, approval, secure input, hand-over) from the Mind inbox or the classic/background task state. The overlay, Ask
and the mission card render it with the one `CycloneOwnerCard`; the task notification shows the same buttons with an
inline reply. Every button is a Task Kit command. Buttons that act for the owner require an unlocked phone; secrets
stay on the Secrets Card.

### Cyclone Mind (foreground Ask, default since 5.0.0-alpha.25)

Foreground Ask requests run as a **mission** in `apps/mobile/.../mind/`: one model, one continuous conversation, native
tool calls (text envelope fallback), a working-time budget that excludes owner waits, and a journal after every turn so a
mission resumes after an interruption. The model chooses every action; the harness keeps the boundaries: every mutation
goes through `CycloneAgentEnvironment.act` → policy/GATE → `PhoneToolExecutor` → settle → verification, the new screen
is shown after each action, secret fields go only through the Secrets Card (`vault_fill`), approvals wait on the GATE
card or the mission's request card, and journals/diagnostics are redacted and never contain provider reasoning or
screenshots. Simple "open <app>" launches keep their fast path; background workspaces and owners who switch Cyclone Mind
off use the step agent above. Design and device test plan: `Cyclone V5 plan/16-cyclone-mind.md`.

Observe/act that can run in a workspace carry `sessionId` + `displayId`. Default-foreground is display 0 (`default-foreground`). Named workspace sessions never fall back to display 0; unknown session, display mismatch, and cross-session observation are rejected before mutation. Compiled skills carry the same session/display binding. See [`V4_STAGE2_SESSION_KERNEL.md`](V4_STAGE2_SESSION_KERNEL.md).

## Observability

Every completed or failed agent run should leave a durable trace. Brain presents recent runs and can export a compact text diagnostic containing model-visible context, decisions, tool requests/results, verification, failures and recovery events. Diagnostics must exclude credentials, raw typed secrets, screenshots/base64, full accessibility trees and hidden provider reasoning.

## Source of truth

When documentation disagrees with implementation, use this order:

1. executable code and tests;
2. CI/release metadata;
3. `AGENTS.md` and this documentation;
4. Git history and old release notes for historical context only.
