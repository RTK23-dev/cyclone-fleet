# Root quick setup and Settings navigation

Settings > Quick setup > Enable with root applies allowlisted permissions to Cyclone in the currently active Android user. The user sees the requested capabilities and may deselect Independent phone autonomy before applying. Existing GATE confirmations remain authoritative.

Automated: notifications, microphone, calendar, overlay, precise alarms, battery exemption, notification listener, Accessibility and enabling (not selecting) the agent keyboard. Existing Accessibility services are preserved. Android battery exemption is package-wide across profiles; other permission grants are user-scoped. An enabled-but-unbound Cyclone service is removed/re-added without disabling other services. Readiness polls the real binding, not just the settings flag. Retry is safe; granted permissions remain in place after partial failure.

The trusted local setup helper runs under the existing mutation lock, refuses active/review tasks and GATE, verifies root and the current Android user, rechecks identity before every command, bounds output and times out commands. It is not an LLM tool. It never edits Magisk policy, grants another package, switches user, approves a task, or copies secrets.

Shizuku installation/start/authorization stays in the existing guided Background setup. Root startup is supported by Shizuku; this wizard does not execute an unverified shared-storage startup script. Android 15+ is still required for isolated work. MediaProjection consent remains per-session. Failed grants have manual repair links and are never displayed as ready solely because a command exited successfully.

References researched:
- https://shizuku.rikka.app/guide/setup/
- https://developer.android.com/about/versions/14/behavior-changes-14#media-projection
- AOSP AppOpsManager and NotificationShellCmd: appops SYSTEM_ALERT_WINDOW and notification allow_listener support.

Navigation: explicit Settings text button beside Home readiness; one shell-owned back action for both header and system Back. Subsection -> Settings overview -> previous main destination. Removed the duplicate small blue Settings link.

Tests: command allowlist, exact user routing, preservation of other services, repair rebind and invalid-user rejection. Local Gradle distribution download blocked; use Mobile CI. No physical root grant/navigation/device testing performed.
