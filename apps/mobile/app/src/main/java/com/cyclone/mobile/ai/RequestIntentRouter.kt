package com.cyclone.mobile.ai

enum class RequestIntent { CHAT, PHONE_TASK }

enum class RequestIntentConfidence { HIGH, MEDIUM, LOW }

enum class AttachmentRelevance { NONE, CHAT_CONTEXT, PHONE_REFERENCE }

enum class RequestDispatch { CHAT, START_PHONE_TASK, QUEUE_PHONE_TASK }

data class RequestIntentResult(
    val intent: RequestIntent,
    val confidence: RequestIntentConfidence,
    val reason: String,
    val attachmentRelevance: AttachmentRelevance,
    val appHint: String? = null,
    val requiresAndroidAccess: Boolean = intent == RequestIntent.PHONE_TASK,
    val classifierProviderRequest: Boolean = false,
)

/**
 * Cheap, deterministic first-pass router for Ask Cyclone.
 *
 * It never observes Android, never mutates Android, and never performs a provider request.
 * Explanatory questions remain chat. Imperative browse/search assignments are phone work because
 * Ask Cyclone is the phone assistant surface; they must not bounce the user into a hidden mode.
 */
object RequestIntentRouter {
    fun dispatch(result: RequestIntentResult, canStartPhoneTask: Boolean): RequestDispatch =
        when (result.intent) {
            RequestIntent.CHAT -> RequestDispatch.CHAT
            RequestIntent.PHONE_TASK -> if (canStartPhoneTask) RequestDispatch.START_PHONE_TASK else RequestDispatch.QUEUE_PHONE_TASK
        }

    private data class AppPattern(val canonical: String, val regex: Regex)

    private val appPatterns = listOf(
        AppPattern("Settings", Regex("\\b(?:android\\s+)?settings\\b")),
        AppPattern("Chrome", Regex("\\b(?:google\\s+)?chrome\\b")),
        AppPattern("Instagram", Regex("\\binstagram\\b")),
        AppPattern("Starbucks", Regex("\\bstarbucks\\b")),
        AppPattern("DoorDash", Regex("\\bdoordash\\b")),
        AppPattern("Gmail", Regex("\\bgmail\\b")),
        AppPattern("WhatsApp", Regex("\\bwhats\\s*app\\b")),
        AppPattern("Messages", Regex("\\b(?:google\\s+)?messages\\b")),
        AppPattern("YouTube", Regex("\\byou\\s*tube\\b")),
        AppPattern("TikTok", Regex("\\btik\\s*tok\\b")),
        AppPattern("Maps", Regex("\\b(?:google\\s+)?maps\\b")),
        AppPattern("Spotify", Regex("\\bspotify\\b")),
        AppPattern("Uber", Regex("\\buber\\b")),
    )

    private val liveAndroidState = listOf(
        Regex("\\bcheck\\s+(?:my\\s+)?(?:phone\\s+)?notifications?\\b"),
        Regex("\\b(?:what(?:'s| is)|show me|read)\\s+(?:on\\s+)?my\\s+(?:phone\\s+)?(?:screen|notifications?)\\b"),
        Regex("\\b(?:is|are)\\s+(?:my\\s+)?(?:wi[- ]?fi|wifi|bluetooth|mobile data|airplane mode)\\s+(?:on|off|enabled|disabled)\\b"),
        Regex("\\bcheck\\s+.+?\\s+to\\s+see\\s+if\\b"),
        Regex("\\bsee\\s+if\\s+i(?:'m| am)\\s+logged\\s+in\\b"),
        Regex("\\bcheck\\s+(?:whether|if)\\s+i(?:'m| am)\\s+logged\\s+in\\b"),
    )

    private val systemCommandPrefix =
        "(?:(?:please|can you|could you|would you|can you please|could you please|would you please|i want you to|i need you to)\\s+)?"

    private val directSystemMutation = listOf(
        Regex("^${systemCommandPrefix}(?:turn|switch)\\s+(?:on|off)\\s+(?:my\\s+)?(?:wi[- ]?fi|wifi|bluetooth|mobile data|airplane mode)\\b"),
        Regex("^${systemCommandPrefix}(?:enable|disable)\\s+(?:my\\s+)?(?:wi[- ]?fi|wifi|bluetooth|mobile data|airplane mode)\\b"),
        Regex("^${systemCommandPrefix}(?:increase|decrease|set)\\s+(?:the\\s+)?(?:volume|brightness)\\b"),
    )

    private val explanatoryLead = Regex(
        "^(?:what|who|why|when|where|how|explain|describe|summari[sz]e|write|draft|rewrite|translate|calculate|solve|brainstorm|tell me|give me|help me understand)\\b",
    )

    private val attachmentQuestion = Regex(
        "\\b(?:image|photo|picture|attachment|attached|file|document|text|this)\\b",
    )

    private val attachmentAnalysisVerb = Regex(
        "\\b(?:what|who|where|how many|describe|explain|summari[sz]e|read|extract|identify|count|analy[sz]e|tell me)\\b",
    )

    private val politeCommandPrefix =
        "(?:(?:please|can you|could you|would you|can you please|could you please|would you please|i want you to|i need you to)\\s+)?"

    private val directOpenCommand = Regex(
        "^${politeCommandPrefix}(?:open|launch|start|go to)\\b",
    )

    private val directAppCommand = Regex(
        "^${politeCommandPrefix}(?:open|launch|start|go to|check|search|tap|scroll|post|upload|play|pause|send|message|call|navigate|prepare)\\b",
    )

    private val directBrowseAssignment = Regex(
        "^${politeCommandPrefix}(?:search(?:\\s+(?:for|on))?|look\\s+up|browse(?:\\s+for)?)\\b",
    )

    private val explicitThroughAppAction = Regex(
        "\\b(?:send|post|upload|message|share|delete|order|buy|purchase|book|reserve)\\b.*\\b(?:in|on|through|via|using|with)\\b",
    )

    private val ambiguousConsequential = Regex(
        "^(?:please\\s+)?(?:send|order|buy|purchase|pay|book|reserve|post|upload|delete|cancel)\\b",
    )

    private val directPhoneOperation = Regex(
        "^(?:please\\s+)?(?:call|dial)\\s+.+",
    )

    fun route(text: String, hasAttachment: Boolean = false): RequestIntentResult {
        val clean = text.trim()
        val normalized = clean.lowercase().replace(Regex("\\s+"), " ")
        val appHint = appPatterns.firstOrNull { it.regex.containsMatchIn(normalized) }?.canonical
            ?: com.cyclone.mobile.fastpath.FastPathLanding.namedApp(clean)?.first
                ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        if (normalized.isBlank()) {
            return chat(
                confidence = RequestIntentConfidence.LOW,
                reason = if (hasAttachment) "An attachment alone does not require Android access." else "No request text was provided.",
                hasAttachment = hasAttachment,
            )
        }

        val attachmentLooksAnalytical = hasAttachment &&
            attachmentQuestion.containsMatchIn(normalized) &&
            attachmentAnalysisVerb.containsMatchIn(normalized)

        val explicitPhoneAction = appHint != null && (
            directAppCommand.containsMatchIn(normalized) || explicitThroughAppAction.containsMatchIn(normalized)
        )

        // Attached media stays chat context unless the user explicitly asks to act in an app.
        if (attachmentLooksAnalytical && !explicitPhoneAction) {
            return chat(
                confidence = RequestIntentConfidence.HIGH,
                reason = "The request can be answered from the attached content without observing Android.",
                hasAttachment = true,
                appHint = appHint,
            )
        }

        liveAndroidState.firstOrNull { it.containsMatchIn(normalized) }?.let {
            return phone(
                confidence = RequestIntentConfidence.HIGH,
                reason = "The request requires inspecting current Android state.",
                hasAttachment = hasAttachment,
                appHint = appHint,
            )
        }

        directSystemMutation.firstOrNull { it.containsMatchIn(normalized) }?.let {
            return phone(
                confidence = RequestIntentConfidence.HIGH,
                reason = "The request explicitly changes Android system state.",
                hasAttachment = hasAttachment,
                appHint = appHint,
            )
        }

        if (directPhoneOperation.matches(normalized)) {
            return phone(
                confidence = RequestIntentConfidence.HIGH,
                reason = "The request explicitly invokes a phone operation.",
                hasAttachment = hasAttachment,
                appHint = appHint,
            )
        }

        if (directOpenCommand.containsMatchIn(normalized)) {
            return phone(
                confidence = RequestIntentConfidence.HIGH,
                reason = "The request explicitly asks Cyclone to open or launch Android content.",
                hasAttachment = hasAttachment,
                appHint = appHint,
            )
        }

        if (directBrowseAssignment.containsMatchIn(normalized)) {
            return phone(
                confidence = RequestIntentConfidence.HIGH,
                reason = "The request assigns Cyclone an active search/browse task on the phone.",
                hasAttachment = hasAttachment,
                appHint = appHint,
            )
        }

        com.cyclone.mobile.fastpath.ImplicitAppRouter.job(clean)?.let { job ->
            return phone(
                confidence = RequestIntentConfidence.HIGH,
                reason = "The request is a phone job for ${job.label} even though no app was named.",
                hasAttachment = hasAttachment,
                appHint = appHint ?: job.label,
            )
        }

        if (explicitPhoneAction) {
            return phone(
                confidence = RequestIntentConfidence.HIGH,
                reason = "The request explicitly asks Cyclone to interact with $appHint.",
                hasAttachment = hasAttachment,
                appHint = appHint,
            )
        }

        if (ambiguousConsequential.containsMatchIn(normalized)) {
            return chat(
                confidence = RequestIntentConfidence.LOW,
                reason = "The request may imply a consequential phone action but does not clearly identify the Android interaction; clarify before mutation.",
                hasAttachment = hasAttachment,
                appHint = appHint,
            )
        }

        if (explanatoryLead.containsMatchIn(normalized)) {
            return chat(
                confidence = RequestIntentConfidence.HIGH,
                reason = "The request can be answered from text, attachment context, or model knowledge.",
                hasAttachment = hasAttachment,
                appHint = appHint,
            )
        }

        return chat(
            confidence = RequestIntentConfidence.MEDIUM,
            reason = "No deterministic requirement for live Android observation or mutation was found.",
            hasAttachment = hasAttachment,
            appHint = appHint,
        )
    }

    private fun chat(
        confidence: RequestIntentConfidence,
        reason: String,
        hasAttachment: Boolean,
        appHint: String? = null,
    ) = RequestIntentResult(
        intent = RequestIntent.CHAT,
        confidence = confidence,
        reason = reason,
        attachmentRelevance = if (hasAttachment) AttachmentRelevance.CHAT_CONTEXT else AttachmentRelevance.NONE,
        appHint = appHint,
    )

    private fun phone(
        confidence: RequestIntentConfidence,
        reason: String,
        hasAttachment: Boolean,
        appHint: String? = null,
    ) = RequestIntentResult(
        intent = RequestIntent.PHONE_TASK,
        confidence = confidence,
        reason = reason,
        attachmentRelevance = if (hasAttachment) AttachmentRelevance.PHONE_REFERENCE else AttachmentRelevance.NONE,
        appHint = appHint,
    )
}