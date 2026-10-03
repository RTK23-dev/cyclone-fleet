# Cyclone 3.9.10 — composer and invocation reliability

Cyclone 3.9.10 is intentionally narrow: it repairs the floating Ask Cyclone entry surface without changing the canonical phone executor, policy/GATE authority, background workspace engine, Device Gateway, or Cyclone One protocol.

## User-visible changes

- The floating Ask Cyclone surface is one compact request bar: **Add · Settings · Ask Cyclone · Microphone · Send**.
- Provider/model/Brain diagnostics no longer render in the floating composer.
- Model selection, reasoning level, same-account model qualification, and Assistant-role setup live in an in-app AI Settings activity.
- The composer consumes Android navigation-bar and IME insets and keeps a 14 dp breathing gap above the safe bottom edge.
- Successful, failed, and cancelled tasks clear stale status/voice/composer state and leave the next request immediately usable.
- Internal `Cyclone Brain updated` progress is filtered from the overlay/task-notification progress surface. The trace still records Brain consolidation.
- Android Assistant-role setup uses the system-owned `ROLE_ASSISTANT` flow and an `ACTION_ASSIST` activity entrypoint. Cyclone does not intercept `KEYCODE_POWER`.

## Safety invariants preserved

- Phone mutation remains exclusively behind Cyclone policy/GATE and the canonical phone executor.
- PAY, SEND, DELETE, and GRANT confirmations remain explicit and separate from the normal composer.
- Assistant-role selection is user-controlled by Android and is never silently seized.
- If Accessibility/phone control is unavailable during an assistant invocation, Cyclone stays in-app and explains that phone control must be enabled.
- Attachment references remain bounded and in-memory for the next request.

## CI acceptance

The exact release SHA must pass the reusable Mobile CI job:

- release/version metadata and repository guards
- Device Gateway and MCP contract tests
- Gradle wrapper validation
- Android unit tests
- Android lint
- unsigned release assembly
- APK identity/provenance packaging

The full-release publisher may sign only that exact CI artifact and must verify certificate continuity against the published `v3.9.9` APK before creating `v3.9.10`.

## Physical-device acceptance still required

CI cannot prove these Pixel/OEM behaviors:

1. Resting composer height and visual offset match the intended Gemini-like placement.
2. Gesture-navigation and three-button-navigation safe areas are respected.
3. Opening the keyboard keeps the whole bar above the IME while typing, microphone and Send stay tappable, and dismissal returns smoothly.
4. Ten sequential tasks remain editable after success/failure/cancellation.
5. Voice transcript visibly populates the composer after a prior completed run.
6. Android offers Cyclone as an Assistant-role candidate on the target device.
7. With Cyclone selected and the OEM Power gesture configured for Digital assistant, long-press Power routes to Cyclone rather than Gemini.
8. Chrome, Camera, Muse/model-account access, and broader visual/background behavior retain their separate device-specific verification requirements.

Until those are tested on hardware, `release/version.toml` remains explicit that Pixel/UI acceptance is `UNVERIFIED`.
