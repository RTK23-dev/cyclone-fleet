package com.cyclone.mobile.applearner.v31

import com.cyclone.mobile.applearner.AppGraphSnapshot
import com.cyclone.mobile.brain.graphv2.GraphEvidenceKind

/**
 * Read-only migration adapter for the current AppLearner V1 snapshot. Imported legacy knowledge is
 * intentionally OBSERVED rather than re-claiming old VERIFIED labels as fresh physical proof.
 */
class LegacyAppGraphV2Adapter(private val bridge: V31GraphLearningBridge) {
    fun import(snapshot: AppGraphSnapshot): List<V31GraphWriteResult> {
        val screens = snapshot.screens.associateBy { it.id }
        val actions = snapshot.actions.associateBy { it.id }
        return snapshot.transitions.mapNotNull { transition ->
            val from = screens[transition.fromScreenId] ?: return@mapNotNull null
            val to = screens[transition.toScreenId] ?: return@mapNotNull null
            val action = actions[transition.actionId] ?: return@mapNotNull null
            bridge.recordTransition(
                V31LearnedTransition(
                    appPackage = snapshot.app.packageName,
                    appName = snapshot.app.label,
                    activityClass = from.recognition.className,
                    fromPageKey = from.identity,
                    fromPageTitle = from.title,
                    toPageKey = to.identity,
                    toPageTitle = to.title,
                    actionName = action.semanticName.ifBlank { action.label },
                    elementName = action.semanticName.ifBlank { action.label },
                    selectorKey = action.selectorJson.takeIf(String::isNotBlank),
                    evidence = V31LearningEvidence(
                        kind = GraphEvidenceKind.LEGACY_GRAPH_IMPORT,
                        evidenceId = "legacy-${transition.id}",
                        producer = "legacy-app-graph",
                        physicalDeviceEvidence = false,
                        observedAtEpochMillis = transition.lastObservedAt,
                        succeeded = transition.successfulCount > 0,
                        verifiedRequested = false,
                        confidence = transition.confidence,
                        appVersionName = snapshot.app.versionName,
                        appVersionCode = snapshot.app.versionCode,
                    ),
                ),
            )
        }
    }
}
