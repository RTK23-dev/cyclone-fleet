package com.cyclone.mobile.runtime.background

/** Intent chooses the execution plane. App discovery and screen capture never choose it. */
sealed interface ExecutionTarget {
    data object CurrentForeground : ExecutionTarget
    data object BackgroundWorkspace : ExecutionTarget
    data class Profile(val label: String) : ExecutionTarget
}

object ExecutionTargetResolver {
    fun isSimpleLaunch(request: String, appLabel: String): Boolean =
        Regex("(?i)^(?:please\\s+)?(?:open|launch)\\s+" + Regex.escape(appLabel) + "[.!]?$")
            .matches(request.trim())

    fun resolve(request: String): ExecutionTarget {
        val profile = Regex("(?i)\\b(?:use|in|on|switch to)\\s+(?:my\\s+)?(profile\\s+[\\p{L}\\p{N}_-]+)")
            .find(request)?.groupValues?.get(1)
        if (profile != null) return ExecutionTarget.Profile(profile)
        return if (Regex("(?i)\\b(?:in the background|without taking over (?:my|the) screen|background task)\\b")
                .containsMatchIn(request)) ExecutionTarget.BackgroundWorkspace
            else ExecutionTarget.CurrentForeground
    }
}
