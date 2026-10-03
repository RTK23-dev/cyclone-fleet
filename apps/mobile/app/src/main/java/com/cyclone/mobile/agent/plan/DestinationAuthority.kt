package com.cyclone.mobile.agent.plan

import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.fastpath.FastPathLanding

/**
 * Destination-scoped execution authority for a multi-app trajectory.
 *
 * Waypoint SCENE until=goal_contract used to mean the whole user request, so Gmail never
 * advanced and Facebook-in-Chrome never became the current landing. This object is the
 * single place that decides a destination's until-condition, whether the current page
 * satisfies it, and which phone tool should run for the current waypoint.
 *
 * Raw account addresses stay in memory for the active run. JSON, traces and consumer
 * stages receive only a masked form.
 */
object DestinationAuthority {
    const val UNTIL_APP_FOREGROUND = "app_foreground"
    const val UNTIL_HOST_VISIBLE = "host_visible"
    const val UNTIL_CLEAR = "clear"
    const val UNTIL_ACCOUNT_OBSERVED = "account_observed"
    const val UNTIL_LOGIN_WALL = "login_wall"
    const val UNTIL_GOAL_CONTRACT = "goal_contract"
    const val UNTIL_DESTINATION_READY = "destination_ready"

    private val EMAIL = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val EMAIL_LOOKUP = Regex("(?i)\\b(e-?mail|address|account)\\b")
    private val FIND = Regex("(?i)\\b(find|search|look for|locate|check|which|what|current logged[- ]?in|signed[- ]?in)\\b")
    /**
     * An explicit ask about signing in ("log in to Facebook", "check my login status"). "My logged in
     * email" describes an account; it is not a request to check or change login state.
     */
    private val LOGIN_ASK = Regex(
        "(?i)\\b(?:log\\s*in|sign\\s*in)\\b(?!\\s*(?:e-?mail|account|address|user))|\\blog\\s*in\\s+status\\b|\\blogin\\s+status\\b|" +
            "\\b(?:am|are)\\s+(?:i|we)\\s+(?:logged|signed)\\s*in\\b",
    )
    private val CLAUSE_BREAK = Regex("(?i),?\\s+(?:and\\s+)?then\\s+|;\\s*|\\.\\s+")
    private val VERB = Regex("(?i)\\b(find|search for|search|look for|look up|locate|read|check|show me|show)\\b\\s+(.+)")
    private val BROWSER = Regex("(?i)\\b(chrome|browser|firefox)\\b")
    private val PLACEHOLDER_LOCAL = setOf("email", "user", "username", "name", "example", "test")
    private val PLACEHOLDER_DOMAIN = setOf("example.com", "email.com", "test.com", "domain.com")
    private val SIGN_IN = setOf("log in", "login", "sign in", "signin")
    private val SIGN_OUT = setOf("log out", "logout", "sign out", "signout")
    private val GMAIL = "com.google.android.gm"
    private val CHROME = "com.android.chrome"

    data class AccountSighting(
        val count: Int,
        val masked: List<String>,
        val raw: List<String>,
    ) {
        val unique: Boolean get() = count == 1
        val ambiguous: Boolean get() = count > 1
        val missing: Boolean get() = count == 0
        val maskedSingle: String? get() = masked.singleOrNull()
        val rawSingle: String? get() = raw.singleOrNull()
    }

    fun wantsSignedInEmail(goal: String, destination: TaskDestination): Boolean {
        if (destination.kind != "app" || destination.value != GMAIL) return false
        val clause = clauseFor(goal, destination)
        return EMAIL_LOOKUP.containsMatchIn(clause) && FIND.containsMatchIn(clause)
    }

    /** True when the user explicitly asked about signing in, not merely described an account. */
    fun asksAboutLogin(text: String): Boolean = LOGIN_ASK.containsMatchIn(text)

    /**
     * The part of the sentence that belongs to one destination: "open Gmail, check my email, then
     * go to Facebook and find Louella's DM" → "go to Facebook and find Louella's DM" for Facebook.
     * The sentence stays law; each stage just reads its own clause of it.
     */
    fun clauseFor(goal: String, destination: TaskDestination): String {
        val breaks = CLAUSE_BREAK.findAll(goal).toList()
        if (breaks.isEmpty()) return goal
        var start = 0
        for (match in breaks) {
            if (destination.index < match.range.first) return goal.substring(start, match.range.first).trim()
            start = match.range.last + 1
        }
        return goal.substring(start.coerceAtMost(goal.length)).trim().ifBlank { goal }
    }

    fun mentionsBrowser(goal: String): Boolean = BROWSER.containsMatchIn(goal)

    fun browserPackage(goal: String): String? =
        if (Regex("(?i)\\bchrome\\b").containsMatchIn(goal)) CHROME else if (mentionsBrowser(goal)) CHROME else null

    fun untilFor(goal: String, destination: TaskDestination, last: Boolean): String = when {
        wantsSignedInEmail(goal, destination) -> UNTIL_ACCOUNT_OBSERVED
        last && TaskDifficulty.hasAuthenticatedSession(goal) -> UNTIL_LOGIN_WALL
        last -> UNTIL_GOAL_CONTRACT
        else -> UNTIL_DESTINATION_READY
    }

    fun workKind(goal: String, destination: TaskDestination, last: Boolean): WaypointKind =
        if (untilFor(goal, destination, last) == UNTIL_LOGIN_WALL) WaypointKind.STOP_HUMAN else WaypointKind.SCENE

    fun objective(goal: String, destination: TaskDestination, last: Boolean): String {
        val label = labelFor(destination, goal)
        val clause = clauseFor(goal, destination)
        return when {
            wantsSignedInEmail(goal, destination) -> "Finding the signed-in email address"
            usesSelectedEmailSignIn(goal, destination, last) -> "Signing in with the selected email"
            asksAboutLogin(clause) -> "Checking $label login status"
            else -> userVerbObjective(clause, destination)
                ?: if (destination.kind == "host") "Opening $label" else "Working in $label"
        }
    }

    /** "go to facebook and find the dm of Louella" → "Finding the dm of Louella". Never a template. */
    private fun userVerbObjective(clause: String, destination: TaskDestination): String? {
        val match = VERB.find(clause) ?: return null
        val verb = match.groupValues[1].lowercase()
        val rest = match.groupValues[2].trim().trimEnd('.', '!', '?', ',', ';').take(60).trim()
        if (rest.isBlank() || EMAIL.containsMatchIn(rest)) return null
        val gerund = when {
            verb.startsWith("find") || verb == "locate" || verb.startsWith("look") -> "Finding"
            verb.startsWith("search") -> "Searching for"
            verb == "read" -> "Reading"
            verb == "check" -> "Checking"
            else -> "Showing"
        }
        return "$gerund $rest"
    }

    /** Facebook-in-Chrome (host login) after an email lookup — not a native "check login status" ask. */
    private fun usesSelectedEmailSignIn(goal: String, destination: TaskDestination, last: Boolean): Boolean {
        if (untilFor(goal, destination, last) != UNTIL_LOGIN_WALL) return false
        return destination.kind == "host" || (EMAIL_LOOKUP.containsMatchIn(goal) && FIND.containsMatchIn(goal))
    }

    fun labelFor(destination: TaskDestination, goal: String): String = when (destination.kind) {
        "host" -> {
            val site = hostLabel(destination.value)
            if (mentionsBrowser(goal)) "Chrome · $site" else site
        }
        else -> packageLabel(destination.value)
    }

    fun packageLabel(packageName: String): String {
        val aliases = FastPathLanding.APP_PACKAGE_ALIASES.filterValues { it == packageName }.keys
        val best = aliases.maxByOrNull { it.length }
        if (best != null) {
            return best.split(Regex("\\s+")).joinToString(" ") { part ->
                part.replaceFirstChar { ch -> ch.uppercase() }
            }
        }
        return packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            .takeIf { it.isNotBlank() && it.length <= 24 } ?: "App"
    }

    fun hostLabel(host: String): String {
        val root = host.removePrefix("www.")
        FastPathLanding.APP_PACKAGE_ALIASES.values.distinct().forEach { pkg ->
            val fallback = FastPathLanding.webFallback(pkg) ?: return@forEach
            val fallbackHost = fallback.substringAfter("://").substringBefore('/').removePrefix("www.")
            if (fallbackHost == root || fallbackHost.substringBefore('.') == root.substringBefore('.')) {
                return packageLabel(pkg)
            }
        }
        return root.substringBefore('.').replaceFirstChar { it.uppercase() }
    }

    fun landingPackage(destination: TaskDestination, goal: String): String? = when (destination.kind) {
        "app" -> destination.value
        "host" -> browserPackage(goal)
        else -> null
    }

    fun landingUri(destination: TaskDestination): String? =
        if (destination.kind == "host") "https://${destination.value.removePrefix("www.")}" else null

    fun landingHint(waypoint: TaskWaypoint): com.cyclone.mobile.fastpath.FastPathLandingHint? = when (waypoint.kind) {
        WaypointKind.OPEN_APP -> waypoint.packageName?.takeIf { it.isNotBlank() }?.let { pkg ->
            com.cyclone.mobile.fastpath.FastPathLandingHint(
                tool = "phone.open_app",
                packageName = pkg,
                reason = "Current trajectory waypoint opens $pkg.",
            )
        }
        WaypointKind.LAUNCH_INTENT -> waypoint.uri?.takeIf { it.isNotBlank() }?.let { uri ->
            com.cyclone.mobile.fastpath.FastPathLandingHint(
                tool = "phone.launch_intent",
                packageName = waypoint.packageName,
                uri = uri,
                reason = "Current trajectory waypoint opens $uri.",
            )
        }
        else -> null
    }

    fun loginHandoffActive(trajectory: TaskTrajectory): Boolean {
        if (!trajectory.horizonPlanned) return true
        val current = trajectory.current ?: return false
        return current.kind == WaypointKind.STOP_HUMAN || current.until == UNTIL_LOGIN_WALL
    }

    fun packageMatches(waypoint: TaskWaypoint, page: PageContext): Boolean {
        val expected = waypoint.packageName.orEmpty()
        if (expected.isBlank()) return true
        return FastPathLanding.launchCandidates(expected).any { it == page.packageName }
    }

    fun hostVisible(page: PageContext, uriOrHost: String?): Boolean {
        val host = uriOrHost
            ?.substringAfter("://")
            ?.substringBefore('/')
            ?.removePrefix("www.")
            .orEmpty()
        if (host.isBlank()) return false
        val root = host.substringBefore('.')
        val haystack = buildString {
            append(page.title)
            append(' ')
            page.controls.forEach { control ->
                append(control.label)
                append(' ')
                append(control.semanticName)
                append(' ')
            }
        }.lowercase()
        return haystack.contains(host.lowercase()) || (root.length >= 4 && haystack.contains(root.lowercase()))
    }

    fun looksSignedIn(page: PageContext): Boolean {
        val labels = page.controls.map { it.label.trim().lowercase() }
        return labels.any { it in SIGN_OUT } && labels.none { it in SIGN_IN }
    }

    fun visibleEmails(page: PageContext): AccountSighting {
        val texts = buildList {
            add(page.title)
            page.controls.forEach { control ->
                add(control.label)
                add(control.semanticName)
            }
        }
        val found = EMAIL.findAll(texts.joinToString("\n"))
            .map { it.value.trim() }
            .filterNot { placeholderEmail(it) }
            .distinctBy { it.lowercase() }
            .toList()
        return AccountSighting(
            count = found.size,
            masked = found.map(::maskEmail),
            raw = found,
        )
    }

    fun maskEmail(value: String): String {
        val parts = value.split("@", limit = 2)
        if (parts.size != 2) return "***"
        val local = parts[0]
        val domain = parts[1]
        val keep = local.take(1)
        val stars = if (local.length <= 1) "*" else "***"
        return "$keep$stars@$domain"
    }

    fun sceneSatisfied(waypoint: TaskWaypoint, page: PageContext): Boolean {
        if (waypoint.kind != WaypointKind.SCENE) return false
        if (!packageMatches(waypoint, page)) return false
        if (TaskTrajectory.looksLikeLoginWall(page)) return false
        return when (waypoint.until) {
            UNTIL_ACCOUNT_OBSERVED -> visibleEmails(page).unique
            UNTIL_DESTINATION_READY -> {
                val uri = waypoint.uri
                if (!uri.isNullOrBlank()) hostVisible(page, uri) else true
            }
            UNTIL_HOST_VISIBLE -> hostVisible(page, waypoint.uri)
            UNTIL_APP_FOREGROUND -> true
            else -> false
        }
    }

    fun stopHumanSatisfied(waypoint: TaskWaypoint, page: PageContext): Boolean {
        if (page.packageName.contains("launcher", ignoreCase = true)) return false
        if (!packageMatches(waypoint, page)) return false
        if (!waypoint.uri.isNullOrBlank() && !hostVisible(page, waypoint.uri) && !looksSignedIn(page)) {
            return false
        }
        return !TaskTrajectory.looksLikeLoginWall(page)
    }

    private fun placeholderEmail(value: String): Boolean {
        val lower = value.lowercase()
        val local = lower.substringBefore('@')
        val domain = lower.substringAfter('@', "")
        return local in PLACEHOLDER_LOCAL || domain in PLACEHOLDER_DOMAIN ||
            local.startsWith("noreply") || domain.endsWith(".invalid")
    }
}
