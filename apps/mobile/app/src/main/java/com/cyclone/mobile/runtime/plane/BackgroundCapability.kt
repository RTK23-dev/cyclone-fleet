package com.cyclone.mobile.runtime.plane

/**
 * Plan 26 (A42-1): one answer to "can Cyclone work in the background right now?", with exactly one next step for the
 * owner. Pure; the Android facts come from BackgroundSetup.
 */
enum class CapabilityLevel {
    /** Background work is on and every piece is in place. */
    READY,
    /** Everything is set up, but the helper (Shizuku) is stopped: after a reboot, one tap starts it again. */
    NEEDS_START,
    /** A setup step is missing. */
    NEEDS_SETUP,
    /** The owner switched background work off. */
    OFF,
    /** This phone cannot have private background screens (Android 14 or older). */
    UNSUPPORTED,
}

enum class CapabilityAction(val label: String) {
    TURN_ON("Turn on"),
    START_HELPER("Resume"),
    OPEN_SETUP("Set up"),
}

data class BackgroundFacts(
    val ownerOn: Boolean,
    val android15: Boolean,
    val helperInstalled: Boolean,
    val helperRunning: Boolean,
    val helperAuthorized: Boolean,
    val accessibility: Boolean,
    val notifications: Boolean,
)

data class BackgroundCapability(val level: CapabilityLevel, val headline: String, val action: CapabilityAction?) {
    val ready: Boolean get() = level == CapabilityLevel.READY
}

object BackgroundCapabilities {
    fun judge(facts: BackgroundFacts): BackgroundCapability = when {
        !facts.android15 -> BackgroundCapability(CapabilityLevel.UNSUPPORTED,
            "This phone needs Android 15 or later for background screens. Cyclone works on your screen.", null)
        !facts.ownerOn -> BackgroundCapability(CapabilityLevel.OFF, "Background work is off.", CapabilityAction.TURN_ON)
        !facts.helperInstalled -> BackgroundCapability(CapabilityLevel.NEEDS_SETUP,
            "Background work needs the Shizuku helper. Set up takes about two minutes.", CapabilityAction.OPEN_SETUP)
        !facts.helperRunning -> BackgroundCapability(CapabilityLevel.NEEDS_START,
            "Background work is paused: the helper stopped (usually after a restart).", CapabilityAction.START_HELPER)
        !facts.helperAuthorized -> BackgroundCapability(CapabilityLevel.NEEDS_SETUP,
            "Allow Cyclone in the Shizuku helper.", CapabilityAction.OPEN_SETUP)
        !facts.accessibility -> BackgroundCapability(CapabilityLevel.NEEDS_SETUP,
            "Turn on Cyclone phone control in Accessibility.", CapabilityAction.OPEN_SETUP)
        !facts.notifications -> BackgroundCapability(CapabilityLevel.NEEDS_SETUP,
            "Allow Cyclone task notifications, so background work can be seen and stopped.", CapabilityAction.OPEN_SETUP)
        else -> BackgroundCapability(CapabilityLevel.READY, "Background work is on.", null)
    }

    /**
     * Whether to tell the owner now (not in the middle of a task): only when they turned background work on, it is
     * not ready for a reason they can fix, and they were not told in the last [QUIET_MS].
     */
    fun shouldNotify(capability: BackgroundCapability, lastToldAtMs: Long, nowMs: Long): Boolean =
        capability.level in setOf(CapabilityLevel.NEEDS_START, CapabilityLevel.NEEDS_SETUP) && nowMs - lastToldAtMs >= QUIET_MS

    const val QUIET_MS = 12 * 60 * 60_000L
}
