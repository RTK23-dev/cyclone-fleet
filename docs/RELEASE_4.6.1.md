# Cyclone Mobile 4.6.1

Android versionCode 121; builds on published 4.6.0.

4.6.1 compiles the launch-recovery work and the Ask Cyclone workspace into one update. There is no per-app pipeline. Named-app aliases, web fallbacks, and planner landings are the same generic path for every installed app.

4.6.0 could stop a correct `phone.open_app` after one action. A leftover Layer-2 lease became `CAPABILITY_UNAVAILABLE`, then HARD_BLOCKER. Separate 4.6.0 runs also launched the website after a successful app open, rejected launches as STALE_OBSERVATION, and treated OpenRouter timeouts / HTTP 400 as task death.

## Ask Cyclone

Ask Cyclone is a workspace, not a dashboard. Open it and the only things on screen are the greeting, a few suggestions, and the composer.

- Empty state is **Ready when you are**. Tell Cyclone what to do on your phone.
- The composer is the only persistent control. Type, send, or hold the mic.
- **+** opens a capability drawer: Camera, Photos, Files & photos, Share screen, screenshot, open app, explain this screen, routines, model & intelligence. Nothing from that drawer stays on the main page.
- Voice is a dedicated full-screen mode, not a second composer.
- Running phone work stays a compact live object above the composer. Success, action needed, and stop reply stay in that same strip.
- Model choice stays in the header pill. Intelligence details open only when asked.

Home’s + drawer uses the same tiles so Camera and Photos are one tap.

## Launches and recovery

- Ask Cyclone / default-foreground tasks release a leftover Layer-2 lease before mutating. Armed workspace jobs stay queued.
- Scope failures keep a typed code (`WORKSPACE_SCOPE_CONFLICT`, `TARGET_SCOPE_MISMATCH`, `ACTION_FAILED`) instead of collapsing into `CAPABILITY_UNAVAILABLE`.
- Generic capability misses are retryable. Only a policy denial or a tool the model invented (`not exposed`) is a hard blocker.
- After a failed mutation Cyclone still re-observes the same session, unless Accessibility is down or the human owns input.
- `phone.open_app` / `phone.launch_intent` do not require the pre-launch observation ID. Launcher churn is not STALE_OBSERVATION.
- Website fallback runs only when the app is not installed (`APP_NOT_FOUND`). A successful (or in-progress) open stays in that app.
- When several app names appear, destination cues (`open` / `on` / `in`) beat instrument cues (`using` / `with` / `via`).
- Easy named-app opens use the launcher inventory. “open Spotify” / “open TikTok” work for any installed app, not a hardcoded alias list. Nicknames (`fb`, `insta`) still work. Installed app names are not treated as websites.
- Empty loading screens wait locally instead of spending a model turn.
- OpenRouter `provider.deadline` is recoverable. `NO_PROVIDER_AVAILABLE` and auth/model misses pause for a human instead of HARD_BLOCKER.

If the named app is not installed, the executor still reports `APP_NOT_FOUND` and recovery can use the website.

Release requires Mobile CI tests, lint, assembly and existing signing/update-continuity gates. Physical Pixel 8 remains UNVERIFIED.
