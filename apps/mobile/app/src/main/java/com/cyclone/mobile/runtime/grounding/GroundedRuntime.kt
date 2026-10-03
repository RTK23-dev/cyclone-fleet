package com.cyclone.mobile.runtime.grounding

/** Stable Android-grounding vocabulary shared by policy, recovery, and tests. */
enum class SurfaceKind { TASK, MODAL, AGENT }

enum class ModalType {
    SITE_NOTIFICATION_PERMISSION,
    COOKIE_CONSENT,
    NEWSLETTER_PROMPT,
    MARKETING_PROMPT,
    ANDROID_PERMISSION,
    AUTHENTICATION,
    PAYMENT,
    DESTRUCTIVE_CONFIRMATION,
    GENERIC_MODAL,
}

data class GroundedControl(
    val label: String,
    val role: String = "button",
    val resourceId: String = "",
    val bounds: String = "",
)

data class DeviceSurface(
    val kind: SurfaceKind,
    val packageName: String,
    val title: String = "",
    val text: String = "",
    val controls: List<GroundedControl> = emptyList(),
)

data class DeviceReality(
    val taskSurface: DeviceSurface?,
    val modalSurface: DeviceSurface?,
    val agentSurface: DeviceSurface?,
) {
    init {
        require(taskSurface?.kind != SurfaceKind.AGENT) { "Cyclone-owned UI cannot be taskSurface" }
        require(modalSurface?.kind != SurfaceKind.AGENT) { "Cyclone-owned UI cannot be modalSurface" }
        require(agentSurface == null || agentSurface.kind == SurfaceKind.AGENT) { "agentSurface must be AGENT" }
    }
}

object ModalRecognitionEngine {
    fun classify(surface: DeviceSurface): ModalType {
        val controls = surface.controls.joinToString(" ") { "${it.label} ${it.resourceId}" }.lowercase()
        val text = "${surface.title} ${surface.text} $controls".lowercase()
        return when {
            ("notification" in text || "notifications" in text) && hasAnyControl(surface, "block", "allow", "deny") -> ModalType.SITE_NOTIFICATION_PERMISSION
            listOf("cookie", "cookies", "tracking", "privacy choices").any(text::contains) -> ModalType.COOKIE_CONSENT
            listOf("newsletter", "email updates", "subscribe").any(text::contains) -> ModalType.NEWSLETTER_PROMPT
            listOf("special offer", "marketing", "promotion").any(text::contains) -> ModalType.MARKETING_PROMPT
            listOf("password", "sign in", "log in", "authentication", "verify identity").any(text::contains) -> ModalType.AUTHENTICATION
            listOf("pay", "payment", "checkout", "place order", "purchase").any(text::contains) -> ModalType.PAYMENT
            listOf("delete", "erase", "remove permanently", "factory reset").any(text::contains) -> ModalType.DESTRUCTIVE_CONFIRMATION
            surface.packageName == "com.android.permissioncontroller" || "permission" in text -> ModalType.ANDROID_PERMISSION
            else -> ModalType.GENERIC_MODAL
        }
    }

    private fun hasAnyControl(surface: DeviceSurface, vararg labels: String): Boolean =
        surface.controls.any { control -> labels.any { control.label.equals(it, ignoreCase = true) || control.label.contains(it, ignoreCase = true) } }
}

enum class ActionIntent {
    DENY_SITE_NOTIFICATION,
    ALLOW_SITE_NOTIFICATION,
    DISMISS_MARKETING_PROMPT,
    REJECT_OPTIONAL_COOKIES,
    SEND_MESSAGE,
    SUBMIT_FORM_EXTERNALLY,
    PAY,
    DELETE_DATA,
    GRANT_SENSITIVE_PERMISSION,
    GENERIC_SAFE_ACTION,
    UNKNOWN,
}

/**
 * Safety classification is intentionally target-first. Context can raise ambiguity but cannot turn
 * a confidently grounded low-risk control into a different consequential action.
 */
object ActionIntentClassifier {
    fun classify(
        actionLabel: String,
        role: String = "button",
        modalType: ModalType? = null,
        resourceId: String = "",
        context: List<String> = emptyList(),
    ): ActionIntent {
        val label = normalize(actionLabel)
        val rid = normalize(resourceId)
        val contextText = context.joinToString(" ") { normalize(it) }

        if (modalType == ModalType.SITE_NOTIFICATION_PERMISSION) {
            if (label in setOf("block", "deny", "dont allow", "don't allow", "not now")) return ActionIntent.DENY_SITE_NOTIFICATION
            if (label in setOf("allow", "enable", "yes")) return ActionIntent.ALLOW_SITE_NOTIFICATION
        }
        if (modalType == ModalType.COOKIE_CONSENT && listOf("reject", "decline", "necessary only", "essential only").any(label::contains)) {
            return ActionIntent.REJECT_OPTIONAL_COOKIES
        }
        if (modalType in setOf(ModalType.NEWSLETTER_PROMPT, ModalType.MARKETING_PROMPT) &&
            listOf("close", "dismiss", "no thanks", "not now", "skip").any(label::contains)) {
            return ActionIntent.DISMISS_MARKETING_PROMPT
        }
        if (modalType == ModalType.PAYMENT || listOf("pay", "place order", "buy now", "purchase", "checkout").any(label::contains)) return ActionIntent.PAY
        if (modalType == ModalType.DESTRUCTIVE_CONFIRMATION || listOf("delete", "erase", "remove permanently", "wipe").any(label::contains)) return ActionIntent.DELETE_DATA
        if (modalType == ModalType.ANDROID_PERMISSION && listOf("allow", "grant", "while using", "only this time").any(label::contains)) return ActionIntent.GRANT_SENSITIVE_PERMISSION
        if (listOf("send", "send message", "send email", "publish", "post").any { label == it || rid.contains(it) }) return ActionIntent.SEND_MESSAGE
        if (listOf("submit", "confirm submission").any(label::contains)) return ActionIntent.SUBMIT_FORM_EXTERNALLY

        // Context is supporting evidence only: it may classify an otherwise ungrounded action, but
        // never overrides a concrete safe target such as Block/Deny/Reject/Close.
        if (label in SAFE_TARGET_LABELS) return ActionIntent.GENERIC_SAFE_ACTION
        if (label.isBlank() && listOf("send message", "send email", "publish post").any(contextText::contains)) return ActionIntent.SEND_MESSAGE
        if (role.lowercase() in setOf("button", "switch", "checkbox", "radio_button") && label.isNotBlank()) return ActionIntent.GENERIC_SAFE_ACTION
        return ActionIntent.UNKNOWN
    }

    private val SAFE_TARGET_LABELS = setOf("block", "deny", "reject", "decline", "close", "dismiss", "cancel", "not now", "skip")
    private fun normalize(value: String): String = value.trim().lowercase().replace(Regex("[_-]+"), " ").replace(Regex("\\s+"), " ")
}

data class VisualSemanticLocator(
    val semanticTarget: String,
    val modalType: ModalType?,
    val sourcePackage: String,
    val relativePosition: String? = null,
    val approximateBounds: String? = null,
    val sceneIdentity: String,
    val confidence: Double,
) {
    fun resolveFresh(controls: List<GroundedControl>, currentPackage: String, currentSceneIdentity: String): GroundedControl? {
        if (currentPackage != sourcePackage || currentSceneIdentity != sceneIdentity) return null
        val target = semanticTarget.trim().lowercase()
        return controls.maxByOrNull { control ->
            val label = control.label.trim().lowercase()
            when {
                label == target -> 3
                label.contains(target) || target.contains(label) -> 2
                else -> 0
            }
        }?.takeIf { control ->
            val label = control.label.trim().lowercase()
            label == target || label.contains(target) || target.contains(label)
        }
    }
}

/** One visual acquisition per unchanged semantic scene. */
class VisionEscalationGuard {
    private val usedScenes = linkedSetOf<String>()

    enum class Decision { ALLOW, VISION_ALREADY_USED }

    @Synchronized
    fun request(sceneIdentity: String): Decision {
        require(sceneIdentity.isNotBlank()) { "sceneIdentity is required" }
        return if (usedScenes.add(sceneIdentity)) Decision.ALLOW else Decision.VISION_ALREADY_USED
    }

    @Synchronized
    fun resetForVerifiedProgress() = usedScenes.clear()
}

/** Bounded event-driven settlement policy; never authorizes an unbounded sleep/retry loop. */
data class TransitionSettlementPolicy(
    val maxWaitMs: Long = 1_800L,
    val observationIntervalMs: Long = 120L,
) {
    init {
        require(maxWaitMs in 0L..5_000L)
        require(observationIntervalMs in 50L..1_000L)
    }
}
