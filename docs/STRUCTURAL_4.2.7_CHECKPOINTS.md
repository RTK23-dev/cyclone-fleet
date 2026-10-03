# Structural reliability lane — work in progress

Base: b3f499416df7e569940827eb2250e95e1c0d9eb9 (preserved 4.2.6).
Branch: agent/427-structural-reliability. No publication authorized in this lane.

Checkpoint 1 source: ordinary overlay and in-app phone requests select foreground;
queued ordinary requests use the same route. Explicit background retains workspace checks.
Profile intent remains fail-closed pending exact-user routing and provisioning work.

Remaining: exact profile target resolution, cheap verified launch, terminal task cleanup,
launcher resilience, multi-profile ownership registry/provisioning/switching and full CI.
Version remains 4.2.6 until the structural lane passes. Physical testing UNVERIFIED.

Checkpoint 2 source: cancel commands bind the exact task after service recreation;
cleanup joins execution, releases the session, clears the active pointer, and cancels
its notification. Closed results have a separate bounded in-memory history (not disk).
Background panel cleanup restores the compact launcher; repeated attachment renders
through the existing controller instead of creating another stack.

Validation pending CI; local Gradle distribution download is network-blocked.

Checkpoint 3 source: a persistent profile collection preserves the singleton journal;
completed setups can be archived before planning another. Rooted new profiles use
full secondary users, with typed creation/switch commands and exact journal recovery.
Cyclone is automatically included and verified using the same package-install checks.
No extra root-manager APK is required: su is provided by the device daemon.
Minimal setup wiring exposes saved profiles, repair, Add profile and full-user Open.

Still incomplete: automatic recovery of renamed legacy Rooted Clone without a matching
journal, registry transfer/return navigation from a freshly installed secondary user,
explicit profile Ask execution, injectable root runner and exhaustive lifecycle tests.
Do not release this partial lane. Existing managed profiles remain app-based spaces.
