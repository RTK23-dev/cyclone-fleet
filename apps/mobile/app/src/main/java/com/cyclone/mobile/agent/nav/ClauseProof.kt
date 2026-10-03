package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import java.net.URI

data class NavigationScreen(
    val placeId: String?, val roomId: String, val page: PageContext,
    val observationId: String, val readAtMs: Long,
    val persona: AtlasPersona = AtlasPersona.LIVE,
    /** In-process equality check against the non-exported editable-state digest. */
    val signupEmailMatches: Boolean = false,
)

object ClauseProof {
    fun samePlace(expected: String?, actual: String?): Boolean {
        if (expected == null || actual == null) return false
        if (expected == actual) return true
        if (!expected.startsWith("chrome:") || !actual.startsWith("chrome:")) return false
        fun origin(value: String): String? = runCatching {
            val uri = URI(value.removePrefix("chrome:"))
            "${uri.scheme}://${uri.host.removePrefix("www.")}:${uri.port}"
        }.getOrNull()
        return origin(expected)?.let { it == origin(actual) } == true
    }

    fun check(clause: TaskClause, screen: NavigationScreen, ledger: TaskLedger, genericProof: Boolean): String? {
        if (screen.persona != AtlasPersona.LIVE || screen.observationId.isBlank() ||
            (clause.place != null && !samePlace(clause.place, screen.placeId))) return null
        val page = screen.page
        return when (clause.capability) {
            NavCapability.OPEN_PLACE -> if (clause.place != null) "Requested place observed" else null
            NavCapability.FIND_SIGNED_IN_IDENTITY -> {
                val current = LiveTaskFacts.signedInEmails(page).singleOrNull() ?: return null
                val fact = ledger.get("signed-in-email") ?: return null
                if (fact.value == current && fact.sourcePlace == screen.placeId && fact.sourceRoom == screen.roomId &&
                    fact.readAtMs == screen.readAtMs) "Live signed-in email: ${fact.maskedValue()}" else null
            }
            NavCapability.CREATE_ACCOUNT -> if (screen.signupEmailMatches && ledger.get("signed-in-email") != null)
                "Sign-up email matches the live account; submit needs phone approval" else null
            NavCapability.OPEN_DM -> {
                val person = clause.target ?: return null
                val namedHeader = page.title.equals(person, true) || page.controls.any {
                    it.label.equals(person, true) && (it.role in setOf("heading", "toolbar") ||
                        Regex("(?i)title|header|toolbar|recipient").containsMatchIn(it.selector.optString("resourceId")))
                }
                val composer = page.controls.any { it.selector.optBoolean("editable") &&
                    !it.selector.optBoolean("password") &&
                    Regex("(?i)message|reply|write a|send a|bericht").containsMatchIn(it.label + " " + it.semanticName) &&
                    !Regex("(?i)search|find").containsMatchIn(it.label + " " + it.semanticName) }
                if (namedHeader && composer) "Named conversation and message composer observed" else null
            }
            NavCapability.SEARCH_PERSON -> null // finding a label alone never proves a conversation opened
            NavCapability.SET_TIMER -> {
                val seconds = clause.target?.toLongOrNull() ?: return null
                if (com.cyclone.mobile.agent.contract.PhoneIntents.isClockApp(page.packageName) &&
                    com.cyclone.mobile.agent.contract.PhoneIntents.timerRunning(page.controls.map { it.label } + page.title, seconds))
                    "Requested timer is running" else null
            }
            NavCapability.SET_ALARM -> {
                val (hour, minute) = clause.target?.split(':')?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == 2 } ?: return null
                val alarm = com.cyclone.mobile.agent.contract.PhoneIntents.Alarm(hour, minute, null)
                val rows = page.controls.map { control ->
                    com.cyclone.mobile.agent.contract.PhoneIntents.Row(control.label,
                        if (control.selector.has("checked")) control.selector.optBoolean("checked") else null)
                }
                if (com.cyclone.mobile.agent.contract.PhoneIntents.alarmVisible(page.packageName, rows, alarm))
                    "Enabled alarm at ${alarm.hhmm} observed" else null
            }
            NavCapability.OPEN_WIFI -> if (wifiScreen(page)) "Wi-Fi settings observed" else null
            NavCapability.READ_NETWORK -> {
                val network = connectedNetwork(page) ?: return null
                if (ledger.record("connected-network", network, screen.placeId ?: return null, screen.roomId,
                        screen.persona, screen.readAtMs) || ledger.get("connected-network")?.value == network)
                    "Connected network: ${ledger.get("connected-network")!!.maskedValue()}" else null
            }
            NavCapability.USER_GOAL -> if (genericProof) "Clause-specific goal contract verified" else null
        }
    }

    fun wifiScreen(page: PageContext): Boolean = page.packageName == "com.android.settings" &&
        (Regex("(?i)^(wi[- ]?fi|internet|wireless networks)$").matches(page.title.trim()) ||
            (page.controls.any { it.label.trim().equals("Wi-Fi", true) } &&
                page.controls.any { CONNECTED.containsMatchIn(it.label) }))

    fun connectedNetwork(page: PageContext): String? {
        if (!wifiScreen(page)) return null
        val combined = page.controls.mapNotNull {
            Regex("(?i)^(.+?)[,\\n]\\s*(?:connected|verbonden)(?:[,\\s].*)?$").matchEntire(it.label.trim())
                ?.groupValues?.get(1)?.trim()?.takeIf { name -> name.length in 1..64 }
        }.distinct()
        if (combined.size == 1) return combined.single()
        val statuses = page.controls.filter { it.label.trim().lowercase() in setOf("connected", "verbonden") }
        val names = statuses.flatMap { status ->
            val parent = status.selector.optString("path").substringBeforeLast('/', "")
            if (parent.isBlank()) emptyList() else page.controls.filter {
                it !== status && it.selector.optString("path").substringBeforeLast('/', "") == parent &&
                    Regex("(?i)title|ssid").containsMatchIn(it.selector.optString("resourceId")) &&
                    it.label.length in 1..64 && !it.selector.optBoolean("editable")
            }.map { it.label }
        }.distinct()
        return names.singleOrNull()
    }

    /** Remaining seconds shown as h:mm:ss or m:ss (clock apps switch to h:mm:ss at one hour). */
    internal fun countdowns(label: String): List<Long> = com.cyclone.mobile.agent.contract.PhoneIntents.countdowns(label)

    private val CONNECTED = Regex("(?i)\\bconnected\\b|\\bverbonden\\b")
}
