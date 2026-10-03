package com.cyclone.mobile.agent.contract

import org.json.JSONArray
import org.json.JSONObject

enum class AgentFailureClass {
    NONE,
    STALE_OBSERVATION,
    TARGET_NOT_FOUND,
    EXECUTION_FAILED,
    VERIFICATION_FAILED,
    AFTER_OBSERVATION_FAILED,
    POLICY_DENIED,
    GATE_REQUIRED,
    DEVICE_DISCONNECTED,
    HUMAN_HAS_CONTROL,
    ACCESSIBILITY_UNAVAILABLE,
    CAPABILITY_UNAVAILABLE,
    AUTH_REQUIRED,
    TIMEOUT,
    INVALID_REQUEST,
}

enum class AgentFailureLayer {
    NONE,
    INPUT,
    OBSERVATION,
    POLICY,
    DEVICE,
    CAPABILITY,
    EXECUTION,
    VERIFICATION,
    LEARNING,
}

data class AgentFailure(
    val errorClass: AgentFailureClass,
    val failureLayer: AgentFailureLayer,
    val retryable: Boolean,
    val message: String,
    val reasonCode: String? = null,
)

data class AgentElementCandidate(
    val elementId: String,
    val observationId: String,
    val label: String,
    val semanticName: String,
    val role: String,
    val source: String,
    val relevance: Double,
    val evidence: JSONObject,
    val elementIndex: Int? = null,
)

data class AgentPageCard(
    val observationId: String,
    val generation: Long,
    val actionable: Boolean,
    val capturedAtMs: Long,
    val packageName: String,
    val activity: String?,
    val pageKey: String,
    val structuralKey: String,
    val contentKey: String,
    val accessibilityFingerprint: String,
    val pageSummary: JSONObject,
    val pageText: JSONObject,
    val pageEvidence: JSONObject,
    val controls: List<AgentElementCandidate>,
    val nextHopHints: JSONArray,
    val perceptionMode: String = "a11y",
    val treeUseful: Boolean = true,
    val sessionId: String = "default-foreground",
    val displayId: Int = 0,
    // In-process projection of the same capture; never serialized as a second observation.
    val legacyPage: com.cyclone.mobile.applearner.PageContext? = null,
    val observation: com.cyclone.mobile.runtime.session.ObservationIdentity? = null,
)

data class AgentObservationResult(
    val page: AgentPageCard? = null,
    val failure: AgentFailure? = null,
    val image: JSONObject? = null,
)

data class AgentSearchResult(
    val page: AgentPageCard? = null,
    val observationId: String? = null,
    val generation: Long? = null,
    val query: String,
    val goal: String,
    val candidates: List<AgentElementCandidate> = emptyList(),
    val failure: AgentFailure? = null,
)

data class AgentInspectResult(
    val observationId: String? = null,
    val generation: Long? = null,
    val elementId: String,
    val evidence: JSONObject? = null,
    val failure: AgentFailure? = null,
)

data class AgentScreenshotResult(
    val goal: String,
    val observationId: String? = null,
    val filePath: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val timestampMs: Long? = null,
    val failure: AgentFailure? = null,
)

data class AgentKnowledgeResult(
    val goal: String,
    val evidence: JSONObject? = null,
    val failure: AgentFailure? = null,
)

data class AgentStateDelta(
    val pageChanged: Boolean,
    val packageChanged: Boolean,
    val accessibilityChanged: Boolean,
    val semanticStateChanges: List<String>,
    val goalLabelAppeared: Boolean,
    val summary: String,
)

data class AgentLearningResult(
    val recorded: Boolean,
    val reason: String,
    val evidence: JSONObject = JSONObject(),
)

data class AgentActionEnvelope(
    val tool: String,
    val goal: String,
    val androidExecutionOk: Boolean,
    val executorReportedOk: Boolean,
    val verification: AgentSemanticVerification,
    val before: AgentPageCard?,
    val after: AgentPageCard?,
    val pageChanged: Boolean,
    val delta: AgentStateDelta,
    val errorClass: AgentFailureClass,
    val failureLayer: AgentFailureLayer,
    val retryable: Boolean,
    val semanticSuccessClaimed: Boolean,
    val beforeObservationId: String?,
    val afterObservationId: String?,
    val observationGeneration: Long?,
    val learning: AgentLearningResult,
    val safeMessage: String? = null,
    val executorInvoked: Boolean = false,
)
