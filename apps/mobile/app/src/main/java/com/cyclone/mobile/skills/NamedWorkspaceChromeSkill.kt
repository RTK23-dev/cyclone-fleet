package com.cyclone.mobile.skills

/**
 * Chrome search playbook bound to a named virtual display (`session_id != default-foreground`).
 *
 * Compiles through [SkillRouteCompiler]. Replay still mutates only via PhoneToolExecutor.
 * Named workspace playbooks cannot bind display 0 ([PlaybookHint] init / compiler).
 */
object NamedWorkspaceChromeSkill {
    const val PACKAGE = "com.android.chrome"
    const val GOAL = "Open Chrome and search for Pixel 8"
    const val SEARCH_QUERY = "Pixel 8"
    const val START_PAGE_KEY = "chrome.home"
    const val OMNIBOX_PAGE_KEY = "chrome.omnibox"
    const val RESULTS_PAGE_KEY = "chrome.results"
    const val ACCEPTANCE_SESSION_ID = "named-vd"
    const val ACCEPTANCE_DISPLAY_ID = 7

    private const val LAUNCHER_PAGE_KEY = "chrome.launcher"
    private const val OMNIBOX_HINT = "Search or type web address"

    fun playbook(
        sessionId: String = ACCEPTANCE_SESSION_ID,
        displayId: Int = ACCEPTANCE_DISPLAY_ID,
        successCount: Int = SkillRouteCompiler.MIN_SUCCESSES,
    ): PlaybookHint {
        val steps = listOf(
            PlaybookHintStep(
                nl = "Open Chrome",
                tool = "phone.open_app",
                selector = SemanticSelector(packageName = PACKAGE),
                beforePageKey = LAUNCHER_PAGE_KEY,
                afterPageKey = START_PAGE_KEY,
                expectedPageChange = true,
            ),
            PlaybookHintStep(
                nl = "Submit search for $SEARCH_QUERY",
                tool = "phone.click",
                selector = SemanticSelector(text = OMNIBOX_HINT),
                beforePageKey = START_PAGE_KEY,
                afterPageKey = RESULTS_PAGE_KEY,
                expectedPageChange = true,
            ),
        )
        return PlaybookHint(
            packageName = PACKAGE,
            goal = GOAL,
            goalSignature = PlaybookGoal.signature(GOAL),
            startPageKey = steps.first().beforePageKey,
            sessionId = sessionId,
            displayId = displayId,
            steps = steps,
            nlPlaybook = PlaybookGoal.nlPlaybook(steps),
            successCount = successCount,
            source = PlaybookSource.FAST_PATH,
            lastSuccessAtMs = 1_000L,
        )
    }

    fun compile(
        sessionId: String = ACCEPTANCE_SESSION_ID,
        displayId: Int = ACCEPTANCE_DISPLAY_ID,
    ): CompiledSkillRoute {
        val result = SkillRouteCompiler.compile(playbook(sessionId, displayId))
        return requireNotNull(result.route) { result.rejected ?: "Chrome named-VD skill failed to compile" }
    }
}
