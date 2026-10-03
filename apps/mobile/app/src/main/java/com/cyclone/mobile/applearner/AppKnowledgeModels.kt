package com.cyclone.mobile.applearner

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class KnowledgeState { DISCOVERED, UNDERSTOOD, VERIFIED, STALE, UNKNOWN }
enum class LearningMode { GUIDED, TASK, PASSIVE }
enum class LearnerSessionState { IDLE, STARTING, LEARNING, PAUSED, WAITING_FOR_HUMAN, COMPLETE, STOPPED, FAILED }
enum class ActionRisk { SAFE, CONSEQUENTIAL, AUTHENTICATION, CROSS_APP, UNKNOWN }

data class LearnedApp(
    val packageName: String,
    val label: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val knowledgeState: KnowledgeState = KnowledgeState.DISCOVERED,
    val confidence: Double = 0.0,
    val lastLearnedAt: Long = System.currentTimeMillis(),
    val lastVerifiedAt: Long? = null,
    val instructionSummary: String = "",
)

data class ScreenRecognition(
    val semanticFingerprint: String,
    val structuralFingerprint: String,
    val stableAnchors: List<String>,
    val className: String?,
    val titleHints: List<String>,
)

data class LearnedScreen(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val identity: String,
    val title: String,
    val purpose: String,
    val recognition: ScreenRecognition,
    val knowledgeState: KnowledgeState,
    val confidence: Double,
    val appVersion: String? = null,
    val lastSeenAt: Long = System.currentTimeMillis(),
    val lastVerifiedAt: Long? = null,
    val screenshotPath: String? = null,
    val sampleDynamicData: Map<String, String> = emptyMap(),
)

data class LearnedAction(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val screenId: String,
    val semanticName: String,
    val label: String,
    val androidActions: List<String>,
    val selectorJson: String,
    val risk: ActionRisk,
    val requiredInput: String? = null,
    val knowledgeState: KnowledgeState = KnowledgeState.DISCOVERED,
    val confidence: Double = 0.55,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val alternativeSelectors: List<String> = emptyList(),
    val failureCount: Int = 0,
)

data class LearnedTransition(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val fromScreenId: String,
    val actionId: String,
    val toScreenId: String,
    val knowledgeState: KnowledgeState = KnowledgeState.DISCOVERED,
    val confidence: Double = 0.6,
    val observedCount: Int = 1,
    val successfulCount: Int = 1,
    val lastObservedAt: Long = System.currentTimeMillis(),
)

data class SkillCandidate(
    val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val name: String,
    val description: String,
    val transitionIds: List<String>,
    val confidence: Double,
    val state: KnowledgeState,
)

data class LearnerProgress(
    val sessionId: String? = null,
    val packageName: String? = null,
    val appLabel: String? = null,
    val mode: LearningMode = LearningMode.GUIDED,
    val state: LearnerSessionState = LearnerSessionState.IDLE,
    val instruction: String = "",
    val currentScreen: String? = null,
    val currentActivity: String = "Idle",
    val screens: Int = 0,
    val actions: Int = 0,
    val transitions: Int = 0,
    val forms: Int = 0,
    val unknownAreas: Int = 0,
    val approvalBoundaries: Int = 0,
    val message: String? = null,
)

data class AppGraphSnapshot(
    val app: LearnedApp,
    val screens: List<LearnedScreen>,
    val actions: List<LearnedAction>,
    val transitions: List<LearnedTransition>,
) {
    fun outgoing(screenId: String): List<Pair<LearnedAction, LearnedTransition>> {
        val byAction = actions.associateBy { it.id }
        return transitions.filter { it.fromScreenId == screenId }.mapNotNull { transition ->
            byAction[transition.actionId]?.let { it to transition }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("app", JSONObject()
            .put("package", app.packageName)
            .put("label", app.label)
            .put("confidence", app.confidence)
            .put("state", app.knowledgeState.name))
        .put("screens", JSONArray().also { array -> screens.forEach { array.put(it.toJson()) } })
        .put("actions", JSONArray().also { array -> actions.forEach { array.put(it.toJson()) } })
        .put("transitions", JSONArray().also { array -> transitions.forEach { array.put(it.toJson()) } })
}

fun LearnedScreen.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("package", packageName)
    .put("identity", identity)
    .put("title", title)
    .put("purpose", purpose)
    .put("state", knowledgeState.name)
    .put("confidence", confidence)
    .put("lastSeenAt", lastSeenAt)
    .put("lastVerifiedAt", lastVerifiedAt ?: JSONObject.NULL)
    .put("appVersion", appVersion ?: JSONObject.NULL)
    .put("screenshotPath", screenshotPath ?: JSONObject.NULL)
    .put("recognition", JSONObject()
        .put("semanticFingerprint", recognition.semanticFingerprint)
        .put("structuralFingerprint", recognition.structuralFingerprint)
        .put("className", recognition.className ?: JSONObject.NULL)
        .put("stableAnchors", JSONArray(recognition.stableAnchors))
        .put("titleHints", JSONArray(recognition.titleHints)))

fun LearnedAction.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("screenId", screenId)
    .put("name", semanticName)
    .put("label", label)
    .put("androidActions", JSONArray(androidActions))
    .put("selector", runCatching { JSONObject(selectorJson) }.getOrElse { JSONObject() })
    .put("risk", risk.name)
    .put("state", knowledgeState.name)
    .put("confidence", confidence)
    .put("lastSuccessAt", lastSuccessAt ?: JSONObject.NULL)
    .put("lastFailureAt", lastFailureAt ?: JSONObject.NULL)
    .put("failureCount", failureCount)

fun LearnedTransition.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("from", fromScreenId)
    .put("actionId", actionId)
    .put("to", toScreenId)
    .put("state", knowledgeState.name)
    .put("confidence", confidence)
    .put("observedCount", observedCount)
    .put("successfulCount", successfulCount)
    .put("lastObservedAt", lastObservedAt)
