package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.agent.plan.*
import com.cyclone.mobile.fastpath.FastPathLanding
import org.json.JSONArray
import org.json.JSONObject

enum class NavCapability { OPEN_PLACE, FIND_SIGNED_IN_IDENTITY, CREATE_ACCOUNT, OPEN_DM, SEARCH_PERSON, SET_TIMER, SET_ALARM, OPEN_WIFI, READ_NETWORK, USER_GOAL }
enum class ClauseStatus(val wire: String) { PENDING("pending"), ACTIVE("active"), VERIFIED("verified"), NEEDS_APPROVAL("needs-approval"), FAILED("failed") }

data class TaskClause(
    val id: String,
    val text: String,
    val place: String?,
    val capability: NavCapability,
    val doneWhen: String,
    val target: String? = null,
    val status: ClauseStatus = ClauseStatus.PENDING,
    val proof: String? = null,
    /** A ledger fact this clause reuses from an earlier clause ("with that Gmail" → signed-in-email). */
    val consumes: String? = null,
) {
    fun toJson() = JSONObject().put("id", id).put("text", text.take(500)).put("place", place ?: JSONObject.NULL)
        .put("capability", capability.name).put("doneWhen", doneWhen).put("status", status.wire)
        .put("proof", proof ?: JSONObject.NULL).put("consumes", consumes ?: JSONObject.NULL)
}

/** Bind meanings and evidence, never a click script. Unknown meanings keep the user's clause. */
object ClauseCompiler {
    /**
     * Clause runs are for sentences that need them: several clauses, or one clause whose proof is stronger than the
     * ordinary goal contract (identity, account, DM, timer, network). A plain "open X" keeps Stage 1 Fast Path and the
     * existing completion contract.
     */
    fun needsClauseRun(clauses: List<TaskClause>): Boolean =
        clauses.any { it.place != null || it.capability in CLOCK_CAPABILITIES } &&
        (clauses.size > 1 || clauses.any { it.capability !in PLAIN })

    private val CLOCK_CAPABILITIES = setOf(NavCapability.SET_ALARM, NavCapability.SET_TIMER)
    private val PLAIN = setOf(NavCapability.OPEN_PLACE, NavCapability.OPEN_WIFI, NavCapability.USER_GOAL)

    fun compile(goal: String, nativeAvailable: (String) -> Boolean? = { null }): List<TaskClause> {
        var previousPlace: String? = null
        val pieces = SPLIT.split(goal).map(::tidy).filter(String::isNotBlank)
            .flatMap { splitOnActions(it) }
        // Keep the whole sentence if a pathological input exceeds the bounded clause budget.
        return (if (pieces.size > 16) listOf(goal) else pieces).mapIndexed { index, text ->
            var place = bindPlace(text) ?: homePlace(text) ?: previousPlace
            if (place?.startsWith("package:") == true) {
                val pkg = place.removePrefix("package:")
                if (nativeAvailable(pkg) == false) FastPathLanding.webFallback(pkg)?.let { origin ->
                    place = "chrome:${origin.trimEnd('/')}"
                }
            }
            val clockIntent = com.cyclone.mobile.agent.contract.PhoneIntents.timer(text) != null ||
                com.cyclone.mobile.agent.contract.PhoneIntents.alarm(text) != null
            // Alarms and timers are proven in whichever clock app the phone has (Google, Samsung…), so the clause is
            // not bound to one package; the clock intent route opens the right app itself.
            if (clockIntent) place = null
            previousPlace = place ?: previousPlace
            val capability = when {
                IDENTITY.containsMatchIn(text) && (text.contains("gmail", true) || text.contains("email", true)) -> NavCapability.FIND_SIGNED_IN_IDENTITY
                SIGNUP.containsMatchIn(text) -> NavCapability.CREATE_ACCOUNT
                DM.containsMatchIn(text) -> NavCapability.OPEN_DM
                com.cyclone.mobile.agent.contract.PhoneIntents.timer(text) != null -> NavCapability.SET_TIMER
                com.cyclone.mobile.agent.contract.PhoneIntents.alarm(text) != null -> NavCapability.SET_ALARM
                NETWORK.containsMatchIn(text) -> NavCapability.READ_NETWORK
                WIFI.containsMatchIn(text) -> NavCapability.OPEN_WIFI
                TaskDifficulty.isNamedAppOpenOnly(text) || com.cyclone.mobile.agent.contract.GoalContractCompiler.isSimpleWebNavigation(text) -> NavCapability.OPEN_PLACE
                else -> NavCapability.USER_GOAL
            }
            val target = when (capability) {
                NavCapability.OPEN_DM, NavCapability.SEARCH_PERSON -> person(text)
                NavCapability.SET_TIMER -> com.cyclone.mobile.agent.contract.PhoneIntents.timer(text)?.seconds?.toString()
                NavCapability.SET_ALARM -> com.cyclone.mobile.agent.contract.PhoneIntents.alarm(text)?.hhmm
                else -> null
            }
            val consumes = if (index > 0 && CONSUMED_EMAIL.containsMatchIn(text)) "signed-in-email" else null
            TaskClause("clause-${index + 1}", text, place, capability, when (capability) {
                NavCapability.FIND_SIGNED_IN_IDENTITY -> "live signed-in-email recorded from account UI"
                NavCapability.CREATE_ACCOUNT -> "sign-up email field matches the live ledger; approval before submit"
                NavCapability.OPEN_DM -> "named conversation open with a message composer"
                NavCapability.SEARCH_PERSON -> "named person found in current results"
                NavCapability.SET_TIMER -> "requested duration running in the timer UI"
                NavCapability.SET_ALARM -> "enabled alarm at the requested time listed in Clock"
                NavCapability.OPEN_WIFI -> "Wi-Fi settings visible"
                NavCapability.READ_NETWORK -> "connected-network read from current Wi-Fi UI"
                NavCapability.OPEN_PLACE -> "requested app or origin visible"
                NavCapability.USER_GOAL -> "clause-specific goal contract verified on the live screen"
            }, target, consumes = consumes)
        }
    }

    private fun person(text: String): String? = listOf(
        Regex("(?i)\\b(?:dm|chat|thread|conversation)\\s+(?:of|with|from)\\s+(.+?)(?:\\s+(?:on|in|using)\\s+|$)"),
        Regex("(?i)\\b(?:find|open)\\s+(.+?)[’']s\\s+(?:dm|chat|thread)\\b"),
    ).firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.trim()?.takeIf { name -> name.length in 1..80 } }

    /**
     * "A and B" becomes two clauses only when B is a new action in a different place ("check my Gmail and make a
     * Facebook account"). "open Clock and set an alarm" or "open Chrome and go to nu.nl" stay one clause: the action
     * happens where the first half landed.
     */
    private fun splitOnActions(text: String): List<String> {
        val cuts = AND_ACTION.findAll(text).map { it.range }.toList()
        if (cuts.isEmpty()) return listOf(text)
        // Merged halves keep the owner's own words ("and", "en", ","), so the clause text stays the sentence.
        val starts = listOf(0) + cuts.map { it.last + 1 }
        val ends = cuts.map { it.first } + text.length
        val parts = starts.indices.map { i -> starts[i] to ends[i] }.filter { (a, b) -> tidy(text.substring(a, b)).isNotBlank() }
        val out = mutableListOf(parts.first())
        for (next in parts.drop(1)) {
            val left = out.last()
            if (sameWorkplace(tidy(text.substring(left.first, left.second)), tidy(text.substring(next.first, next.second)))) {
                out[out.lastIndex] = left.first to next.second
            } else out += next
        }
        return out.map { (a, b) -> tidy(text.substring(a, b)) }
    }

    private fun sameWorkplace(left: String, right: String): Boolean {
        val rightPlace = bindPlace(right) ?: homePlace(right) ?: return true
        val leftPlace = bindPlace(left) ?: homePlace(left) ?: return false
        if (ClauseProof.samePlace(leftPlace, rightPlace)) return true
        // "open Chrome and go to x.com": a website inside the browser the left half opened.
        return leftPlace.removePrefix("package:") in BROWSERS && rightPlace.startsWith("chrome:") &&
            TaskDifficulty.isNamedAppOpenOnly(left)
    }

    /** The place named inside this clause: a website first, then the first app (not an app only mentioned as a fact). */
    internal fun bindPlace(text: String): String? {
        val stripped = CONSUMED_REFERENCE.replace(text, " ")
        HOST.find(stripped)?.let { return "chrome:https://${it.groupValues[1].lowercase().removePrefix("www.")}" }
        val hits = FastPathLanding.namedAppHits(stripped).filterNot { it.instrumentOnly }
        val apps = hits.filter { it.packageName !in BROWSERS }
        val browserNamed = hits.any { it.packageName in BROWSERS }
        apps.firstOrNull()?.let { app ->
            if (browserNamed && WEB_CUE.containsMatchIn(stripped)) {
                FastPathLanding.webFallback(app.packageName)?.let { return "chrome:${it.trimEnd('/')}" }
            }
            return "package:${app.packageName}"
        }
        return hits.firstOrNull()?.let { "package:${it.packageName}" }
    }

    /** Where a capability lives when the clause names no app: the signed-in Google account is read in Gmail. */
    private fun homePlace(text: String): String? = when {
        IDENTITY.containsMatchIn(text) && Regex("(?i)g-?mail|google").containsMatchIn(text) -> "package:com.google.android.gm"
        else -> null
    }

    private fun tidy(value: String) = value.trim().trimEnd(',', ';', '.').trim()

    private val BROWSERS = setOf("com.android.chrome", "com.chrome.beta", "org.mozilla.firefox", "com.microsoft.emmx")
    private val SPLIT = Regex(
        "(?i),?\\s+(?:and\\s+)?then\\s+|,?\\s+(?:and\\s+)?after\\s+that,?\\s+|;\\s*|,?\\s+(?:en\\s+)?(?:daarna|vervolgens|dan)\\s+|" +
            "\\s+and\\s+(?=(?:open|launch)\\s)",
    )
    private const val VERBS = "make|create|sign|register|set|send|find|search|check|tell|show|go|navigate|open|launch|book|" +
        "add|turn|write|post|reply|call|share|look|read|copy|play|start|use|see|enter|fill|log|" +
        "maak|zet|stuur|zoek|kijk|vind|ga|meld|registreer|vertel|laat|speel|bel|deel|schrijf|vul|controleer"
    private val AND_ACTION = Regex("(?i),?\\s+(?:and|en)\\s+(?=(?:$VERBS)\\b)")
    /** A website: letters in the TLD, never the domain of an e-mail address. */
    private val HOST = Regex("(?i)(?<![@\\w.-])((?:www\\.)?(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24})(?![\\w@-])")
    private val WEB_CUE = Regex("(?i)\\b(on|in|using|via|with)\\s+(?:google\\s+)?chrome\\b|\\bwebsite\\b|\\bsite\\b|\\bbrowser\\b")
    private val CONSUMED_REFERENCE = Regex(
        "(?i)\\b(?:with|using|into|for|met|via)\\s+(?:that|this|the same|my|it'?s|dat|die|dezelfde|mijn)\\s+" +
            "(?:g-?mail(?:\\s+(?:address|account))?|e-?mail(?:\\s+address)?|account|address|adres|google account)\\b",
    )
    private val CONSUMED_EMAIL = Regex(
        "(?i)\\b(?:with|using|met)\\s+(?:that|this|the same|it'?s|dat|die|dezelfde)\\s+(?:g-?mail|e-?mail|account|address|adres)\\b|\\bwith it\\b",
    )
    private val IDENTITY = Regex("(?i)which|signed[- ]?in|logged[- ]?in|current.*(?:email|account)|ingelogd|aangemeld|welk(?:e)?\\s+(?:account|e-?mail)")
    private val SIGNUP = Regex("(?i)sign[- ]?up|register|registreer|registreren|aanmelden|account\\s+aan|(?:create|make|maak)\\s+(?:an?\\s+|new\\s+|een\\s+)?(?:[a-z]+\\s+)?account")
    private val DM = Regex("(?i)\\b(dm|direct message|conversation|thread|chat)\\b")
    private val NETWORK = Regex("(?i)connected.*network|network.*name|(?:which|what).*wi[- ]?fi")
    private val WIFI = Regex("(?i)wi[- ]?fi|wireless settings")
}

class ClauseRun(val goal: String, initial: List<TaskClause>) {
    private val rows = initial.toMutableList()
    var index: Int = 0
        private set
    val current: TaskClause? get() = rows.getOrNull(index)
    val complete: Boolean get() = rows.isNotEmpty() && index == rows.size
    fun clauses(): List<TaskClause> = rows.toList()
    fun toJson(): JSONArray = JSONArray().also { out -> rows.forEach { out.put(it.toJson()) } }

    /** Advance only the current clause. A later screen cannot retroactively prove skipped work. */
    fun observe(screen: NavigationScreen, ledger: TaskLedger, genericProof: Boolean = false): Boolean {
        val clause = current ?: return false
        if (clause.status == ClauseStatus.FAILED) return false
        val proof = ClauseProof.check(clause, screen, ledger, genericProof)
        if (proof == null) {
            if (clause.status != ClauseStatus.ACTIVE) rows[index] = clause.copy(status = ClauseStatus.ACTIVE, proof = null)
            return false
        }
        if (clause.capability == NavCapability.CREATE_ACCOUNT) {
            rows[index] = clause.copy(status = ClauseStatus.NEEDS_APPROVAL, proof = proof)
            return false
        }
        rows[index] = clause.copy(status = ClauseStatus.VERIFIED, proof = proof)
        index++
        return true
    }

    fun fail(): TaskClause? = current?.let {
        it.copy(status = ClauseStatus.FAILED).also { failed -> rows[index] = failed }
    }

    fun trajectory(screen: NavigationScreen?): TaskTrajectory {
        val steps = rows.map { clause ->
            val needsLanding = clause == current && clause.place != null && !ClauseProof.samePlace(clause.place, screen?.placeId)
            val uri = clause.place?.takeIf { it.startsWith("chrome:") }?.removePrefix("chrome:")
            TaskWaypoint(
                kind = if (needsLanding) { if (uri != null) WaypointKind.LAUNCH_INTENT else WaypointKind.OPEN_APP }
                    else WaypointKind.SCENE,
                packageName = clause.place?.takeIf { it.startsWith("package:") }?.removePrefix("package:")
                    ?: if (uri != null) "com.android.chrome" else null,
                uri = uri, until = "clause_proof", summary = clause.text,
                clauseId = clause.id, capability = clause.capability.name, doneWhen = clause.doneWhen,
            )
        }
        return TaskTrajectory(if (rows.size > 1) TaskDifficultyTier.HARD else TaskDifficultyTier.MEDIUM,
            "current", goal, steps, index, horizonPlanned = true)
    }
}
