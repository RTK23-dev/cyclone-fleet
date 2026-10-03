package com.cyclone.mobile.applearner.v31

import com.cyclone.mobile.brain.graphv2.ActivityNode
import com.cyclone.mobile.brain.graphv2.AppNode
import com.cyclone.mobile.brain.graphv2.AppVersionEvidence
import com.cyclone.mobile.brain.graphv2.EdgeRecordResult
import com.cyclone.mobile.brain.graphv2.ElementNode
import com.cyclone.mobile.brain.graphv2.GraphEdgeKey
import com.cyclone.mobile.brain.graphv2.GraphEdgeType
import com.cyclone.mobile.brain.graphv2.GraphEvidenceKind
import com.cyclone.mobile.brain.graphv2.GraphEvidenceSource
import com.cyclone.mobile.brain.graphv2.GraphNode
import com.cyclone.mobile.brain.graphv2.GraphNodeId
import com.cyclone.mobile.brain.graphv2.GraphStaleness
import com.cyclone.mobile.brain.graphv2.GraphVerificationScope
import com.cyclone.mobile.brain.graphv2.GraphVerificationState
import com.cyclone.mobile.brain.graphv2.PageNode
import com.cyclone.mobile.brain.graphv2.RoutineNode
import com.cyclone.mobile.brain.graphv2.SelectorNode
import com.cyclone.mobile.brain.graphv2.TemporalEdgeEvidence
import com.cyclone.mobile.brain.graphv2.TemporalGraphStore
import com.cyclone.mobile.brain.graphv2.TemporalKnowledgeEdge
import com.cyclone.mobile.brain.graphv2.TransitionNode
import java.security.MessageDigest

data class V31LearningEvidence(
    val kind: GraphEvidenceKind,
    val evidenceId: String,
    val producer: String,
    val physicalDeviceEvidence: Boolean,
    val observedAtEpochMillis: Long,
    val succeeded: Boolean,
    val verifiedRequested: Boolean,
    val confidence: Double,
    val appVersionName: String? = null,
    val appVersionCode: Long? = null,
) {
    init {
        require(evidenceId.isNotBlank() && producer.isNotBlank())
        require(observedAtEpochMillis >= 0 && confidence in 0.0..1.0)
        require(appVersionCode == null || appVersionCode >= 0)
    }
}

data class V31LearnedTransition(
    val appPackage: String,
    val appName: String,
    val activityClass: String? = null,
    val fromPageKey: String,
    val fromPageTitle: String,
    val toPageKey: String,
    val toPageTitle: String,
    val actionName: String,
    val elementName: String? = null,
    val selectorKey: String? = null,
    val routineId: String? = null,
    val recoveryName: String? = null,
    val evidence: V31LearningEvidence,
) {
    init {
        require(appPackage.isNotBlank() && fromPageKey.isNotBlank() && toPageKey.isNotBlank())
        require(actionName.isNotBlank())
    }
}

data class V31GraphWriteResult(
    val recorded: Int,
    val alreadyRecorded: Int,
    val rejected: List<EdgeRecordResult.Rejected>,
    val verificationState: GraphVerificationState,
)

/**
 * Additive bridge from legacy App Learner/Teach evidence into the temporal V3 graph. Legacy graph
 * stores remain readable; this class never deletes or rewrites them.
 */
class V31GraphLearningBridge(private val graph: TemporalGraphStore) {
    fun recordTransition(learned: V31LearnedTransition): V31GraphWriteResult {
        val app = AppNode(id("app", learned.appPackage), learned.appPackage, learned.appName.ifBlank { learned.appPackage })
        val fromPage = PageNode(id("page", "${learned.appPackage}|${learned.fromPageKey}"), learned.appPackage, learned.fromPageKey, learned.fromPageTitle)
        val toPage = PageNode(id("page", "${learned.appPackage}|${learned.toPageKey}"), learned.appPackage, learned.toPageKey, learned.toPageTitle)
        register(app, fromPage, toPage)

        val edges = mutableListOf<GraphEdgeKey>()
        edges += GraphEdgeKey(app.id, GraphEdgeType.CONTAINS, fromPage.id)
        edges += GraphEdgeKey(app.id, GraphEdgeType.CONTAINS, toPage.id)

        learned.activityClass?.takeIf(String::isNotBlank)?.let { activityClass ->
            val activity = ActivityNode(id("activity", "${learned.appPackage}|$activityClass"), learned.appPackage, activityClass)
            register(activity)
            edges += GraphEdgeKey(app.id, GraphEdgeType.CONTAINS, activity.id)
            edges += GraphEdgeKey(activity.id, GraphEdgeType.CONTAINS, fromPage.id)
            edges += GraphEdgeKey(activity.id, GraphEdgeType.CONTAINS, toPage.id)
        }

        val transition = TransitionNode(
            id("transition", "${learned.appPackage}|${learned.fromPageKey}|${learned.actionName}|${learned.toPageKey}"),
            learned.actionName,
            learned.actionName,
        )
        register(transition)
        edges += GraphEdgeKey(fromPage.id, GraphEdgeType.CONTAINS, transition.id)
        edges += GraphEdgeKey(fromPage.id, GraphEdgeType.NAVIGATES_TO, toPage.id)
        edges += GraphEdgeKey(
            transition.id,
            if (learned.actionName.contains("submit", ignoreCase = true)) GraphEdgeType.SUBMITS else GraphEdgeType.OPENS,
            toPage.id,
        )

        var element: ElementNode? = null
        learned.elementName?.takeIf(String::isNotBlank)?.let { name ->
            element = ElementNode(id("element", "${learned.appPackage}|${learned.fromPageKey}|$name"), name, name)
            register(requireNotNull(element))
            edges += GraphEdgeKey(fromPage.id, GraphEdgeType.CONTAINS, requireNotNull(element).id)
            if (learned.actionName.contains("scroll", ignoreCase = true)) {
                edges += GraphEdgeKey(fromPage.id, GraphEdgeType.SCROLL_REVEALS, requireNotNull(element).id)
            }
        }

        learned.selectorKey?.takeIf(String::isNotBlank)?.let { selectorKey ->
            val selector = SelectorNode(id("selector", selectorKey), selectorKey, learned.elementName ?: "Learned selector")
            register(selector)
            element?.let { edges += GraphEdgeKey(selector.id, GraphEdgeType.SELECTOR_MATCHES, it.id) }
            edges += GraphEdgeKey(transition.id, GraphEdgeType.REQUIRES, selector.id)
        }

        learned.routineId?.takeIf(String::isNotBlank)?.let { routineId ->
            val routine = RoutineNode(id("routine", routineId), routineId, routineId)
            register(routine)
            edges += GraphEdgeKey(transition.id, GraphEdgeType.USED_BY_ROUTINE, routine.id)
        }

        learned.recoveryName?.takeIf(String::isNotBlank)?.let { recoveryName ->
            val recovery = TransitionNode(id("recovery", "${learned.appPackage}|$recoveryName"), recoveryName, recoveryName)
            register(recovery)
            edges += GraphEdgeKey(transition.id, GraphEdgeType.RECOVERED_BY, recovery.id)
        }

        val evidence = temporalEvidence(learned.evidence, learned.appPackage)
        val outcomes = edges.distinct().map { graph.record(TemporalKnowledgeEdge(it, evidence)) }
        return V31GraphWriteResult(
            recorded = outcomes.count { it is EdgeRecordResult.Recorded },
            alreadyRecorded = outcomes.count { it is EdgeRecordResult.AlreadyRecorded },
            rejected = outcomes.filterIsInstance<EdgeRecordResult.Rejected>(),
            verificationState = evidence.verificationState,
        )
    }

    fun markSuperseded(
        previous: GraphNode,
        replacement: GraphNode,
        evidence: V31LearningEvidence,
        appPackage: String,
    ): EdgeRecordResult {
        require(previous.type == replacement.type) { "SUPERSEDES requires matching node types" }
        register(previous, replacement)
        return graph.record(
            TemporalKnowledgeEdge(
                GraphEdgeKey(replacement.id, GraphEdgeType.SUPERSEDES, previous.id),
                temporalEvidence(evidence, appPackage),
            ),
        )
    }

    private fun temporalEvidence(input: V31LearningEvidence, appPackage: String): TemporalEdgeEvidence {
        val physicalEvidence = input.physicalDeviceEvidence &&
            input.kind != GraphEvidenceKind.CI_FIXTURE && input.kind != GraphEvidenceKind.MODEL_INFERENCE
        val mayVerifyPhysical = input.verifiedRequested && input.succeeded && physicalEvidence
        val verificationState = when {
            mayVerifyPhysical -> GraphVerificationState.VERIFIED
            input.succeeded -> GraphVerificationState.OBSERVED
            else -> GraphVerificationState.UNVERIFIED
        }
        return TemporalEdgeEvidence(
            source = GraphEvidenceSource(input.kind, input.evidenceId, input.producer, physicalEvidence),
            confidence = input.confidence,
            observedAtEpochMillis = input.observedAtEpochMillis,
            lastSucceededAtEpochMillis = input.observedAtEpochMillis.takeIf { input.succeeded },
            lastFailedAtEpochMillis = input.observedAtEpochMillis.takeUnless { input.succeeded },
            successCount = if (input.succeeded) 1 else 0,
            failureCount = if (input.succeeded) 0 else 1,
            appVersion = AppVersionEvidence(appPackage, input.appVersionName, input.appVersionCode),
            verificationState = verificationState,
            verificationScope = if (mayVerifyPhysical) GraphVerificationScope.PHYSICAL_DEVICE else GraphVerificationScope.NONE,
            staleness = GraphStaleness.CURRENT,
        )
    }

    private fun register(vararg nodes: GraphNode) = nodes.forEach(graph::registerNode)

    private fun id(prefix: String, stable: String): GraphNodeId = GraphNodeId("$prefix:${digest(stable).take(40)}")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
