# Cyclone Mobile 4.7.0

Android versionCode 130; built directly on published Cyclone Mobile 4.6.9.

This release redesigns Ask Cyclone's in-app and accessibility-overlay conversation surfaces without
rolling back the reliability work shipped through 4.6.9.

## Chat drawer redesign

- In-app Ask Cyclone now has a rounded bottom chat drawer with a visible grab handle.
- Dragging down or tapping the handle minimizes the drawer into a compact **Ask Cyclone** pill.
- Tapping or dragging the pill upward restores the same conversation/current run.
- The accessibility overlay uses the same interaction model while preserving its secure,
  non-modal host-app behavior.
- A minimized foreground run gets a dedicated content-height overlay window rather than falling
  back to the tiny idle hotspot.
- Background work uses the same Ask Cyclone rest-state pill; human takeover retains explicit
  Autofill / I'm Done controls.
- The plus panel keeps the 4.6.9 Camera, Files, screen share, Create a routine, model and
  intelligence capabilities, now with a visible drag/tap dismissal handle.

## In-chat task elements

- Working tasks start compact with a live progress line, grounded current-step copy, and completed
  checkpoint count.
- **View progress** expands semantic checkpoints inline; **Show less** returns to the compact state.
- Action-needed and completion cards use quiet state tinting, subtle outlines, and no drop-shadow
  banding.
- Existing Take Over, Autofill, I'm Done, confirmation and result routing are preserved.

## Preserved 4.6.9 reliability

- Human-handoff re-grounding and action-memory reset behavior.
- HTTP/HTTPS destination-host verification.
- Semantic stale-target quarantine.
- Explicit signup-completion evidence requirements.
- PhoneToolExecutor remains the sole phone mutation authority.
- GATE, capture security, lock-screen suppression, provider isolation, task queue ownership and
  verification-first completion remain unchanged.

## Validation gate

Before publication, Mobile CI must pass repository/product guards, gateway/MCP contracts, Android
unit tests, lint, release assembly and provenance packaging. Physical Pixel 8 UI acceptance remains
UNVERIFIED until the drawer, keyboard, Instagram task progress, host scrolling and capture
invisibility are exercised on-device.
