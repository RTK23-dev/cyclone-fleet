package com.cyclone.mobile.voice

/**
 * Alpha 95: while Driver mode is on, the Drive orb is always there. The orb's windows can go missing:
 * - they were never made, because creating a window failed once or the overlay never attached;
 * - Android or a stale service token removed them;
 * - the button stayed hidden after AI mode closed;
 * - it ended up off screen after a rotation.
 *
 * Before alpha 95, nothing put the orb back, and turning Driver mode off and on did not help when the settings watcher
 * had died. This decides, on every heartbeat, what has to happen. Pure; the overlay does the window work.
 */
object OrbKeeper {
    /** How often the overlay looks while Driver mode is on. */
    const val CHECK_EVERY_MS = 2_000L
    /** Back-off between repairs that keep failing: 2 s, 4 s, 8 s … up to 30 s. */
    const val MAX_BACKOFF_MS = 30_000L

    enum class Action {
        /** All good. */
        NONE,
        /** The overlay holds an old accessibility service: attach to the live one (which rebuilds the windows). */
        REATTACH,
        /** The windows are missing or broken: remove what is left and build them again. */
        REBUILD,
        /** The button exists but is hidden while AI mode is closed: show it. */
        SHOW_BUTTON,
        /** The button is off the visible screen: put it back at its spot. */
        PLACE,
    }

    data class Look(
        val enabled: Boolean,
        /** The accessibility service the overlay works through is the one Android runs now. */
        val serviceCurrent: Boolean,
        val windowsPresent: Boolean,
        val buttonAttached: Boolean,
        val buttonVisible: Boolean,
        /** AI mode (the panel) is meant to be open: then the orb lives in the panel and the button is hidden. */
        val panelWanted: Boolean,
        val panelAttached: Boolean,
        val buttonOnScreen: Boolean,
    )

    fun decide(look: Look): Action = when {
        !look.enabled -> Action.NONE
        !look.serviceCurrent -> Action.REATTACH
        !look.windowsPresent -> Action.REBUILD
        look.panelWanted -> if (look.panelAttached) Action.NONE else Action.REBUILD
        !look.buttonAttached -> Action.REBUILD
        !look.buttonVisible -> Action.SHOW_BUTTON
        !look.buttonOnScreen -> Action.PLACE
        else -> Action.NONE
    }

    /** Rebuilds and reattaches are rate-limited after failures; showing and placing are cheap and never wait. */
    fun allowed(action: Action, failures: Int, lastRepairMs: Long, nowMs: Long): Boolean {
        if (action != Action.REBUILD && action != Action.REATTACH) return true
        if (failures <= 0) return true
        return nowMs - lastRepairMs >= backoffMs(failures)
    }

    fun backoffMs(failures: Int): Long =
        if (failures <= 0) 0L else (CHECK_EVERY_MS shl (failures - 1).coerceAtMost(4)).coerceAtMost(MAX_BACKOFF_MS)

    /** The button's top-left must leave at least a quarter of it on the screen. */
    fun onScreen(x: Int, y: Int, button: Int, width: Int, height: Int): Boolean {
        val keep = button / 4
        return x + button - keep >= 0 && y + button - keep >= 0 && x + keep <= width && y + keep <= height
    }
}
