package com.cyclone.mobile.agent.plan

import com.cyclone.mobile.agent.contract.GoalContractCompiler
import com.cyclone.mobile.agent.contract.GoalRequirementKind
import com.cyclone.mobile.fastpath.FastPathLanding

enum class TaskDifficultyTier {
    EASY,
    MEDIUM,
    HARD,
}

data class TaskDestination(
    val kind: String,
    val value: String,
    val index: Int,
)

data class TaskDifficultyAssessment(
    val goal: String,
    val tier: TaskDifficultyTier,
    val packages: Set<String>,
    val hosts: Set<String>,
    val destinations: List<TaskDestination>,
    val reasons: List<String>,
) {
    val destinationCount: Int get() = destinations.size
    val localHardPlan: Boolean get() = tier == TaskDifficultyTier.HARD && destinationCount >= 2
}

/**
 * Three execution tiers, decided locally from the ask:
 * EASY — named app / website open only. Reflex landing, no model.
 * MEDIUM — one app or site, in-scene work. Page agent after landing.
 * HARD — two or more destinations. Local waypoint plan when named; model plan only if unnamed.
 */
object TaskDifficulty {
    private val EXTRA_WORK = Regex(
        "(?i)\\b(search|type|send|buy|order|post|message|dm|text|tell|reply|like|follow|" +
            "log\\s*in|sign\\s*in|scroll|click|tap|then|write|draft|fill|book|apply|compare|research)\\b",
    )
    private val HOST = Regex("(?i)\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b")
    private val BROWSERS = setOf("com.android.chrome", "com.chrome.beta", "org.mozilla.firefox", "com.microsoft.emmx")
    private val SYSTEM = listOf("launcher", "systemui", "inputmethod", "permissioncontroller")

    fun classify(goal: String): TaskDifficultyTier = assess(goal).tier

    fun assess(goal: String): TaskDifficultyAssessment {
        val clean = goal.trim()
        if (clean.isBlank()) {
            return TaskDifficultyAssessment(clean, TaskDifficultyTier.MEDIUM, emptySet(), emptySet(), emptyList(), listOf("empty"))
        }
        val destinations = destinationsInOrder(clean)
        val packages = destinations.filter { it.kind == "app" }.map { it.value }.toSet()
        val hosts = destinations.filter { it.kind == "host" }.map { it.value }.toSet()
        val reasons = mutableListOf<String>()
        val tier = when {
            destinations.size >= 2 -> {
                reasons += "destinations=${destinations.size}"
                TaskDifficultyTier.HARD
            }
            isEasy(clean, destinations) -> {
                reasons += "open-only"
                TaskDifficultyTier.EASY
            }
            else -> {
                reasons += "one-destination-scene"
                TaskDifficultyTier.MEDIUM
            }
        }
        return TaskDifficultyAssessment(clean, tier, packages, hosts, destinations, reasons)
    }

    fun isNamedAppOpenOnly(goal: String): Boolean {
        val named = FastPathLanding.namedApp(goal) ?: return false
        if (GoalContractCompiler.isSimpleWebNavigation(goal)) return false
        if (EXTRA_WORK.containsMatchIn(goal)) return false
        val leftover = goal.lowercase()
            .replace(named.first, " ", ignoreCase = true)
            .replace(Regex("(?i)\\b(open|launch|start|go to|navigate to|please|the|app|my)\\b"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        return leftover.isEmpty()
    }

    fun isEasy(goal: String): Boolean = assess(goal).tier == TaskDifficultyTier.EASY

    private fun isEasy(goal: String, destinations: List<TaskDestination>): Boolean {
        if (destinations.size > 1) return false
        return GoalContractCompiler.isSimpleWebNavigation(goal) || isNamedAppOpenOnly(goal)
    }

    fun namedAppCount(goal: String): Int = namedPackages(goal).size

    fun namedPackages(goal: String): Set<String> = destinationsInOrder(goal)
        .filter { it.kind == "app" }
        .map { it.value }
        .toSet()

    fun destinationsInOrder(goal: String): List<TaskDestination> {
        val lower = goal.lowercase()
        val found = mutableListOf<TaskDestination>()
        val packages = mutableSetOf<String>()
        FastPathLanding.namedAppHits(goal)
            .filterNot { it.instrumentOnly }
            .forEach { hit ->
                if (hit.packageName in packages) return@forEach
                packages += hit.packageName
                found += TaskDestination("app", hit.packageName, hit.index)
            }
        val hosts = mutableSetOf<String>()
        HOST.findAll(lower).forEach { match ->
            val host = match.value.removePrefix("www.")
            if (host == "android.com" || host in hosts) return@forEach
            if (packages.any { FastPathLanding.webFallback(it)?.contains(host) == true }) return@forEach
            hosts += host
            found += TaskDestination("host", host, match.range.first)
        }
        val ordered = found.sortedBy { it.index }
        if (hosts.isNotEmpty()) {
            return ordered.filterNot { it.kind == "app" && it.value in BROWSERS }
        }
        return preferWebsiteInsideNamedBrowser(goal, ordered)
    }

    /**
     * "Go to Chrome and log in to Facebook" is a browser-site destination, not the native Facebook app.
     * Gmail as a prior app destination is preserved. Chrome itself is not a user outcome.
     */
    private fun preferWebsiteInsideNamedBrowser(goal: String, ordered: List<TaskDestination>): List<TaskDestination> {
        val browserIndex = ordered.filter { it.kind == "app" && it.value in BROWSERS }.minOfOrNull { it.index }
            ?: return ordered
        val wantsWebsite = Regex(
            "(?i)\\b(log\\s*in|sign\\s*in|website|in chrome|using chrome|go to chrome|open chrome)\\b",
        ).containsMatchIn(goal)
        if (!wantsWebsite) return ordered
        val rewritten = mutableListOf<TaskDestination>()
        ordered.forEach { destination ->
            if (destination.kind == "app" && destination.value in BROWSERS) return@forEach
            if (
                destination.kind == "app" &&
                destination.index >= browserIndex &&
                destination.value !in BROWSERS
            ) {
                val host = FastPathLanding.webFallback(destination.value)
                    ?.substringAfter("://")
                    ?.substringBefore('/')
                    ?.removePrefix("www.")
                if (host != null) {
                    rewritten += TaskDestination("host", host, destination.index)
                    return@forEach
                }
            }
            rewritten += destination
        }
        return rewritten.ifEmpty { ordered }
    }

    fun hasAuthenticatedSession(goal: String): Boolean =
        GoalContractCompiler.compile(goal).requirements.any { it.kind == GoalRequirementKind.AUTHENTICATED_SESSION }

    fun familyOf(packageName: String): String {
        val lower = packageName.lowercase()
        return when {
            lower.startsWith("com.facebook.") -> "facebook"
            lower.startsWith("com.whatsapp") -> "whatsapp"
            lower.startsWith("com.instagram.") -> "instagram"
            lower in BROWSERS || lower.contains("browser") -> "browser"
            SYSTEM.any { lower.contains(it) } -> "system"
            lower.startsWith("com.cyclone.") -> "cyclone"
            else -> packageName
        }
    }

    fun nativeFamilies(packages: Set<String>): Set<String> = packages.map(::familyOf)
        .filterNot { it in setOf("system", "cyclone") }
        .toSet()

    fun evidenceFamilies(packages: Set<String>, goal: String): Set<String> {
        val families = nativeFamilies(packages)
        val named = namedPackages(goal).map(::familyOf).toSet()
        val nativeNamed = named.filterNot { it == "browser" }
        return if (nativeNamed.size <= 1 && destinationsInOrder(goal).count { it.kind == "app" && it.value !in BROWSERS } <= 1) {
            families.filterNot { it == "browser" }.toSet()
        } else {
            families
        }
    }

    fun hardWaypoints(goal: String): List<TaskWaypoint>? {
        val assessment = assess(goal)
        if (!assessment.localHardPlan) return null
        val waypoints = mutableListOf<TaskWaypoint>()
        assessment.destinations.forEachIndexed { index, destination ->
            val last = index == assessment.destinations.lastIndex
            val pkg = DestinationAuthority.landingPackage(destination, goal)
            val uri = DestinationAuthority.landingUri(destination)
            when (destination.kind) {
                "app" -> waypoints += TaskWaypoint(
                    WaypointKind.OPEN_APP,
                    packageName = destination.value,
                    until = DestinationAuthority.UNTIL_APP_FOREGROUND,
                    summary = "Open ${DestinationAuthority.packageLabel(destination.value)}",
                )
                "host" -> waypoints += TaskWaypoint(
                    WaypointKind.LAUNCH_INTENT,
                    packageName = pkg,
                    uri = uri,
                    until = DestinationAuthority.UNTIL_HOST_VISIBLE,
                    summary = "Open ${DestinationAuthority.labelFor(destination, goal)}",
                )
            }
            waypoints += TaskWaypoint(
                WaypointKind.LOCAL_INTERRUPTIONS,
                packageName = pkg,
                uri = uri,
                until = DestinationAuthority.UNTIL_CLEAR,
                summary = "Dismiss cookie and notice banners",
            )
            val until = DestinationAuthority.untilFor(goal, destination, last)
            waypoints += TaskWaypoint(
                DestinationAuthority.workKind(goal, destination, last),
                packageName = pkg,
                uri = uri,
                until = until,
                summary = DestinationAuthority.objective(goal, destination, last),
            )
        }
        waypoints += TaskWaypoint(
            WaypointKind.DONE,
            until = DestinationAuthority.UNTIL_GOAL_CONTRACT,
            summary = "Finish when the original goal is verified",
        )
        return waypoints
    }
}
