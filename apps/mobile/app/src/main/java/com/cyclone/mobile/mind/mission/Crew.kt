package com.cyclone.mobile.mind.mission

/**
 * Parallel sessions (plan 26 §6, alpha.65): one mission in front (the owner's screen, the overlay, the pill, the task
 * card) and up to two working behind it, each on its own background screen. Pure rules; [MindMissions] applies them.
 */
object Crew {
    /** Missions that may work behind the front one, by the phone's memory: 2 from 8 GB, 1 from 6 GB, none below. */
    fun behindSlots(totalMemoryBytes: Long): Int = when {
        totalMemoryBytes >= 7_500L * MB -> 2
        totalMemoryBytes >= 5_500L * MB -> 1
        else -> 0
    }

    private const val MB = 1_000_000L

    data class Facts(
        /** The owner allowed tasks at the same time (Settings → AI). */
        val enabled: Boolean,
        /** A mission is in front now. */
        val frontLive: Boolean,
        val behindRunning: Int,
        val slots: Int,
        /** Background work is ready on this phone (null) or why not. */
        val backgroundBlocker: String?,
        /** The request is a Cyclone Lab run: measurements always run alone, in front. */
        val lab: Boolean = false,
        /** The goal needs the owner's hands (sign in, camera, a code): it cannot work behind the screen. */
        val needsHands: Boolean = false,
    )

    sealed class Admit {
        data object Front : Admit()
        data object Behind : Admit()
        data class Queue(val reason: String) : Admit()
    }

    fun admit(facts: Facts): Admit = when {
        !facts.frontLive -> Admit.Front
        facts.lab -> Admit.Queue("A Lab run always works alone.")
        !facts.enabled -> Admit.Queue("Tasks at the same time are off.")
        facts.slots <= 0 -> Admit.Queue("This phone has too little memory for a second task at the same time.")
        facts.backgroundBlocker != null -> Admit.Queue(facts.backgroundBlocker)
        facts.needsHands -> Admit.Queue("This task needs you on the screen, so it runs after the current one.")
        facts.behindRunning >= facts.slots -> Admit.Queue("${facts.behindRunning} ${if (facts.behindRunning == 1) "task is" else "tasks are"} already working behind your screen.")
        else -> Admit.Behind
    }

    /** The goal needs the owner's hands: the same words the start policy uses to keep a task on the screen. */
    fun needsHands(goal: String): Boolean = HANDS.containsMatchIn(goal)

    private val HANDS = Regex("(?i)\\b(sign in|log in|login|inloggen|password|wachtwoord|camera|photo|foto|selfie|scan|captcha)\\b")

    /**
     * A mission behind the owner's screen may not touch the owner's screen. Until it has its own background screen,
     * only these tools run: opening its app (which gives it that screen), the no-screen tools and everything that is
     * not a phone tool at all.
     */
    val BEHIND_WITHOUT_SCREEN = setOf("open_app", "set_timer", "set_alarm")

    /** What a behind mission is told when it tries to use the owner's screen; null when the tool may run. */
    fun behindRefusal(tool: String, hasOwnScreen: Boolean): String? {
        if (hasOwnScreen || tool in BEHIND_WITHOUT_SCREEN) return null
        return "NOT RUN: this task works behind the owner's screen, and no app is open for it yet. Start with open_app; " +
            "Cyclone opens the app on a background screen of its own. The owner's screen is theirs."
    }

    /** How a behind mission begins: it never reads the owner's screen, so it is told where it stands instead. */
    const val BEHIND_SITUATION = "Time: %s\nYou work behind the owner's screen while another Cyclone task has the front: the owner keeps " +
        "using their phone and you never see or touch their screen. Nothing is open for you yet. Start with open_app: Cyclone " +
        "opens the app on a background screen of your own. If a step needs the owner's screen, Cyclone waits and brings this " +
        "task to the front when the other one ends."

    /** Which behind mission comes to the front when the front one ends: the one that started first. */
    fun next(behind: List<Pair<String, Long>>): String? = behind.minByOrNull { it.second }?.first

    /** How a behind mission is named on the owner's cards. */
    fun label(goal: String): String = "For “${goal.trim().replace(Regex("\\s+"), " ").take(40).let { if (goal.trim().length > 40) "$it…" else it }}”:"
}
