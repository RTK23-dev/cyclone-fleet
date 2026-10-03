package com.cyclone.mobile.ai

import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentPageCard

enum class CookieInterruptionOutcome { HANDLED, NOT_APPLICABLE, AMBIGUOUS, BLOCKED_BY_USER_INTENT, UNRESOLVED }
data class CookieInterruptionDecision(val outcome: CookieInterruptionOutcome, val target: AgentElementCandidate? = null, val reason: String)

/**
 * Artemis-style transient overlay handling for cookie banners.
 *
 * Google ARTEMIS treats consent bars as time-sensitive UI: dismiss them in a local action burst
 * without a model turn or a human gate. Cyclone mirrors that:
 * 1. Prefer a unique Reject / necessary-only control (never Accept all).
 * 2. If the first surface only offers Settings, open it so the next observe can reject.
 * 3. Rank competing reject labels instead of pausing as ambiguous.
 * 4. Never ask the user to Take Over for cookies.
 */
class CookieInterruptionPolicy {
    private val attempted = mutableSetOf<String>()

    fun next(page: AgentPageCard?, goal: String): AgentElementCandidate? = evaluate(page, goal).target

    fun evaluate(page: AgentPageCard?, goal: String): CookieInterruptionDecision {
        if (page == null || !page.actionable) return CookieInterruptionDecision(CookieInterruptionOutcome.NOT_APPLICABLE, reason = "cookie.no_current_page")
        if (explicitAlternative(goal)) return CookieInterruptionDecision(CookieInterruptionOutcome.BLOCKED_BY_USER_INTENT, reason = "cookie.user_choice")
        val scene = "${page.pageSummary} ${page.pageText} ${page.controls.joinToString { it.label }}".lowercase()
        if (!Regex("\\bcookies?\\b").containsMatchIn(scene)) return CookieInterruptionDecision(CookieInterruptionOutcome.NOT_APPLICABLE, reason = "cookie.no_consent_scene")
        val actionable = page.controls.filter { clickable(it, page.observationId) }
        val consentChoices = actionable.any { normalize(it.label) in consentLabels || isAcceptLabel(it.label) }
        val reject = ranked(actionable) { isRejectLabel(it.label) && (normalize(it.label).contains("cookie") || consentChoices) }
        if (reject != null) return once(page, reject, "cookie.reject_optional")
        val settings = if (consentChoices) ranked(actionable) { isSettingsLabel(it.label) } else null
        if (settings != null) return once(page, settings, "cookie.open_settings")
        if (!consentChoices && page.controls.none { isRejectLabel(it.label) }) {
            return CookieInterruptionDecision(CookieInterruptionOutcome.NOT_APPLICABLE, reason = "cookie.no_consent_controls")
        }
        return CookieInterruptionDecision(CookieInterruptionOutcome.UNRESOLVED, reason = "cookie.reject_unavailable")
    }

    private fun once(page: AgentPageCard, target: AgentElementCandidate, reason: String): CookieInterruptionDecision {
        val key = "${page.sessionId}|${page.displayId}|${page.packageName}|${normalize(target.label)}"
        if (!attempted.add(key)) return CookieInterruptionDecision(CookieInterruptionOutcome.UNRESOLVED, reason = "cookie.effect_unverified")
        return CookieInterruptionDecision(CookieInterruptionOutcome.HANDLED, target, reason)
    }

    private fun clickable(control: AgentElementCandidate, observationId: String): Boolean =
        control.observationId == observationId && control.elementId.isNotBlank() &&
            control.evidence.optBoolean("enabled", true) && control.evidence.optBoolean("visibleToUser", true) &&
            (control.evidence.optBoolean("clickable") || control.role.lowercase() in setOf("button", "link"))

    private fun ranked(controls: List<AgentElementCandidate>, predicate: (AgentElementCandidate) -> Boolean): AgentElementCandidate? {
        val matches = controls.filter(predicate).distinctBy { it.elementId }
        if (matches.isEmpty()) return null
        return matches.minBy { control ->
            rank.indexOfFirst { token -> normalize(control.label) == token || normalize(control.label).startsWith("$token ") }
                .takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
    }

    private fun explicitAlternative(goal: String): Boolean =
        Regex("(?i)\\b(accept|allow|enable|keep|manage|customize|customise|accepteer|accepteren|toestaan|beheer)\\b.{0,40}\\bcookies?\\b|\\bcookies?\\b.{0,40}\\b(accept|allow|enable|keep|manage|customize|customise|accepteer|accepteren|toestaan|beheer)\\b").containsMatchIn(goal)

    companion object {
        private fun normalize(label: String) = label.trim().lowercase().replace(Regex("\\s+"), " ")
        fun isRejectLabel(label: String): Boolean {
            val value = normalize(label)
            if (value in rejectLabels) return true
            val short = setOf("reject", "decline", "weigeren")
            return rejectLabels.any { token ->
                token !in short && (value.startsWith("$token ") || value.endsWith(" $token"))
            }
        }
        fun isAcceptLabel(label: String): Boolean {
            val value = normalize(label)
            return value in consentLabels || value.startsWith("accept")
        }
        fun isSettingsLabel(label: String): Boolean {
            val value = normalize(label)
            return value in settingsLabels || (value.contains("cookie") && value.contains("setting"))
        }
        fun explanation(reason: String): String = when (reason) {
            "cookie.open_settings" -> "Opening cookie settings so optional tracking can be rejected."
            "cookie.reject_unavailable" -> "A cookie banner is visible without a unique reject control. Continuing the original task."
            "cookie.effect_unverified" -> "Cookie rejection was already attempted. Continuing the original task."
            else -> "Dismissing the cookie banner, then continuing your task."
        }
        private val consentLabels = setOf(
            "accept all", "accept all cookies", "accept cookies", "cookie settings", "manage cookies",
            "alles accepteren", "alle cookies accepteren", "cookies accepteren", "cookie-instellingen", "cookies beheren",
        )
        private val settingsLabels = setOf(
            "cookie settings", "manage cookies", "manage preferences", "customize", "customise",
            "preferences", "cookie-instellingen", "cookies beheren", "instellingen",
        )
        private val rejectLabels = setOf(
            "reject all cookies", "reject all", "decline all", "reject optional cookies",
            "reject non-essential cookies", "reject nonessential cookies", "decline optional cookies",
            "only necessary cookies", "necessary cookies only", "essential cookies only",
            "use necessary cookies only", "continue without accepting", "reject", "decline",
            "optionele cookies weigeren", "alle cookies weigeren", "alleen noodzakelijke cookies",
            "alleen essentiële cookies", "doorgaan zonder accepteren", "alles weigeren", "weigeren",
        )
        private val rank = listOf(
            "reject all cookies", "alle cookies weigeren", "reject all", "decline all", "alles weigeren",
            "reject optional cookies", "decline optional cookies", "optionele cookies weigeren",
            "only necessary cookies", "necessary cookies only", "essential cookies only",
            "use necessary cookies only", "alleen noodzakelijke cookies", "alleen essentiële cookies",
            "continue without accepting", "doorgaan zonder accepteren",
            "reject", "decline", "weigeren",
            "cookie settings", "manage cookies", "manage preferences", "customize", "customise",
            "preferences", "cookie-instellingen", "cookies beheren", "instellingen",
        )
    }
}
