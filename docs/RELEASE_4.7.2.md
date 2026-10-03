# Cyclone Mobile 4.7.2

Android versionCode 132. Built from the green 4.7.1 overlay-state correction, itself based on the
published 4.7.0 / 4.6.9 reliability lineage.

## Conversation system

- Phone requests now remain visible as natural user + Cyclone acknowledgment turns.
- The live task card is part of the conversation canvas instead of a second control area inside the
  composer drawer.
- Assistant prose stays inline; user requests use one restrained right-aligned bubble.
- The composer remains the calm persistent interaction surface with Model · Intelligence inline.
- First-stage in-app retraction remains a fully typeable composer with +, model/intelligence,
  voice/send and expand controls rather than falling back to a dead status pill.

## Task Card v2

- Working, Action needed, Done and Failed are visual states of one physical card.
- Shared conversation tokens define spacing, radii, state colors and motion durations.
- Working cards show determinate progress only when the typed agent trajectory provides a stable
  waypoint denominator. Raw operation streams remain indeterminate.
- Grounded progress animates through one shared progress primitive instead of jumping between
  widths; visual animation never changes the underlying completion evidence.
- Repeated low-level operations are compacted into visual milestones without deleting diagnostic
  evidence.
- Done cards preserve a bounded consumer-safe result and expose View details + Open app + Run again.
- Failed cards expose Try again + View details without implying completion.
- Action-needed controls remain capability-gated by the existing runtime interruption contract.

## Backend presentation frontier

- TaskPresentationSnapshot is the single read-only consumer projection for task title, state,
  milestone, progress, outcome and follow-up actions.
- The existing typed TaskTrajectory is projected into deterministic consumer labels; raw model
  waypoint prose never enters the UI.
- Android notifications, the task details page, background handoff ribbon and Ask Cyclone now
  consume the same task projection.
- WorkspaceTaskUi stores a bounded terminal outcome separately from transient status text.
- Run again / Try again re-enter the existing WorkspaceTasks queue and execution ownership path.

## Preserved authority

This release does not add a second execution engine. PhoneToolExecutor remains phone mutation
authority. GATE, Session Kernel ownership, stale-target quarantine, handoff re-grounding,
destination-host verification, signup completion evidence, capture security and lock-screen
suppression remain unchanged.

## Validation gate

Publication remains disabled until exact-SHA Mobile CI passes unit tests, lint, release assembly,
product/repository guards and provenance packaging. Physical Pixel 8 acceptance remains UNVERIFIED
until the conversation/task states, overlay transitions, keyboard behavior and capture invisibility
are exercised on the device.
