package com.cyclone.mobile.mind.modes

/** Plan 42: the few words Instant shows (and Live says when silent success is off). Pure. */
object InstantCopy {
    fun working(command: InstantCommand): String = when (command.intent) {
        InstantIntent.SWIPE -> "Swiping ${command.direction ?: "up"}"
        InstantIntent.SCROLL -> "Scrolling ${command.direction ?: "down"}"
        InstantIntent.BACK -> "Back"
        InstantIntent.HOME -> "Home"
        InstantIntent.RECENTS -> "Recent apps"
        InstantIntent.TAP -> "Tapping ${command.targetLabel ?: command.target.orEmpty()}"
        InstantIntent.OPEN_APP -> "Opening ${command.targetLabel ?: command.target.orEmpty()}"
        InstantIntent.CAMERA -> "Opening the camera"
        InstantIntent.PHOTO -> "Taking a photo"
        InstantIntent.SELFIE -> "Taking a selfie"
        InstantIntent.CALL -> "Calling ${command.targetLabel ?: command.target.orEmpty()}"
        InstantIntent.TIMER -> "Setting a timer"
        InstantIntent.ALARM -> "Setting an alarm"
        InstantIntent.FLASHLIGHT -> "Flashlight ${command.direction ?: "on"}"
        InstantIntent.VOLUME -> "Volume ${command.direction ?: "up"}"
        InstantIntent.MEDIA -> "Media"
    }.trim()

    /** The last thing done, as a short sentence ("Took the photo."). */
    fun done(outcome: InstantOutcome): String =
        outcome.lines.lastOrNull()?.replaceFirstChar { it.uppercase() }?.let { if (it.endsWith(".")) it else "$it." } ?: "Done."
}
