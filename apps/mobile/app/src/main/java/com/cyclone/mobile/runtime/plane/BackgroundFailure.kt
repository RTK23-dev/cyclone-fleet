package com.cyclone.mobile.runtime.plane

/**
 * Plan 28: what a failed action on a background screen means for the plane. Before, every background failure carried
 * the same text ("could not complete in its current scope") and any of them sent the task to the owner's screen: a
 * page that changed under a tap, or a gesture that missed, moved the whole task. Now only a step that truly cannot be
 * done in the background does. Pure; reads the reason code the executor keeps in the failure text.
 */
enum class BackgroundFailure {
    /** Ordinary: the page changed, a control was not found, the tap missed. The Mind looks again; the plane stays. */
    ORDINARY,
    /** This step cannot be done on a background screen: it goes to the owner's screen. */
    NEEDS_SCREEN,
    /** The owner opened the app on their screen: the watchdog yields it to them (never taken from them). */
    OWNER_HAS_APP,
    /** Cyclone lost input authority on a screen it still holds (a pause nobody undid): take it back. */
    AUTHORITY_LOST,
    /** The background screen, its service or its app is gone: the watchdog's recovery ladder repairs it. */
    PLANE_BROKEN,
    /** The phone is locked: the mission waits for the unlock; nothing moves. */
    LOCKED,
    ;

    companion object {
        fun classify(text: String): BackgroundFailure = when {
            "SCREEN_LOCKED" in text -> LOCKED
            "FOREGROUND_REQUIRED" in text -> OWNER_HAS_APP
            "UNSUPPORTED" in text -> NEEDS_SCREEN
            "input authority expired" in text || "HUMAN_HAS_CONTROL" in text -> AUTHORITY_LOST
            BROKEN.any { it in text } -> PLANE_BROKEN
            else -> ORDINARY
        }

        private val BROKEN = listOf("DISPLAY_GONE", "BACKEND_DISCONNECTED", "TASK_GONE", "BACKGROUND_MODE_UNAVAILABLE",
            "workspace is unavailable", "ACCESSIBILITY_NOT_CONNECTED")
    }
}
