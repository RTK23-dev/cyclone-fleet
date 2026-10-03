# Cyclone 4.3.4 task glass correction

Candidate 4.3.4 / 95. Published base v4.3.3 9774d539. Incorporates the full frontend 3c423473 with backend capability checks, retaining Profiles and grounded harness changes.

Checkpoint 1: remove legacy top trace HUD entirely. AgentTraceStore remains available for diagnostics; no independent trace WindowManager/controller survives. Working, Action needed, Done and the human-control ribbon come from the shared task state. Publication authorized by the user; exact-source CI and signature continuity with 4.3.3 remain mandatory. Physical UI acceptance UNVERIFIED.

Checkpoint 2: Ask Cyclone renders bounded semantic checkpoints with an animated nine-dot current-action indicator and verified-only checkmarks. View Progress no longer renders the checkpoint list; named VD frames remain exact and secure, and ordinary default-foreground working tasks open the existing current-profile app. If no live-frame capability exists, Cyclone does not substitute another profile's screen. Arbitrary secondary Android-user streaming is not added by this UI change. Action needed retains backend-controlled Take Over / I'm Done; Autofill is visibly disabled.

Screenshots inspected: the black top rectangle was AiTraceOverlayV27, not the shared Ask task card. This runtime now has no window construction path. AgentTraceStore diagnostics remain intact. No physical-device test performed.

Backend handoff audit: the inherited harness already rejects transport-only completion, binds semantic evidence to session/display/workspace/control revision, invalidates evidence on handoff, and captures fresh exact-session observations before resume. Those paths and their regression tests are preserved. The human ribbon now uses the same backend capability predicate as the main card. This release changes no credentials architecture, executor authority, root profile primitive or GATE.
