package com.cyclone.mobile.observability.events

import com.cyclone.mobile.platform.event.DataClassification
import com.cyclone.mobile.platform.event.EventEnvelope
import com.cyclone.mobile.platform.event.RedactionMetadata
import com.cyclone.mobile.platform.module.ModuleId
import java.security.MessageDigest

private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
private val SAFE_CODE = Regex("[a-z][a-z0-9_.-]{0,95}")
private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
private val SHA_256 = Regex("[0-9a-f]{64}")

/**
 * A content-addressed reference for non-secret structural evidence.
 *
 * [fromRaw] must never receive credentials, user-entered text, or other low-entropy sensitive
 * values. Those values use [omitted], because an unkeyed digest is guessable offline.
 */
class EvidenceRef private constructor(
    val namespace: String,
    val sha256: String,
) : java.io.Serializable {
    init {
        require(SAFE_CODE.matches(namespace)) { "Evidence namespace must be a safe code" }
        require(SHA_256.matches(sha256)) { "Evidence digest must be lowercase SHA-256" }
    }

    override fun toString(): String = "$namespace:$sha256"

    override fun equals(other: Any?): Boolean =
        other is EvidenceRef && namespace == other.namespace && sha256 == other.sha256

    override fun hashCode(): Int = 31 * namespace.hashCode() + sha256.hashCode()

    companion object {
        fun fromRaw(namespace: String, value: String): EvidenceRef =
            EvidenceRef(namespace, sha256(value))

        fun fromDigest(namespace: String, sha256: String): EvidenceRef =
            EvidenceRef(namespace, sha256.lowercase())

        /** A deterministic marker that proves omission without fingerprinting the omitted value. */
        fun omitted(namespace: String): EvidenceRef = EvidenceRef(namespace, OMITTED_DIGEST)

        private const val OMITTED_DIGEST =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}

/** Safe evidence that text existed, without retaining any of the text. */
data class RedactedTextDigest(
    val reference: EvidenceRef,
    val characterCount: Int,
    val estimatedTokens: Int,
    val inputTruncatedForCounting: Boolean,
) : java.io.Serializable {
    init {
        require(characterCount >= 0 && estimatedTokens >= 0) { "Text counts must be non-negative" }
    }
}

object ContextPrivacy {
    const val REDACTION_POLICY_VERSION: Int = 2
    private const val MAX_COUNTED_CHARACTERS = 16_384

    fun redactText(namespace: String, rawText: CharSequence): RedactedTextDigest {
        val counted = rawText.length.coerceAtMost(MAX_COUNTED_CHARACTERS)
        return RedactedTextDigest(
            reference = EvidenceRef.omitted(namespace),
            characterCount = counted,
            estimatedTokens = if (counted == 0) 0 else (counted + 3) / 4,
            inputTruncatedForCounting = rawText.length > MAX_COUNTED_CHARACTERS,
        )
    }

    fun sanitizeFailure(code: String, rawMessage: CharSequence?): SanitizedFailure =
        SanitizedFailure(
            code = code,
            messageFingerprint = rawMessage?.let { EvidenceRef.omitted("failure") },
            messageCharacterCount = rawMessage?.length?.coerceAtMost(MAX_COUNTED_CHARACTERS) ?: 0,
        )
}

enum class DecisionStage {
    STARTED,
    CONTEXT_ASSEMBLED,
    POLICY_EVALUATED,
    VISION_EVALUATED,
    MODEL_INVOKED,
    ACTION_PROPOSED,
    ACTION_RESULT,
    VERIFICATION_RESULT,
    RECOVERY_RESULT,
    COMPLETED,
    FAILED,
}

enum class ContextSourceKind {
    SYSTEM_POLICY,
    GOAL,
    PAGE_AWARENESS,
    APP_GRAPH,
    BRAIN,
    ROUTINE,
    TOOL_SCHEMA,
    PREVIOUS_RESULT,
    VISION_EVIDENCE,
}

data class ContextSourceEvidence(
    val source: ContextSourceKind,
    val byteCount: Int,
    val estimatedTokens: Int,
    val evidenceRefs: List<EvidenceRef> = emptyList(),
    val classification: DataClassification = DataClassification.INTERNAL,
    val included: Boolean = true,
    val stale: Boolean = false,
    val redactedValueCount: Int = 0,
) : java.io.Serializable {
    init {
        require(byteCount >= 0 && estimatedTokens >= 0 && redactedValueCount >= 0) {
            "Context source counts must be non-negative"
        }
    }
}

enum class PolicyState { UNKNOWN, ALLOW, ALLOW_ONCE, ASK, DENY }
enum class AiNecessityReason { NO_VERIFIED_ROUTE, AMBIGUOUS_PAGE, ROUTE_FAILED, USER_REQUESTED, RECOVERY_NEEDED }
enum class VisionReason { NOT_USED_STRUCTURED_SUFFICIENT, STRUCTURED_EVIDENCE_INSUFFICIENT, AMBIGUOUS_TARGET, VERIFICATION_REQUIRED }
enum class ActionOutcome { NOT_EXECUTED, SUCCEEDED, FAILED, DENIED }
enum class VerificationStatus { NOT_REQUESTED, PENDING, VERIFIED, FAILED, INCONCLUSIVE }
enum class RecoveryStatus { NOT_NEEDED, ATTEMPTED, RECOVERED, FAILED, HUMAN_REQUIRED }

data class PolicyTrace(
    val state: PolicyState,
    val decisionRef: EvidenceRef? = null,
    val reasonCode: String,
) : java.io.Serializable {
    init { require(SAFE_CODE.matches(reasonCode)) { "Policy reason must be a safe code" } }
}

data class VisionTrace(
    val used: Boolean,
    val reason: VisionReason,
    val providerCode: String? = null,
    val evidenceRefs: List<EvidenceRef> = emptyList(),
    val attemptCount: Int = 0,
) : java.io.Serializable {
    init {
        require(providerCode == null || SAFE_CODE.matches(providerCode)) { "Vision provider must be a safe code" }
        require(attemptCount >= 0) { "Vision attempt count must be non-negative" }
        require(used || evidenceRefs.isEmpty()) { "Unused vision cannot supply evidence" }
    }
}

data class ModelTrace(
    val providerCode: String,
    val modelCode: String,
    val requestRef: EvidenceRef,
) : java.io.Serializable {
    init {
        require(SAFE_CODE.matches(providerCode)) { "Model provider must be a safe code" }
        require(SAFE_CODE.matches(modelCode)) { "Model must be a safe code" }
    }
}

data class ProposedActionTrace(
    val actionCode: String,
    val targetRef: EvidenceRef? = null,
    val parameterNames: Set<String> = emptySet(),
) : java.io.Serializable {
    init {
        require(SAFE_CODE.matches(actionCode)) { "Action must be a safe code" }
        require(parameterNames.all(SAFE_CODE::matches)) { "Action parameter names must be safe codes" }
    }
}

data class ActionResultTrace(
    val outcome: ActionOutcome,
    val resultRef: EvidenceRef? = null,
) : java.io.Serializable

data class VerificationTrace(
    val status: VerificationStatus,
    val resultRef: EvidenceRef? = null,
) : java.io.Serializable

data class LatencyTrace(
    val contextMillis: Long = 0,
    val modelMillis: Long = 0,
    val actionMillis: Long = 0,
    val verificationMillis: Long = 0,
    val totalMillis: Long = 0,
) : java.io.Serializable {
    init {
        require(listOf(contextMillis, modelMillis, actionMillis, verificationMillis, totalMillis).all { it >= 0 }) {
            "Latency values must be non-negative"
        }
    }
}

data class RecoveryTrace(
    val status: RecoveryStatus,
    val strategyCode: String? = null,
    val resultRef: EvidenceRef? = null,
) : java.io.Serializable {
    init { require(strategyCode == null || SAFE_CODE.matches(strategyCode)) { "Recovery strategy must be a safe code" } }
}

data class SanitizedFailure(
    val code: String,
    val messageFingerprint: EvidenceRef?,
    val messageCharacterCount: Int,
) : java.io.Serializable {
    init {
        require(SAFE_CODE.matches(code)) { "Failure code must be a safe code" }
        require(messageCharacterCount >= 0) { "Failure message count must be non-negative" }
    }
}

data class PayloadTruncation(
    val droppedSources: Int = 0,
    val droppedEvidenceRefs: Int = 0,
    val droppedKnowledgeRefs: Int = 0,
) : java.io.Serializable {
    val truncated: Boolean get() = droppedSources + droppedEvidenceRefs + droppedKnowledgeRefs > 0
}

data class ContextDecisionEvent(
    val decisionId: String,
    val stage: DecisionStage,
    val appPackage: String? = null,
    val pageRef: EvidenceRef? = null,
    val goal: RedactedTextDigest? = null,
    val routeRef: EvidenceRef? = null,
    val contextSources: List<ContextSourceEvidence> = emptyList(),
    val knowledgeRefs: List<EvidenceRef> = emptyList(),
    val policy: PolicyTrace? = null,
    val vision: VisionTrace? = null,
    val model: ModelTrace? = null,
    val aiReason: AiNecessityReason? = null,
    val proposedAction: ProposedActionTrace? = null,
    val actionResult: ActionResultTrace? = null,
    val verification: VerificationTrace? = null,
    val latency: LatencyTrace? = null,
    val recovery: RecoveryTrace? = null,
    val failure: SanitizedFailure? = null,
    val truncation: PayloadTruncation = PayloadTruncation(),
) : java.io.Serializable {
    init {
        require(SAFE_ID.matches(decisionId)) { "Decision id must be a safe identifier" }
        require(appPackage == null || PACKAGE_NAME.matches(appPackage)) { "App package is invalid" }
    }
}

data class ContextEventRequest(
    val eventId: String,
    val timestampEpochMillis: Long,
    val missionId: String? = null,
    val sessionId: String? = null,
    val payload: ContextDecisionEvent,
) {
    init {
        require(SAFE_ID.matches(eventId)) { "Event id must be a safe identifier" }
        require(timestampEpochMillis >= 0) { "Timestamp must be non-negative" }
        require(missionId == null || SAFE_ID.matches(missionId)) { "Mission id must be a safe identifier" }
        require(sessionId == null || SAFE_ID.matches(sessionId)) { "Session id must be a safe identifier" }
    }
}

data class ContextPayloadBudget(
    val maxSources: Int = 16,
    val maxEvidenceRefsPerSource: Int = 8,
    val maxKnowledgeRefs: Int = 16,
) {
    init { require(maxSources > 0 && maxEvidenceRefsPerSource > 0 && maxKnowledgeRefs > 0) }
}

object ContextEventFactory {
    const val SCHEMA_VERSION: Int = 1
    val MODULE_ID: ModuleId = ModuleId("observability.context")

    fun create(
        request: ContextEventRequest,
        budget: ContextPayloadBudget = ContextPayloadBudget(),
    ): EventEnvelope<ContextDecisionEvent> {
        val original = request.payload
        val sortedSources = original.contextSources.sortedBy { it.source.name }
        var droppedRefs = 0
        val sources = sortedSources.take(budget.maxSources).map { source ->
            val refs = source.evidenceRefs.distinct().sortedBy(EvidenceRef::toString)
            droppedRefs += (refs.size - budget.maxEvidenceRefsPerSource).coerceAtLeast(0)
            source.copy(evidenceRefs = refs.take(budget.maxEvidenceRefsPerSource))
        }
        val knowledge = original.knowledgeRefs.distinct().sortedBy(EvidenceRef::toString)
        val boundedPayload = original.copy(
            contextSources = sources,
            knowledgeRefs = knowledge.take(budget.maxKnowledgeRefs),
            proposedAction = original.proposedAction?.copy(
                parameterNames = original.proposedAction.parameterNames.toSortedSet(),
            ),
            truncation = PayloadTruncation(
                droppedSources = (sortedSources.size - budget.maxSources).coerceAtLeast(0),
                droppedEvidenceRefs = droppedRefs,
                droppedKnowledgeRefs = (knowledge.size - budget.maxKnowledgeRefs).coerceAtLeast(0),
            ),
        )
        val classification = boundedPayload.contextSources.maxOfOrNull { it.classification.ordinal }
            ?.let { DataClassification.values()[it] }
            ?: DataClassification.INTERNAL
        val omitReferences = classification >= DataClassification.SENSITIVE
        val payload = if (omitReferences) boundedPayload.copy(
            pageRef = boundedPayload.pageRef?.let { EvidenceRef.omitted(it.namespace) },
            routeRef = boundedPayload.routeRef?.let { EvidenceRef.omitted(it.namespace) },
            contextSources = boundedPayload.contextSources.map { source ->
                source.copy(evidenceRefs = source.evidenceRefs.map { EvidenceRef.omitted(it.namespace) })
            },
            knowledgeRefs = boundedPayload.knowledgeRefs.map { EvidenceRef.omitted(it.namespace) },
            policy = boundedPayload.policy?.copy(
                decisionRef = boundedPayload.policy.decisionRef?.let { EvidenceRef.omitted(it.namespace) },
            ),
            vision = boundedPayload.vision?.copy(
                evidenceRefs = boundedPayload.vision.evidenceRefs.map { EvidenceRef.omitted(it.namespace) },
            ),
            model = boundedPayload.model?.copy(requestRef = EvidenceRef.omitted(boundedPayload.model.requestRef.namespace)),
            proposedAction = boundedPayload.proposedAction?.copy(
                targetRef = boundedPayload.proposedAction.targetRef?.let { EvidenceRef.omitted(it.namespace) },
            ),
            actionResult = boundedPayload.actionResult?.copy(
                resultRef = boundedPayload.actionResult.resultRef?.let { EvidenceRef.omitted(it.namespace) },
            ),
            verification = boundedPayload.verification?.copy(
                resultRef = boundedPayload.verification.resultRef?.let { EvidenceRef.omitted(it.namespace) },
            ),
            recovery = boundedPayload.recovery?.copy(
                resultRef = boundedPayload.recovery.resultRef?.let { EvidenceRef.omitted(it.namespace) },
            ),
        ) else boundedPayload.copy(
            proposedAction = boundedPayload.proposedAction?.let { action ->
                if (action.actionCode in setOf("phone.type", "phone.replace_text")) {
                    action.copy(targetRef = action.targetRef?.let { EvidenceRef.omitted(it.namespace) })
                } else action
            },
        )
        val redactedFields = buildSet {
            if (payload.goal != null) add("payload.goal")
            if (payload.proposedAction?.targetRef != null) add("payload.proposedAction.target")
            if (payload.failure?.messageFingerprint != null) add("payload.failure.message")
            if (payload.contextSources.any { it.redactedValueCount > 0 }) add("payload.contextSources.values")
        }
        return EventEnvelope(
            eventId = request.eventId,
            eventType = "ai.context.${payload.stage.name.lowercase().replace('_', '-')}",
            schemaVersion = SCHEMA_VERSION,
            timestampEpochMillis = request.timestampEpochMillis,
            missionId = request.missionId,
            sessionId = request.sessionId,
            moduleId = MODULE_ID,
            correlationId = payload.decisionId,
            payload = payload,
            redaction = RedactionMetadata(
                classification = classification,
                redactedFields = redactedFields,
                containsSensitiveData = classification >= DataClassification.SENSITIVE || redactedFields.isNotEmpty(),
                redactionPolicyVersion = ContextPrivacy.REDACTION_POLICY_VERSION,
            ),
        )
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
