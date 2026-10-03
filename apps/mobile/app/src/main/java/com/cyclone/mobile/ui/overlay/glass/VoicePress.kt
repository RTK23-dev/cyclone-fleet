package com.cyclone.mobile.ui.overlay.glass

/**
 * How a press on the voice button turns into start / stop, kept pure so it is unit-tested without a device.
 *
 * The owner can tap to talk (tap, speak, tap again or just stop talking) or push to talk (hold while speaking, let go
 * to finish). Voice starts on the finger going down, so the press that starts it can never also stop it, and a touch
 * that lands in the first moments of listening (a bounce, a nervous double tap) is ignored.
 */
object VoicePress {
    /** Held at least this long, letting go ends the dictation (push to talk). Shorter is a tap: keep listening. */
    const val HOLD_TO_TALK_MS = 500L
    /** A stop tap only counts once voice has been listening this long. */
    const val MIN_LISTEN_MS = 800L
    /** Presses closer together than this are one press. */
    const val DEBOUNCE_MS = 400L

    enum class Action { START, STOP, NONE }

    /** The finger goes down. [lastPressAt] is the last press that did something (0 if none). */
    fun down(now: Long, listening: Boolean, listeningSince: Long, lastPressAt: Long): Action = when {
        lastPressAt > 0 && now - lastPressAt < DEBOUNCE_MS -> Action.NONE
        !listening -> Action.START
        listeningSince > 0 && now - listeningSince < MIN_LISTEN_MS -> Action.NONE
        else -> Action.STOP
    }

    /** The finger comes up [heldMs] after a press that started voice: a long hold was push to talk, so stop. */
    fun upAfterStart(heldMs: Long): Action = if (heldMs >= HOLD_TO_TALK_MS) Action.STOP else Action.NONE
}
