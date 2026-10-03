package com.cyclone.mobile.agent.recovery

enum class RecoveryLevel(val stage: Int) {
    KNOWN_VERIFIED_ROUTE(0), CURRENT_SEMANTIC_PAGE(1), GOAL_RANKED_SEARCH(2),
    ADDITIONAL_ELEMENT_INSPECTION(3), BOUNDED_PAGE_EXPLORATION(4), SILENT_SCREENSHOT_VISION(5),
    BACKTRACK_OR_REPLAN(6), HUMAN_GATE(7),
}

enum class EvidenceSource {
    KNOWN_ROUTE, CURRENT_SEMANTIC_PAGE, SEMANTIC_SEARCH, ELEMENT_INSPECTION, PAGE_EXPLORATION,
    SCREENSHOT_VISION, BACKTRACK, VERIFIED_ASSERTION,
}

enum class ProgressClassification { VERIFIED_PROGRESS, NEW_EVIDENCE, NO_PROGRESS, REGRESSION, HUMAN_BOUNDARY, HARD_BLOCKER }
enum class TaskFailureClassification { COMPLETE, RECOVERABLE, HUMAN_OR_GATE, HARD_BLOCKER, NON_CONVERGENCE, CANCELLED }
enum class RecoverableCause {
    STALE_SELECTOR, TARGET_MISSING_FROM_COMPACT_CONTROLS, VERIFICATION_FAILED, PAGE_LOAD_SLOW,
    AFTER_STATE_MISSING, WRONG_TARGET, SAME_PAGE_NO_EFFECT, MALFORMED_MODEL_OUTPUT, AMBIGUOUS_SEMANTICS,
    VISION_NEEDED, WRONG_BRANCH, RETRYABLE_TOOL_OR_TRANSPORT_ERROR,
}
enum class VisionTrigger {
    TARGET_ABSENT_FROM_STRUCTURED_CONTROLS, SEMANTIC_SEARCH_EXHAUSTED,
    TWO_DISTINCT_ACTIONS_WITHOUT_PROGRESS, WEBVIEW_CANVAS_OR_CUSTOM_UI,
    STRUCTURED_REPRESENTATION_SPARSE, SEMANTIC_EVIDENCE_CONFLICT, AMBIGUOUS_AFTER_STATE,
    REPEATED_STALE_OR_VANISHING_TARGETS,
}

data class ObservationEvidence(
    val semanticStateKey: String? = null,
    val accessibilityFingerprint: String? = null,
    val contentKey: String? = null,
    val goalRelevantControls: Set<String> = emptySet(),
    val interactionState: Map<String, String> = emptyMap(),
    val packageName: String? = null,
    val activityName: String? = null,
    val verifiedAssertions: Set<String> = emptySet(),
    val appGraphDistanceToGoal: Int? = null,
    val collectedEvidence: Set<EvidenceSource> = emptySet(),
    val structuredControlCount: Int = 0,
    val rawNodeCount: Int = 0,
    val pageLooksWebOrCanvas: Boolean = false,
    val wrongBranch: Boolean = false,
    val alternateRouteOpenedByBacktrack: Boolean = false,
    val humanBoundary: Boolean = false,
    val hardBlockerEvidence: Set<String> = emptySet(),
)

data class ProgressResult(val classification: ProgressClassification, val reasons: Set<String> = emptySet()) {
    /** New evidence may help the next decision, but it is not verified user-goal progress. */
    val incrementsNoProgressCounter: Boolean
        get() = classification in setOf(ProgressClassification.NEW_EVIDENCE, ProgressClassification.NO_PROGRESS, ProgressClassification.REGRESSION)
}

/**
 * Classifies task progress from semantic evidence. Raw page keys, content hashes and Accessibility
 * fingerprints are deliberately weak evidence: they may change because of animations, clocks,
 * overlays or framework churn. They can inform the next decision, but they never reset a no-progress
 * budget by themselves.
 */
object AgenticProgressClassifier {
    fun classify(before: ObservationEvidence?, after: ObservationEvidence): ProgressResult {
        if (after.hardBlockerEvidence.isNotEmpty()) return ProgressResult(ProgressClassification.HARD_BLOCKER, after.hardBlockerEvidence)
        if (after.humanBoundary) return ProgressResult(ProgressClassification.HUMAN_BOUNDARY, setOf("human_boundary"))
        if (before == null) return if (after.collectedEvidence.isNotEmpty())
            ProgressResult(ProgressClassification.NEW_EVIDENCE, setOf("initial_observation"))
        else ProgressResult(ProgressClassification.NO_PROGRESS)

        val regressionReasons = linkedSetOf<String>()
        val beforeDistance = before.appGraphDistanceToGoal
        val afterDistance = after.appGraphDistanceToGoal
        if (after.wrongBranch && !before.wrongBranch) regressionReasons += "wrong_branch"
        if (beforeDistance != null && afterDistance != null && afterDistance > beforeDistance) regressionReasons += "app_graph_distance_increased"
        if (regressionReasons.isNotEmpty()) return ProgressResult(ProgressClassification.REGRESSION, regressionReasons)

        val verifiedReasons = linkedSetOf<String>()
        if (after.alternateRouteOpenedByBacktrack && !before.alternateRouteOpenedByBacktrack) verifiedReasons += "backtrack_opened_alternate_route"
        if (beforeDistance != null && afterDistance != null && afterDistance < beforeDistance) verifiedReasons += "app_graph_distance_decreased"
        if (after.verifiedAssertions.any { it !in before.verifiedAssertions }) verifiedReasons += "verified_assertion_became_true"
        if (after.goalRelevantControls != before.goalRelevantControls) verifiedReasons += "goal_relevant_controls_changed"
        if (after.interactionState != before.interactionState) verifiedReasons += "interaction_state_changed"
        if (after.packageName != before.packageName || after.activityName != before.activityName) verifiedReasons += "package_or_activity_changed"
        if (verifiedReasons.isNotEmpty()) return ProgressResult(ProgressClassification.VERIFIED_PROGRESS, verifiedReasons)

        val evidenceReasons = linkedSetOf<String>()
        if (after.semanticStateKey != before.semanticStateKey) evidenceReasons += "semantic_state_changed_unverified"
        if (after.accessibilityFingerprint != before.accessibilityFingerprint) evidenceReasons += "accessibility_fingerprint_changed_unverified"
        if (after.contentKey != before.contentKey) evidenceReasons += "content_key_changed_unverified"
        val newEvidence = after.collectedEvidence - before.collectedEvidence
        newEvidence.mapTo(evidenceReasons) { "evidence_${it.name.lowercase()}" }
        return if (evidenceReasons.isNotEmpty()) {
            ProgressResult(ProgressClassification.NEW_EVIDENCE, evidenceReasons)
        } else {
            ProgressResult(ProgressClassification.NO_PROGRESS)
        }
    }
}

data class VisionContext(
    val targetAbsentFromStructuredControls: Boolean = false,
    val semanticSearchExhausted: Boolean = false,
    val materiallyDifferentActionsWithoutProgress: Int = 0,
    val pageLooksWebOrCanvas: Boolean = false,
    val structuredRepresentationSparse: Boolean = false,
    val semanticEvidenceConflictsWithExpectedState: Boolean = false,
    val actionAfterStateAmbiguous: Boolean = false,
    val repeatedStaleOrVanishingTargets: Boolean = false,
    val capturesForUnchangedSemanticState: Int = 0,
    val semanticStateChangedSinceLastCapture: Boolean = false,
)

data class VisionEligibility(val eligible: Boolean, val triggers: Set<VisionTrigger>, val reason: String)

class AgenticVisionPolicy(private val maxCapturesPerUnchangedSemanticState: Int = 1) {
    init { require(maxCapturesPerUnchangedSemanticState in 1..4) }

    fun evaluate(c: VisionContext): VisionEligibility {
        val triggers = linkedSetOf<VisionTrigger>()
        if (c.targetAbsentFromStructuredControls) triggers += VisionTrigger.TARGET_ABSENT_FROM_STRUCTURED_CONTROLS
        if (c.semanticSearchExhausted) triggers += VisionTrigger.SEMANTIC_SEARCH_EXHAUSTED
        if (c.materiallyDifferentActionsWithoutProgress >= 2) triggers += VisionTrigger.TWO_DISTINCT_ACTIONS_WITHOUT_PROGRESS
        if (c.pageLooksWebOrCanvas) triggers += VisionTrigger.WEBVIEW_CANVAS_OR_CUSTOM_UI
        if (c.structuredRepresentationSparse) triggers += VisionTrigger.STRUCTURED_REPRESENTATION_SPARSE
        if (c.semanticEvidenceConflictsWithExpectedState) triggers += VisionTrigger.SEMANTIC_EVIDENCE_CONFLICT
        if (c.actionAfterStateAmbiguous) triggers += VisionTrigger.AMBIGUOUS_AFTER_STATE
        if (c.repeatedStaleOrVanishingTargets) triggers += VisionTrigger.REPEATED_STALE_OR_VANISHING_TARGETS
        if (triggers.isEmpty()) return VisionEligibility(false, emptySet(), "structured_evidence_still_sufficient")
        val budget = c.semanticStateChangedSinceLastCapture || c.capturesForUnchangedSemanticState < maxCapturesPerUnchangedSemanticState
        return VisionEligibility(budget, triggers, if (budget) "structured_evidence_insufficient" else "screenshot_budget_exhausted_for_unchanged_semantic_state")
    }
}

data class RecoveryMemory(
    val attemptedLevels: Set<RecoveryLevel> = emptySet(),
    val attemptedEvidence: Set<EvidenceSource> = emptySet(),
    val semanticSearchExhausted: Boolean = false,
    val capturesForSemanticState: Int = 0,
    val materiallyDifferentActionsWithoutProgress: Int = 0,
)

data class RecoveryRequest(
    val observation: ObservationEvidence,
    val memory: RecoveryMemory = RecoveryMemory(),
    val cause: RecoverableCause? = null,
    val targetAbsentFromStructuredControls: Boolean = false,
    val semanticEvidenceConflictsWithExpectedState: Boolean = false,
    val actionAfterStateAmbiguous: Boolean = false,
    val repeatedStaleOrVanishingTargets: Boolean = false,
    val knownVerifiedRouteAvailable: Boolean = false,
    val semanticSearchAvailable: Boolean = true,
    val supplementalInspectionAvailable: Boolean = true,
    val boundedExplorationAvailable: Boolean = true,
    val backtrackOrAlternateBranchAvailable: Boolean = true,
    val semanticStateChangedSinceLastCapture: Boolean = false,
)

data class RecoveryDecision(val level: RecoveryLevel?, val reason: String, val visionTriggers: Set<VisionTrigger> = emptySet())

class AgenticRecoveryPolicy(private val visionPolicy: AgenticVisionPolicy = AgenticVisionPolicy()) {
    fun next(r: RecoveryRequest): RecoveryDecision {
        val o = r.observation
        fun tried(level: RecoveryLevel, source: EvidenceSource) = level in r.memory.attemptedLevels || source in r.memory.attemptedEvidence
        if (o.hardBlockerEvidence.isNotEmpty()) return RecoveryDecision(null, "hard_blocker_requires_terminal_classification")
        if (o.humanBoundary) return RecoveryDecision(RecoveryLevel.HUMAN_GATE, "observed_human_or_gate_boundary")
        if (r.cause == RecoverableCause.WRONG_BRANCH && r.backtrackOrAlternateBranchAvailable && !tried(RecoveryLevel.BACKTRACK_OR_REPLAN, EvidenceSource.BACKTRACK))
            return RecoveryDecision(RecoveryLevel.BACKTRACK_OR_REPLAN, "wrong_branch_backtrack")
        if (r.knownVerifiedRouteAvailable && !tried(RecoveryLevel.KNOWN_VERIFIED_ROUTE, EvidenceSource.KNOWN_ROUTE))
            return RecoveryDecision(RecoveryLevel.KNOWN_VERIFIED_ROUTE, "known_verified_brain_or_app_graph_route")
        if (o.structuredControlCount > 0 && !tried(RecoveryLevel.CURRENT_SEMANTIC_PAGE, EvidenceSource.CURRENT_SEMANTIC_PAGE))
            return RecoveryDecision(RecoveryLevel.CURRENT_SEMANTIC_PAGE, "current_semantic_page_not_yet_exhausted")
        if (r.semanticSearchAvailable && !r.memory.semanticSearchExhausted && !tried(RecoveryLevel.GOAL_RANKED_SEARCH, EvidenceSource.SEMANTIC_SEARCH))
            return RecoveryDecision(RecoveryLevel.GOAL_RANKED_SEARCH, "goal_ranked_semantic_search")
        if (r.supplementalInspectionAvailable && !tried(RecoveryLevel.ADDITIONAL_ELEMENT_INSPECTION, EvidenceSource.ELEMENT_INSPECTION))
            return RecoveryDecision(RecoveryLevel.ADDITIONAL_ELEMENT_INSPECTION, "inspect_supplemental_current_snapshot_elements")
        if (r.boundedExplorationAvailable && !tried(RecoveryLevel.BOUNDED_PAGE_EXPLORATION, EvidenceSource.PAGE_EXPLORATION))
            return RecoveryDecision(RecoveryLevel.BOUNDED_PAGE_EXPLORATION, "bounded_scroll_wait_refresh_reobserve")

        val vision = visionPolicy.evaluate(VisionContext(
            targetAbsentFromStructuredControls = r.targetAbsentFromStructuredControls,
            semanticSearchExhausted = r.memory.semanticSearchExhausted,
            materiallyDifferentActionsWithoutProgress = r.memory.materiallyDifferentActionsWithoutProgress,
            pageLooksWebOrCanvas = o.pageLooksWebOrCanvas,
            structuredRepresentationSparse = o.structuredControlCount == 0 || (o.rawNodeCount >= 12 && o.structuredControlCount <= 2),
            semanticEvidenceConflictsWithExpectedState = r.semanticEvidenceConflictsWithExpectedState,
            actionAfterStateAmbiguous = r.actionAfterStateAmbiguous,
            repeatedStaleOrVanishingTargets = r.repeatedStaleOrVanishingTargets,
            capturesForUnchangedSemanticState = r.memory.capturesForSemanticState,
            semanticStateChangedSinceLastCapture = r.semanticStateChangedSinceLastCapture,
        ))
        if (vision.eligible && !tried(RecoveryLevel.SILENT_SCREENSHOT_VISION, EvidenceSource.SCREENSHOT_VISION))
            return RecoveryDecision(RecoveryLevel.SILENT_SCREENSHOT_VISION, vision.reason, vision.triggers)
        if (r.backtrackOrAlternateBranchAvailable && !tried(RecoveryLevel.BACKTRACK_OR_REPLAN, EvidenceSource.BACKTRACK))
            return RecoveryDecision(RecoveryLevel.BACKTRACK_OR_REPLAN, "alternate_branch_or_replan")
        return RecoveryDecision(null, "recovery_layers_exhausted_without_human_boundary")
    }
}

data class FailureEvidence(
    val completionVerified: Boolean = false,
    val cancelled: Boolean = false,
    val humanBoundary: Boolean = false,
    val hardBlockerEvidence: Set<String> = emptySet(),
    val recoveryRemaining: Boolean = true,
    val recoveryExhausted: Boolean = false,
    val recoverableCauses: Set<RecoverableCause> = emptySet(),
    val modelStatus: String? = null,
)

object AgenticFailureClassifier {
    fun classify(e: FailureEvidence): TaskFailureClassification = when {
        e.cancelled -> TaskFailureClassification.CANCELLED
        e.completionVerified -> TaskFailureClassification.COMPLETE
        e.humanBoundary -> TaskFailureClassification.HUMAN_OR_GATE
        e.hardBlockerEvidence.isNotEmpty() -> TaskFailureClassification.HARD_BLOCKER
        e.recoveryRemaining -> TaskFailureClassification.RECOVERABLE
        e.recoveryExhausted -> TaskFailureClassification.NON_CONVERGENCE
        e.recoverableCauses.isNotEmpty() -> TaskFailureClassification.RECOVERABLE
        e.modelStatus.equals("blocked", ignoreCase = true) -> TaskFailureClassification.RECOVERABLE
        else -> TaskFailureClassification.RECOVERABLE
    }
}
