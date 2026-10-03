package com.cyclone.mobile.agent.contract

enum class AgentVerificationStatus {
    OBSERVED,
    PASSED,
    FAILED,
    DEGRADED,
    NOT_REQUIRED,
}

data class SemanticElementState(
    val stableKey: String,
    val label: String,
    val role: String,
    val selected: Boolean,
    val checked: Boolean,
    val focused: Boolean,
    val editableTextState: String? = null,
)

data class SemanticObservationState(
    val packageName: String,
    val pageKey: String,
    val accessibilityFingerprint: String,
    val haystack: String,
    val elements: List<SemanticElementState>,
)

data class AgentSemanticVerification(
    val status: AgentVerificationStatus,
    val passed: Boolean,
    val semanticSuccessClaimed: Boolean,
    val basis: String? = null,
    val detail: String? = null,
)

/**
 * Transport/executor acceptance is deliberately separate from semantic verification.
 * This object is Android-free so the PC gateway and local mobile agent consume identical rules.
 *
 * A changing page hash/fingerprint is evidence that Android changed, but it is NOT task progress.
 * Cyclone only claims semantic success when the fresh after-state contains a stronger witness:
 * expected package, package transition, goal evidence, interaction state, or materially changed
 * semantic controls. This is the main guard against state-hash churn resetting recovery forever.
 */
object AgentSemanticVerifier {
    private val mutatingTools = setOf(
        "phone.click",
        "phone.long_press",
        "phone.tap",
        "phone.swipe",
        "phone.scroll",
        "phone.type",
        "phone.replace_text",
        "phone.back",
        "phone.home",
        "phone.open_app",
        "phone.launch_intent",
        "phone.set_clipboard",
        "phone.set_alarm",
        "phone.set_timer",
        "phone.open_settings",
        "phone.submit_text",
        "phone.tap_point",
    )

    // PhoneToolExecutor evaluates params.expect only for actionWithConfirmation tools.
    // A model-supplied expectation on any other tool is data, not verification evidence.
    private val executorExpectationTools = setOf(
        "phone.click",
        "phone.long_press",
        "phone.tap",
        "phone.swipe",
        "phone.scroll",
        "phone.back",
        "phone.home",
    )

    fun verify(
        tool: String,
        androidExecutionOk: Boolean,
        executorAssertionFailed: Boolean,
        explicitExpectation: Boolean,
        expectedPackage: String,
        goalLabel: String,
        before: SemanticObservationState?,
        after: SemanticObservationState?,
        expectedUri: String = "",
    ): AgentSemanticVerification {
        if (executorAssertionFailed) {
            return AgentSemanticVerification(
                status = AgentVerificationStatus.FAILED,
                passed = false,
                semanticSuccessClaimed = false,
                basis = "EXPLICIT_EXPECTATION_FAILED",
                detail = "Android performed the action but the requested after-state assertion did not pass.",
            )
        }
        if (!androidExecutionOk || tool !in mutatingTools) {
            return AgentSemanticVerification(
                status = AgentVerificationStatus.NOT_REQUIRED,
                passed = false,
                semanticSuccessClaimed = false,
                basis = if (androidExecutionOk) "READ_ONLY_TOOL" else "EXECUTION_NOT_ACCEPTED",
            )
        }
        if (after == null) {
            return AgentSemanticVerification(
                status = AgentVerificationStatus.DEGRADED,
                passed = false,
                semanticSuccessClaimed = false,
                basis = "AFTER_OBSERVATION_FAILED",
                detail = "Action executed but a fresh authoritative after-observation was unavailable.",
            )
        }
        if (explicitExpectation && tool in executorExpectationTools) {
            return passed("EXPLICIT_EXPECTATION")
        }
        if (tool == "phone.open_app" && expectedPackage.isNotBlank()) {
            return if (after.packageName == expectedPackage) {
                passed("EXPECTED_PACKAGE")
            } else {
                AgentSemanticVerification(
                    status = AgentVerificationStatus.FAILED,
                    passed = false,
                    semanticSuccessClaimed = false,
                    basis = "EXPECTED_PACKAGE_MISMATCH",
                    detail = "The requested app package was not the authoritative after-state package.",
                )
            }
        }

        if (tool == "phone.open_settings") {
            return if ("settings" in after.packageName.lowercase()) passed("SETTINGS_FOREGROUND") else AgentSemanticVerification(
                status = AgentVerificationStatus.OBSERVED,
                passed = false,
                semanticSuccessClaimed = false,
                basis = "SETTINGS_NOT_OBSERVED",
                detail = "Android accepted the Settings intent, but Settings is not the authoritative after-state.",
            )
        }

        if (tool == "phone.set_alarm" || tool == "phone.set_timer") {
            // The tool's own check is only "a clock app took the request"; the goal proof (an enabled alarm row, a
            // running countdown) is separate and never inferred from this.
            return if (PhoneIntents.isClockApp(after.packageName)) passed("CLOCK_APP_FOREGROUND") else AgentSemanticVerification(
                status = AgentVerificationStatus.OBSERVED,
                passed = false,
                semanticSuccessClaimed = false,
                basis = "CLOCK_APP_NOT_OBSERVED",
                detail = "Android accepted the clock intent, but no clock app is the authoritative after-state.",
            )
        }

        val expectedHttpHost = expectedHttpHost(expectedUri)
        if (tool == "phone.launch_intent" && expectedHttpHost != null) {
            return if (httpHostVisible(after.haystack, expectedHttpHost)) {
                passed("EXPECTED_URI_HOST")
            } else {
                AgentSemanticVerification(
                    status = AgentVerificationStatus.OBSERVED,
                    passed = false,
                    semanticSuccessClaimed = false,
                    basis = "EXPECTED_URI_HOST_NOT_OBSERVED",
                    detail = "Android accepted the web intent, but the authoritative after-state does not yet show the requested host.",
                )
            }
        }

        if (before != null) {
            if (
                before.packageName.isNotBlank() &&
                after.packageName.isNotBlank() &&
                before.packageName != after.packageName
            ) {
                return passed("PACKAGE_CHANGED")
            }
            val interactionBasis = semanticInteractionProgress(before, after)
            if (interactionBasis != null) return passed(interactionBasis)
            if (goalLabelAppeared(before.haystack, after.haystack, goalLabel)) {
                return passed("GOAL_LABEL_APPEARED")
            }
            if (
                before.pageKey.isNotBlank() &&
                after.pageKey.isNotBlank() &&
                before.pageKey != after.pageKey &&
                semanticSurfaceChanged(before, after)
            ) {
                return passed("SEMANTIC_PAGE_CHANGED")
            }
        }

        return AgentSemanticVerification(
            status = AgentVerificationStatus.OBSERVED,
            passed = false,
            semanticSuccessClaimed = false,
            basis = "NO_SEMANTIC_PROGRESS",
            detail = "Android accepted the action, but the fresh after-state did not prove semantic progress. Page/fingerprint churn alone is not progress.",
        )
    }

    /**
     * State changes on a stable semantic element are strong task-progress witnesses even if the
     * surrounding page identity also changes. Raw page/fingerprint changes are intentionally ignored.
     */
    fun semanticInteractionProgress(
        before: SemanticObservationState,
        after: SemanticObservationState,
    ): String? {
        val beforeByKey = before.elements.associateBy { it.stableKey }
        for (element in after.elements) {
            val prior = beforeByKey[element.stableKey] ?: continue
            if (prior.selected != element.selected) return "SELECTED_STATE_CHANGED"
            if (prior.checked != element.checked) return "CHECKED_STATE_CHANGED"
            if (prior.focused != element.focused) return "FOCUSED_STATE_CHANGED"
            val beforeText = prior.editableTextState
            val afterText = element.editableTextState
            if (
                element.role in setOf("textbox", "edit_text") &&
                beforeText != null &&
                afterText != null &&
                beforeText != afterText
            ) {
                return "EDITABLE_TEXT_CHANGED"
            }
        }
        return null
    }

    // Kept for callers/tests using the previous name. Its semantics are now intentionally stronger:
    // it verifies semantic interaction progress, not merely that a hash/page key stayed the same.
    fun samePageSemanticProgress(
        before: SemanticObservationState,
        after: SemanticObservationState,
    ): String? = semanticInteractionProgress(before, after)

    fun semanticSurfaceChanged(before: SemanticObservationState, after: SemanticObservationState): Boolean {
        fun surface(state: SemanticObservationState): Set<String> = state.elements
            .asSequence()
            .map { "${it.stableKey}|${it.role}|${it.label.trim().lowercase()}" }
            .filter { it.isNotBlank() }
            .take(80)
            .toSet()
        val beforeSurface = surface(before)
        val afterSurface = surface(after)
        if (beforeSurface.isEmpty() && afterSurface.isEmpty()) return false
        return beforeSurface != afterSurface
    }

    fun goalLabelAppeared(beforeHaystack: String, afterHaystack: String, goalLabel: String): Boolean {
        val needle = goalLabel.trim()
        if (needle.isBlank()) return false
        return !beforeHaystack.contains(needle, ignoreCase = true) && afterHaystack.contains(needle, ignoreCase = true)
    }

    private fun expectedHttpHost(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        return uri.host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
    }

    private fun httpHostVisible(haystack: String, expectedHost: String): Boolean {
        if (haystack.isBlank()) return false
        val exactHost = Regex(
            "(?i)(?<![a-z0-9_.@-])(?:https?://)?(?:[a-z0-9-]+\\.)*" +
                Regex.escape(expectedHost) + "(?=[:/?#\\s\"']|$)",
        )
        return exactHost.containsMatchIn(haystack)
    }

    private fun passed(basis: String) = AgentSemanticVerification(
        status = AgentVerificationStatus.PASSED,
        passed = true,
        semanticSuccessClaimed = true,
        basis = basis,
    )
}
