package com.cyclone.mobile.setup

/**
 * Plan 30 (alpha.46): the guided setup, one short card per important setting. This part is pure: the cards, their
 * words and which card comes next. [SetupState] reads Android; the card sheet only draws.
 *
 * Cyclone never turns a setting on itself. Every card opens Android's own settings screen or Android's own permission
 * dialog, and the owner flips the switch.
 */
enum class SetupCard(
    val id: String,
    val title: String,
    val why: String,
    /** Shown only on this Android version or later. */
    val minSdk: Int = 0,
) {
    PHONE_CONTROL(
        "phone_control", "Let Cyclone use your phone",
        "Cyclone taps and reads the screen for you, only for the tasks you give it. You can stop it any time.",
    ),
    OVER_APPS(
        "over_apps", "Show Cyclone over other apps",
        "So you always see what Cyclone is doing, with a stop button, whatever app is open.",
    ),
    RESULTS(
        "results", "Tell you when it's done",
        "Cyclone sends a short note when a task finishes or needs you.",
    ),
    READ_NOTIFICATIONS(
        "read_notifications", "Answer your messages",
        "Cyclone can read new messages and reply from the notification, without opening the app. It asks before it sends.",
    ),
    KEEP_RUNNING(
        "keep_running", "Keep tasks running",
        "Stops Android from pausing Cyclone in the middle of a task.",
    ),
    BACKGROUND(
        "background", "Work in the background",
        "Cyclone works on a hidden screen, so your phone stays yours. This needs the free Shizuku app; setup shows you how.",
        minSdk = 35,
    ),
    CALENDAR(
        "calendar", "Check and add events",
        "Cyclone can see your calendar and add the events you ask for, without opening an app.",
    ),
    CONTACTS(
        "contacts", "Find people",
        "Cyclone can look up a number or email when a task needs someone. It never changes your contacts.",
    ),
    VOICE(
        "voice", "Talk to Cyclone",
        "Tap the mic and say what you need. Cyclone only listens while the mic is on.",
    ),
    DRIVER(
        "driver", "Drive with Cyclone",
        "Cyclone hears you only after you tap the orb, and keeps nothing it hears. Paying or deleting waits until you stop.",
    ),
    ;

    companion object {
        fun byId(id: String?): SetupCard? = entries.firstOrNull { it.id == id }
    }
}

/** The owner's words on the card's buttons and header. */
object SetupCopy {
    const val SETUP = "Set up"
    const val MANAGE = "Open settings"
    const val SKIP = "Skip"
    const val CLOSE = "Close setup"
    const val DONE = "Done"
    const val ON = "On"
    const val FINISHED_TITLE = "You're set"

    fun meta(left: Int): String = if (left <= 1) "SET UP CYCLONE · LAST ONE" else "SET UP CYCLONE · $left LEFT"

    fun finished(stillOff: List<SetupCard>): String = when {
        stillOff.isEmpty() -> "Everything is on. You can change any of it in Settings."
        else -> "Still off: ${stillOff.joinToString(", ") { it.shortName }}. Tap ⓘ in Settings to set it up later."
    }

    private val SetupCard.shortName: String get() = when (this) {
        SetupCard.PHONE_CONTROL -> "phone control"
        SetupCard.OVER_APPS -> "show over apps"
        SetupCard.RESULTS -> "task notes"
        SetupCard.READ_NOTIFICATIONS -> "messages"
        SetupCard.KEEP_RUNNING -> "keep running"
        SetupCard.BACKGROUND -> "background"
        SetupCard.CALENDAR -> "calendar"
        SetupCard.CONTACTS -> "contacts"
        SetupCard.VOICE -> "voice"
        SetupCard.DRIVER -> "driver mode"
    }
}

/** Which card to show. [done] = settings already on; [passed] = cards skipped in this run of the flow. */
object SetupFlow {
    /** [driving]: Driver mode is on. Its card (plan 32) is part of setup only then, to explain the microphone. */
    fun cards(sdk: Int, driving: Boolean = false): List<SetupCard> =
        SetupCard.entries.filter { sdk >= it.minSdk && (it != SetupCard.DRIVER || driving) }

    /** The next card: the first one that is not on yet and not skipped in this run; null when the flow is finished. */
    fun next(cards: List<SetupCard>, done: Set<SetupCard>, passed: Set<SetupCard>): SetupCard? =
        cards.firstOrNull { it !in done && it !in passed }

    /** Cards still to show in this run, the current one included. */
    fun left(cards: List<SetupCard>, done: Set<SetupCard>, passed: Set<SetupCard>): Int =
        cards.count { it !in done && it !in passed }

    fun stillOff(cards: List<SetupCard>, done: Set<SetupCard>): List<SetupCard> = cards.filter { it !in done }

    /**
     * Whether the flow opens by itself: only when a card is off that the owner has never been shown (first run, or a
     * new card after an update). Skipping or closing marks cards as seen, so it never nags.
     */
    fun shouldOpen(cards: List<SetupCard>, done: Set<SetupCard>, seen: Set<String>): Boolean =
        cards.any { it !in done && it.id !in seen }
}
