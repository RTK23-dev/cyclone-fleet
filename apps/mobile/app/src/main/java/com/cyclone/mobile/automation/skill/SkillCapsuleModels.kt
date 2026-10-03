package com.cyclone.mobile.automation.skill

/**
 * V4 skill capsule contract (frozen in docs/agent-system/V4_BUILD_BIBLE.md).
 *
 * Capsules are review artifacts. Compile always emits [SkillCapsuleStatus.DRAFT] and
 * [enabled] = false. Workers must not promote; human review lives in Automations.
 */
enum class SkillCapsuleStatus {
    DRAFT,
    REVIEW,
    VERIFIED,
    QUARANTINED,
}

data class SkillWhen(
    val pageKey: String,
    val preconditions: List<String> = emptyList(),
)

data class RankedSelector(
    val kind: String,
    val value: String,
    val confidence: Double,
) {
    init {
        require(confidence in 0.0..1.0) { "Selector confidence must be 0..1" }
        require(kind.isNotBlank() && value.isNotBlank())
    }
}

data class SkillVerifier(
    val afterPageKey: String? = null,
    val text: String? = null,
    val goneControl: String? = null,
) {
    init {
        require(!afterPageKey.isNullOrBlank() || !text.isNullOrBlank() || !goneControl.isNullOrBlank()) {
            "Verifier needs after pageKey, text, or gone-control"
        }
    }
}

data class SkillEvidence(
    val traces: List<String> = emptyList(),
)

data class SkillStepDraft(
    val whenClause: String,
    val thenClause: String,
    val checkClause: String,
    val action: String,
    val selectors: List<RankedSelector> = emptyList(),
    val verifiers: List<SkillVerifier> = emptyList(),
    val params: Map<String, String> = emptyMap(),
    val beforePageKey: String? = null,
    val afterPageKey: String? = null,
    val verified: Boolean = false,
    val evidenceTrace: String? = null,
)

data class SkillCompiledStep(
    val id: String,
    val whenClause: String,
    val thenClause: String,
    val checkClause: String,
    val action: String,
    val selectors: List<RankedSelector>,
    val verifiers: List<SkillVerifier>,
    val params: Map<String, String>,
    val beforePageKey: String?,
    val afterPageKey: String?,
)

data class SkillCapsule(
    val id: String,
    val app: String,
    val goal: String,
    val whenPage: SkillWhen,
    val steps: List<SkillCompiledStep>,
    val selectors: List<RankedSelector>,
    val verifiers: List<SkillVerifier>,
    val params: Map<String, String>,
    val evidence: SkillEvidence,
    val status: SkillCapsuleStatus,
    val enabled: Boolean = false,
) {
    init {
        require(id.isNotBlank() && app.isNotBlank() && goal.isNotBlank())
        require(steps.size >= 2) { "A skill capsule needs 2+ steps" }
        require(!enabled) { "Compiled capsules stay disabled for review" }
        require(status == SkillCapsuleStatus.DRAFT || status == SkillCapsuleStatus.QUARANTINED)
    }
}

data class SkillCompileInput(
    val app: String,
    val goal: String,
    val startPageKey: String,
    val preconditions: List<String> = emptyList(),
    val steps: List<SkillStepDraft>,
    val params: Map<String, String> = emptyMap(),
    val nowEpochMillis: Long = 0L,
)

sealed interface SkillCompileResult {
    data class DraftWritten(val capsule: SkillCapsule) : SkillCompileResult
    data class Rejected(val reason: String) : SkillCompileResult
}
