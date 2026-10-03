# Cyclone Mobile 4.3.6 — stability + setup improvements

Mobile 4.3.6 / versionCode 97. This release consolidates the complete 4.3.6 stability line onto v4.3.5 and adds the Settings/navigation and rooted quick-setup improvements requested for easier full-autonomy setup.

Publication was explicitly authorized by the user on September 12, 2026. Exact-source Cyclone Mobile CI on `release/cyclone-mobile-v4.3.6` is mandatory before publication. Physical Pixel 8 acceptance remains UNVERIFIED and is not represented as complete by this release.

## What changed

- API-key settings no longer crash on secure-storage save/remove failures. Saves require verified readback before success is shown.
- Login completion cannot be falsely proven by hidden or non-user-visible sign-out controls.
- Visual control grounding rejects evidence without an execution identity.
- Settings is directly discoverable from Home instead of only through the status icon.
- Settings subsections now use one back control; Back returns to the Settings overview instead of Home.
- Root Quick Setup applies Cyclone's allowlisted permission setup, verifies readiness afterward, and repairs the case where Accessibility is enabled but not actually connected.
- Quick Setup refuses to alter control state while an active task or confirmation owns execution.
- Setup preserves the current keyboard selection while enabling Cyclone's keyboard for use when needed.
- Battery optimization exemption is explicitly described as package-wide; other permission work remains scoped to the current Android profile where Android permits.
- Acceptance guards, unit coverage, and stability checkpoint documentation were updated for the release.

## Validation

The consolidated release tree passed Cyclone Mobile CI before publication, including release metadata guards, gateway/MCP contracts, Windows gateway dry-run, Android unit tests, lint, unsigned release APK assembly, provenance packaging, and artifact upload. The publishing workflow independently requires an exact-source Mobile CI success for the final release commit before it signs or creates the GitHub Release.

The published APK is signed with Cyclone's historical update-compatible development key, and the release workflow verifies certificate continuity against v4.3.5 before publication.

## Still requires physical-device acceptance

The following remain explicitly unverified on the target Pixel 8 and should be retested after installation: API-key entry/removal, the Reddit login flow, rooted Quick Setup/full-autonomy readiness, Settings navigation/back behavior, Accessibility reconnect behavior, and Android MediaProjection/screen-sharing consent behavior.
