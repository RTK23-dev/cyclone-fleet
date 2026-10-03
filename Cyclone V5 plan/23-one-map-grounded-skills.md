# 23 — One map, grounded skills and routines (alpha.39)

**Status:** built in 5.0.0-alpha.39.dev1 (see `docs/RELEASE_5.0.0-alpha.39.dev1.md`), 2026-09-26. Moves plan 21 (Hands)
to alpha.40 and Desk to alpha.41.

**Owner's ask:** "How solid is the full mapping and skills / routines build out now? New skills and routines should be
grounded into the map too." Then: "build out this alpha 39 now and release".

## 1. What the audit found (alpha.38)

| Finding | Evidence |
|---|---|
| Two maps that did not talk. Mapping missions wrote the Atlas; runs route on app knowledge that only Learn wrote. A 2-hour mission made Glass richer and no run faster. | `mapping/run/AtlasStoreMappingPort.kt` writes `AtlasStore`; `mind/map/MindMap.kt` reads `AppKnowledgeStore` through `AppKnowledgeReader`; `AtlasIngestion` only goes knowledge → Atlas. |
| "Your skills" were a goal sentence. No route, no destination, no way to know they still work after an app update. | `market/OwnerSkills.kt` stored goal + app names only. |
| Compiled skill routes were dormant: wired only into the classic agent, which is off since the Mind became the default. | `ai/OpenRouterAdaptiveAgent.kt` → `CompiledSkillReplay`; `MindMissions.enabled` defaults to true. |
| Routines ran on a separate engine of fixed selectors (coordinates allowed for taught ones) that never looked at the map and never learned. | `automation/WorkflowRuntime.kt`, `guided/TeachingRoutineCompilerV292.kt`. |
| The Mind had no skill or routine tool. | `mind/MindTools.kt`, `mind/PhoneMindToolbox.kt`. |

## 2. The build

### 2.1 One map
A mapping pass records its walk the way a Mind mission does (`mapping/run/MappingTrailTap.kt`, through
`MindTrailRecorder`, so the same privacy filter applies). When the pass ends (done, stopped or failed) the trail is
learned into app knowledge exactly like a Learn press (`MappingLearning`), so `go_to` and the map card can use it.
- The mapper's Atlas stays structural (digests) for Glass; the labels live where Learn already keeps them.
- Own-account passes are trusted like Learn. **Test-account passes are learned less trusted** (confidence 0.5,
  discovered): `MindMap` adds a cost of 1.5 moves to such a move, so a confirmed route wins, and a walk on the owner's
  account confirms it (the existing walk feedback sets confidence 0.9).
- The pass report says what runs can now use (`runsCanUse`).

### 2.2 Grounded skills
A skill is a route plus a finish (`market/SkillAnchor.kt`):
- **Anchor**: the app it works in (most successful actions), the destination (the screen of its last successful
  action there), the way in from the entry with detours and loops removed (≤ 12 stops), and how much work happens at
  the destination. Page keys and structural titles only.
- **Save skill** learns the run and saves the anchor beside the listing (`owner-skills-anchors.json`).
- **Health** from the map as it is now: *Route known* (destination on the map and a known route from the entry),
  *Destination known*, *Needs re-check* (the destination left the map), *Not grounded* (saved before alpha.39).
- **Running**: a mission whose goal is a saved skill gets a skill card with its first situation: where it works (the
  map handle), the saved way, "open the app, go_to sN, then do the rest". The walker checks every screen; the way is
  advice. Lab missions never get skill cards.
- **Re-grounding**: a saved skill that runs to the end is learned and its anchor moves to where it worked this time.

### 2.3 Grounded routines
A new routine step, **run one of your skills** (`StepType.RUN_GROUNDED_SKILL`), starts the skill through the same
entry as an Ask (`Marketplace.run`), so GATE, approvals and the Secrets Card stay. A refusal (busy, owner in control)
fails the routine run honestly. The Routine builder offers it first, with each skill's health. Routines show
*Grounded on the map* or *Scripted taps · not grounded* (`automation/RoutineGrounding.kt`). Existing scripted routines
keep working unchanged.

### 2.4 Skills on Glass's map
- `skills.list` (phone op, gateway contract, `GET /v1/devices/{id}/skills`): name, health, the way as structural titles
  with the Taught map's screen id when known, finish steps, when grounded. The goal never leaves the phone here.
- App → **Skills** tab: health counts, one card per skill with its way (destination highlighted), Show on the map
  (draws it on the Taught map, `?skill=`), Run on the phone.
- Place inspector: **Skills through here** (passes / works here).
- Fleet: skill count per app, or how many need a re-check.

## 3. Invariants kept
PhoneToolExecutor is the only mutation path (skills and routines run as Mind missions). Approval boundaries and the
Secrets Card are unchanged. No typed values, secrets or screen content in anchors, the trail or `skills.list`.
Glass decides nothing.

## 4. Not in this alpha
- Compiled skill routes stay with the classic agent; they are not deleted, and not used by the Mind.
- Converting an existing scripted routine into a grounded one is manual (build it again from a skill).
- Skills with inputs (typed parameters) are still catalog recipes; owner skills stay literal goals.
- The mapped Atlas (mapping persona) and the Taught map (live persona) are still two Glass views of one knowledge;
  a skill's way is drawn on the Taught map.
