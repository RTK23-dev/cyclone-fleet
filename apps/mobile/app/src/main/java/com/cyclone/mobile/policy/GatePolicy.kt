package com.cyclone.mobile.policy

import com.cyclone.mobile.runtime.grounding.ActionIntent
import com.cyclone.mobile.runtime.grounding.ActionIntentClassifier
import com.cyclone.mobile.runtime.grounding.ModalType

/**
 * GATE classes owned by [PolicyGovernor]: pay, send, delete, grant.
 *
 * Phone-side only. A PC envelope with `autoApprove=true` is ignored and never becomes
 * user authority. Workers cannot use GATE as a promotion path.
 */
enum class GateClass {
    PAY,
    SEND,
    DELETE,
    GRANT,
    ;

    val capability: String
        get() = when (this) {
            PAY -> "commerce.pay"
            SEND -> "messages.send"
            DELETE -> "data.delete"
            GRANT -> "authority.grant"
        }

    val risk: ActionRisk
        get() = when (this) {
            PAY -> ActionRisk.FINANCIAL
            SEND -> ActionRisk.EXTERNAL_COMMUNICATION
            DELETE -> ActionRisk.DESTRUCTIVE
            GRANT -> ActionRisk.SECURITY_CRITICAL
        }

    val jsonKey: String get() = name.lowercase()
}

/**
 * PC-supplied GATE envelope. [autoApprove] is always ignored. [requestedCapsuleStatus]
 * of `verified` is never honored on this path.
 */
data class PcGateEnvelope(
    val autoApprove: Boolean = false,
    val origin: String = "pc",
    val requestedCapsuleStatus: String? = null,
)

object GateClassifier {
    private val pay = listOf(
        "pay", "payment", "checkout", "place order", "buy now", "purchase", "confirm order",
        "order this", "complete purchase",
    )
    private val grant = listOf(
        "grant", "allow access", "give permission", "authorize app", "trust this", "enable access",
    )
    // Alpha 92: an app's Force stop, Clear storage/data and Uninstall end or erase the owner's state too (a Mind run
    // force-stopped Files and confirmed Android's warning on its own).
    private val delete = listOf("delete", "remove", "erase", "factory reset", "wipe data", "move to bin", "move to trash", "send to bin", "send to trash", "throw away",
        "force stop", "clear storage", "clear data", "uninstall", "reset app preferences", "geforceerd stoppen", "opslag wissen", "verwijderen")
    private val send = listOf("send", "send message", "send email", "post", "publish")
    private val deleteMoveSendToBinOrTrash =
        Regex("""(?:move|send)(?:\s+\S+){0,6}\s+to\s+(?:bin|trash)""")

    /**
     * Classifies the grounded target before reading surrounding prose. This prevents contextual text
     * such as "wants to send you notifications" from turning the actual `Block` control into SEND.
     */
    fun classify(action: String, labels: List<String> = emptyList()): GateClass? {
        val normalizedLabels = labels.map(String::trim).filter(String::isNotBlank)
        val selectedLabel = normalizedLabels.firstOrNull().orEmpty()
        if (PublishGate.gates(selectedLabel)) return GateClass.SEND
        val contextual = normalizedLabels.drop(1)
        val allText = (listOf(action) + normalizedLabels).joinToString(" ").lowercase().replace(Regex("[_-]+"), " ")
        val modalType = when {
            allText.contains("notification") && normalizedLabels.any { it.equals("block", true) || it.equals("allow", true) || it.equals("deny", true) } -> ModalType.SITE_NOTIFICATION_PERMISSION
            allText.contains("cookie") || allText.contains("tracking") -> ModalType.COOKIE_CONSENT
            allText.contains("payment") || allText.contains("checkout") -> ModalType.PAYMENT
            allText.contains("permission") -> ModalType.ANDROID_PERMISSION
            else -> null
        }
        val intent = ActionIntentClassifier.classify(
            actionLabel = selectedLabel,
            modalType = modalType,
            context = contextual,
        )
        when (intent) {
            // These are the only confidently grounded low-risk actions that should bypass the
            // legacy keyword fallback. GENERIC_SAFE_ACTION must continue into compatibility
            // classification because older callers encode consequential meaning in action/context.
            ActionIntent.DENY_SITE_NOTIFICATION,
            ActionIntent.DISMISS_MARKETING_PROMPT,
            ActionIntent.REJECT_OPTIONAL_COOKIES,
            -> return null
            ActionIntent.ALLOW_SITE_NOTIFICATION,
            ActionIntent.GRANT_SENSITIVE_PERMISSION,
            -> return GateClass.GRANT
            ActionIntent.SEND_MESSAGE,
            ActionIntent.SUBMIT_FORM_EXTERNALLY,
            -> return GateClass.SEND
            ActionIntent.PAY -> return GateClass.PAY
            ActionIntent.DELETE_DATA -> return GateClass.DELETE
            ActionIntent.GENERIC_SAFE_ACTION,
            ActionIntent.UNKNOWN,
            -> Unit
        }

        // Compatibility fallback for non-element actions and older callers with no grounded label.
        val text = allText
        if (pay.any { text.contains(it) }) return GateClass.PAY
        if (grant.any { text.contains(it) }) return GateClass.GRANT
        if (delete.any { text.contains(it) } || deleteMoveSendToBinOrTrash.containsMatchIn(text)) return GateClass.DELETE
        if (send.any { text.contains(it) }) return GateClass.SEND
        return null
    }
}

sealed interface GateDecision {
    val mutationAllowed: Boolean
    val ignoredPcAutoApprove: Boolean
    val writesVerified: Boolean get() = false

    data class NotGated(
        override val ignoredPcAutoApprove: Boolean,
    ) : GateDecision {
        override val mutationAllowed: Boolean = true
    }

    data class Allowed(
        val gateClass: GateClass,
        val evaluation: PolicyEvaluation,
        override val ignoredPcAutoApprove: Boolean,
    ) : GateDecision {
        override val mutationAllowed: Boolean = true
    }

    data class Blocked(
        val gateClass: GateClass,
        val evaluation: PolicyEvaluation,
        override val ignoredPcAutoApprove: Boolean,
    ) : GateDecision {
        override val mutationAllowed: Boolean = false
    }
}

/**
 * Phone-side GATE authority. Every pay/send/delete/grant action is evaluated by
 * [PolicyGovernor]. PC `autoApprove` is dropped on the floor.
 */
class GatePolicy(private val governor: PolicyGovernor) {
    fun evaluate(
        actionId: String,
        action: String,
        labels: List<String> = emptyList(),
        packageName: String? = null,
        principal: PolicyPrincipal,
        requestedAtEpochMillis: Long,
        missionId: String? = null,
        grantId: String? = null,
        pcEnvelope: PcGateEnvelope? = null,
        gateClass: GateClass? = null,
        authorityClaims: List<AuthorityClaim> = emptyList(),
        contextEvidence: List<AuthorityClaim> = emptyList(),
    ): GateDecision {
        val ignoredPcAutoApprove = pcEnvelope?.autoApprove == true
        val classified = gateClass ?: GateClassifier.classify(action, labels)
            ?: return GateDecision.NotGated(ignoredPcAutoApprove)

        val phoneAuthority = if (pcEnvelope != null) {
            authorityClaims.filter {
                it.origin.canIssueGrant && it.origin != AuthorityOrigin.CURRENT_CONFIRMATION
            }
        } else {
            authorityClaims
        }

        val evidence = buildList {
            addAll(contextEvidence)
            if (pcEnvelope != null) {
                add(AuthorityClaim(AuthorityOrigin.TOOL_OUTPUT, "pc-gate-envelope"))
            }
            if (ignoredPcAutoApprove) {
                add(AuthorityClaim(AuthorityOrigin.SUBAGENT_INSTRUCTION, "pc-autoapprove-ignored"))
            }
        }

        val request = PolicyRequest(
            actionId = actionId,
            capability = classified.capability,
            risk = classified.risk,
            principal = principal,
            requestedAtEpochMillis = requestedAtEpochMillis,
            missionId = missionId,
            target = PolicyTarget(
                packageName = packageName,
                targetType = "gate",
                targetId = classified.jsonKey,
            ),
            grantId = if (pcEnvelope != null) null else grantId,
            authorityClaims = phoneAuthority,
            contextEvidence = evidence,
        )
        val evaluation = governor.evaluate(request)
        return if (evaluation.isAllowed) {
            GateDecision.Allowed(classified, evaluation, ignoredPcAutoApprove)
        } else {
            GateDecision.Blocked(classified, evaluation, ignoredPcAutoApprove)
        }
    }

    companion object {
        const val FUNCTION = "GatePolicy.evaluate"
        val CLASSES = GateClass.entries.map { it.jsonKey }
    }
}
