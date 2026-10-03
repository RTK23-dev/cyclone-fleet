# Cyclone Mobile 4.2.6 — Visual Architecture

Mobile 4.2.6 / versionCode 87 publishes the completed visual-architecture sprint from `visual/426-run-01`, source checkpoint `5bee951d2140773ed0026e17bbe1b855c1da5112`. It supersedes Mobile 4.2.5 / 86 without rewriting that release.

## Included

- Rebuilt consumer shell around Home, Profiles, AI, Routines and Brain, with AI retained as the visually dominant center destination.
- New alpine Ask Cyclone environment with calmer conversation surfaces, compact model selection, Low / Medium / High intelligence controls, phone-autonomy access, and a keyboard-safe composer/navigation relationship.
- Tactile task glass with exact task identity, live progress routing, swipe-to-open and contextual pause / stop / dismiss behavior, plus compact terminal states.
- App-first Routines organization with Apps / Categories / Specifics, search, app identity, active state, and Teach by doing moved into routine creation instead of primary navigation.
- Live Profiles dashboard with active profiles prioritized, truthful rotation language for time-sliced work, app identity, task state, and take-control flows.
- Evidence-first Brain with verified skills, learned apps, confidence, recent outcomes and real run details instead of developer-oriented implementation state.
- Utility-first Settings for AI, phone control, background work, profiles, connections, privacy and About, while retaining the existing permission, background, profile, API-key and PC Gateway wiring.
- Refined task recovery and live-view surfaces with bounded previews, explicit connection state, graceful unavailable-state copy, and clear review / takeover actions.
- Unified light/dark design tokens, typography, spacing, system-bar handling and reduced outline/card noise across the primary product surfaces.

## Safety and execution invariants

Cyclone's existing executor, Session Kernel identity, GATE/review behavior, workspace routing, mutation authority and verification paths remain authoritative. This release does not claim simultaneous parallel Android input; multiple active profiles are presented as Cyclone rotating between workspaces.

Background isolated-display work still requires Android 15+ and its existing setup path. The app minimum remains Android 13 / API 33.

## Validation and physical review

The user explicitly authorized publication on September 9, 2026 for immediate physical visual review. Physical phone / Pixel / USB / ADB visual acceptance for this exact build remains UNVERIFIED before publication. The release pipeline must still pass Cyclone Mobile CI, assemble the exact release-branch source, verify APK metadata and checksum, sign with the existing update-compatible development signer, verify signature continuity with 4.2.5, and publish only if those gates succeed.

No Windows component versions are advanced by this release. Cyclone One remains 1.1.2 and device gateway / MCP remain 4.1.0.
