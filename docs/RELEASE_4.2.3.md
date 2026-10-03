# Cyclone Mobile 4.2.3 — Live Phone

Android versionCode 84. Builds on Mobile 4.2.2 and the completed Live Phone sprint.

- Clearly separates ON PC AI, LIVE PHONE and BACKGROUND PHONE in gateway settings.
- Adds the Android foreground boundary for the typed Cloud ChatGPT Live Phone adapter. Named displays and Layer 2 workspaces cannot become Live Phone targets.
- Adds a persistent Live Phone notification with Pause/Stop, plus visible Resume/Pause/Stop controls. Pause/Stop blocks subsequent Live Phone requests; it cannot undo an action already dispatched.
- Preserves native Codex MCP, PhoneToolExecutor, existing Background/Profiles features, and GATE authority.

This APK contains the Android half of Live Phone. Cloud ChatGPT also needs the new CycloneLivePhone adapter and matching Cyclone One sprint build; the previously published One 1.1.2 installer does not include that adapter. This release does not publish a new Windows installer.

Publication uses the exact green Mobile CI APK and the historical update-compatible development signer. APK checksum, source SHA, CI run ID, mobile metadata, signing information and update compatibility sidecars accompany the release.

Development channel. Physical Pixel 8 and UI acceptance remain UNVERIFIED. No phone/Pixel/USB/ADB acceptance tests were performed. Chrome, Snapchat, SIM information and actual-phone runtime restart scenarios remain unverified. Windows private IPC restart and native MCP regression tests passed in the source sprint.
