# Cyclone Mobile 4.0.1 — Background Task Glass

Base: main / published v4.0.0, `b8afad96b1ad4847dda333cfdd02402097881c21`. Mobile 4.0.1 / versionCode 72. Session Kernel, Fast Path, Skill Compiler and MCP identity contracts remain unchanged. Publication stays disabled; physical Pixel 8 and UI acceptance remain **UNVERIFIED**.

## Shipped

- The existing Accessibility overlay hosts a collapsed task card with the workspace app icon, exact task title/subtitle, View progress and Ask Cyclone. The window is non-focusable, touchable only within its small bottom panel and non-modal outside it, leaving the human app usable. Opening Ask expands the existing composer; minimizing returns to glass while the task continues.
- View progress uses the existing exact-session frames and Take control / Continue / Stop / GATE flows. The glass hides while progress detail owns the screen and returns when leaving it. Background tasks never use foreground frames or a display-0 mutation fallback.
- Both glass and expanded composer use the same bottom gap: **18 → 30 dp (+12 dp)**, in addition to system/IME insets. A layout-contract regression verifies the delta.
- Settings → Background tasks opens guided setup with official Shizuku Play/GitHub install links, Open Shizuku, wireless-debugging pairing/start steps, permission request and live status. Android 15+, Accessibility and task notifications are required. Notification-listener access is optional for triggers. The glass uses Accessibility's overlay authority, so SYSTEM_ALERT_WINDOW is not an extra required grant.
- The final setup row takes the user Home and checks the actual display-0 application. Starting from Cyclone similarly goes Home, then waits up to five seconds for every required check to pass before creating the workspace. Starting from another app leaves that app alone. Missing prerequisites name the failed row. Required-access loss during a task stops execution and tears down the glass. The existing target-app-on-display-0 restriction remains fail-closed.

## P0 ghost fix

Previously, an external-action flag could leave a visible Compose surface marked NOT_TOUCHABLE; progress detail did not clear that flag. Dismissal used asynchronous view removal, and the destroyed Compose lifecycle could later be reused.

All successfully added overlay windows now belong to one registry. Failure/cancel resets the chrome machine, cached task/expanded state and external-interaction flag, synchronously removes windows, disposes Compose hosts, destroys lifecycle owners, and invalidates queued callbacks by generation. Re-entry creates a fresh lifecycle. External actions hide the actual view rather than leaving a visible disabled bar. Partial attach failures clean up already-added windows. Accessibility/process destruction uses the same teardown path; Android removes process-owned windows on process death.

`OverlayChromeRuntime.overlayWindowCount()` is the device instrumentation hook: after failed startup or cancel and a main-loop drain, it must be **0**. Pure JVM regressions cover registry cleanup/idempotence, glass task phases/progress, required setup rows, non-focusable window policy, and the +12 dp layout delta. Existing session-routing and GATE regression tests remain in CI.

## Manual acceptance — UNVERIFIED

1. Cold phone: from Cyclone Settings alone, install Shizuku; enable Developer options / wireless debugging; pair, start and authorize Cyclone. Enable Accessibility and notifications. Check each row, then use the Home/recheck button. Deny each permission once and verify the row stays red with a useful next action.
2. Start a Starbucks-style multi-step order (or another compatible app) from Ask. Verify a nonzero Session Kernel display/session and a Working on this task card with the correct app icon.
3. Open Instagram on display 0. Scroll, change tabs and return Home while the task progresses. Cyclone must not tap or type into Instagram. Glass remains outside the human app's focus.
4. View progress: verify the displayed frame belongs to the task app, then Take control. Confirm the same prepared page transfers; Continue returns that task to its workspace. Confirm a GATE pauses and remains authoritative.
5. Stop, succeed, fail Shizuku startup, deny permission, disconnect Accessibility and kill/restart Cyclone. On failure/cancel, assert zero owned overlay windows; no visible unresponsive bar. Reopen Ask and verify typing/drag/dismiss on a fresh host.
6. Compare with 4.0.0: Ask/glass sit 12 dp higher. Check gesture navigation, three-button navigation, keyboard, display cutout and rotation.
7. Complete an order up to review; verify View progress and prepared-page handoff survive. Do not complete payment during testing unless intentionally authorized.

## Build and limits

Local product/security/version guards and CI-script tests run before the final checkpoint. Local Gradle cannot download its distribution in this environment; Mobile CI owns JVM tests, lint and APK assembly. The PR contains the candidate; no release publication authorization or physical-acceptance flags are enabled.

Android 15+, running/authorized Shizuku and a compatible app/OEM remain required. One hot background workspace remains the product limit. No root, second overlay service, second mutation engine, or Session OS rewrite. Physical Instagram scrolling, OEM display behavior, IME/cutout placement and actual window teardown need the checklist above.

Setup references: [official Shizuku downloads](https://shizuku.rikka.app/download/) and [wireless debugging guide](https://shizuku.rikka.app/guide/setup/#start-via-wireless-debugging).
