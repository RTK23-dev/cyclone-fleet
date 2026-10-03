package com.cyclone.mobile.automation.capsule

import com.cyclone.mobile.platform.capability.CapabilityId

private val ROUTINE_ID_PATTERN = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
private val FIELD_ID_PATTERN = Regex("[a-z][A-Za-z0-9_.-]*")
private val PACKAGE_PATTERN = Regex("[a-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")

@JvmInline
value class RoutineId(val value: String) : Comparable<RoutineId> {
    init { require(ROUTINE_ID_PATTERN.matches(value)) { "Invalid routine id: $value" } }
    override fun compareTo(other: RoutineId) = value.compareTo(other.value)
    override fun toString() = value
}

@JvmInline
value class RoutineStepId(val value: String) : Comparable<RoutineStepId> {
    init { require(FIELD_ID_PATTERN.matches(value)) { "Invalid routine step id: $value" } }
    override fun compareTo(other: RoutineStepId) = value.compareTo(other.value)
    override fun toString() = value
}

data class RoutineVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<RoutineVersion> {
    init { require(major >= 0 && minor >= 0 && patch >= 0) }
    override fun compareTo(other: RoutineVersion) =
        compareValuesBy(this, other, RoutineVersion::major, RoutineVersion::minor, RoutineVersion::patch)
    override fun toString() = "$major.$minor.$patch"
}

enum class RoutineInputType { TEXT, NUMBER, BOOLEAN, PACKAGE, SECRET_REFERENCE }

data class RoutineInput(
    val name: String,
    val type: RoutineInputType,
    val required: Boolean = true,
    val sensitive: Boolean = false,
    val description: String,
) {
    init {
        require(FIELD_ID_PATTERN.matches(name)) { "Invalid routine input name: $name" }
        require(description.isNotBlank())
        require(type != RoutineInputType.SECRET_REFERENCE || sensitive) {
            "Secret-reference inputs must be sensitive"
        }
    }
}

sealed interface RoutineArgument {
    data class InputReference(val inputName: String) : RoutineArgument {
        init { require(FIELD_ID_PATTERN.matches(inputName)) }
    }
    data class SecretReference(val inputName: String) : RoutineArgument {
        init { require(FIELD_ID_PATTERN.matches(inputName)) }
    }
    data class NonSensitiveLiteral(val value: String) : RoutineArgument {
        init {
            require(value.length <= 2_000) { "Routine literal is too large" }
            require(!SENSITIVE_SHAPE.containsMatchIn(value)) { "Literal resembles sensitive data; use a reference" }
        }

        private companion object {
            val SENSITIVE_SHAPE = Regex("(?i)(password|passcode|otp|token|api[_-]?key|secret|bearer)\\s*[:=]")
        }
    }
}

data class RoutineActionProposal(
    val capabilityId: CapabilityId,
    val operation: String,
    val arguments: Map<String, RoutineArgument> = emptyMap(),
) {
    init {
        require(FIELD_ID_PATTERN.matches(operation)) { "Invalid typed operation: $operation" }
        require(arguments.keys.all(FIELD_ID_PATTERN::matches))
        if (capabilityId.value == "phone.type") {
            require(arguments.values.none { it is RoutineArgument.NonSensitiveLiteral }) {
                "phone.type values must be supplied by input/secret reference, never persisted literals"
            }
        }
    }
}

enum class RecoveryPrimitive {
    REOBSERVE,
    RETRY_SELECTOR,
    SEARCH_PAGE,
    RETURN_TO_KNOWN_PAGE,
    REPLAN,
    HUMAN_TAKEOVER,
}

data class RoutineRecoveryPlan(
    val maximumAttempts: Int,
    val sequence: List<RecoveryPrimitive>,
) {
    init {
        require(maximumAttempts in 0..10) { "Recovery attempt bound must be 0..10" }
        require(sequence.size <= 10) { "Recovery sequence is too large" }
        require(maximumAttempts == 0 || sequence.isNotEmpty())
    }
}

enum class RoutineStepKind { ACTION_PROPOSAL, OBSERVE, VERIFY, WAIT_FOR_USER }

data class RoutineStep(
    val id: RoutineStepId,
    val name: String,
    val kind: RoutineStepKind,
    val action: RoutineActionProposal? = null,
    val nextStepIds: List<RoutineStepId> = emptyList(),
    val verificationIds: List<String> = emptyList(),
    val recovery: RoutineRecoveryPlan = RoutineRecoveryPlan(0, emptyList()),
) {
    init {
        require(name.isNotBlank())
        require((kind == RoutineStepKind.ACTION_PROPOSAL) == (action != null)) {
            "Only action-proposal steps carry an action"
        }
        require(nextStepIds.distinct().size == nextStepIds.size)
        require(verificationIds.all(FIELD_ID_PATTERN::matches))
        require(verificationIds.distinct().size == verificationIds.size)
    }
}

enum class VerificationKind {
    ACTION_RESULT_OK,
    PAGE_IDENTITY,
    ELEMENT_PRESENT,
    TRANSITION_OBSERVED,
    VALUE_MATCHES,
}

data class RoutineVerification(
    val id: String,
    val kind: VerificationKind,
    val expectedReference: String,
    val required: Boolean = true,
) {
    init {
        require(FIELD_ID_PATTERN.matches(id))
        require(expectedReference.isNotBlank())
        require(expectedReference.length <= 1_000)
    }
}

enum class RoutineConfirmation { NONE, WHEN_POLICY_REQUIRES, ALWAYS }

data class RoutinePolicyRequirement(
    val capabilityId: CapabilityId,
    val policyCategory: String,
    val confirmation: RoutineConfirmation,
) {
    init {
        require(FIELD_ID_PATTERN.matches(policyCategory))
    }
}

data class RoutineProvenance(
    val sourceType: String,
    val sourceId: String,
    val createdAtEpochMillis: Long,
    val author: String,
) {
    init {
        require(FIELD_ID_PATTERN.matches(sourceType))
        require(sourceId.isNotBlank() && author.isNotBlank())
        require(createdAtEpochMillis >= 0)
    }
}

data class RoutineCompatibility(
    val minimumCapsuleApi: Int,
    val maximumCapsuleApiExclusive: Int,
    val minimumAndroidApi: Int = 34,
) {
    init {
        require(minimumCapsuleApi >= 1 && maximumCapsuleApiExclusive > minimumCapsuleApi)
        require(minimumAndroidApi >= 1)
    }
}

data class RoutineGraph(
    val entryStepId: RoutineStepId,
    val steps: List<RoutineStep>,
    val maximumTransitions: Int,
) {
    init {
        require(steps.isNotEmpty())
        require(steps.map { it.id }.distinct().size == steps.size) { "Step ids must be unique" }
        val ids = steps.map { it.id }.toSet()
        require(entryStepId in ids) { "Entry step must exist" }
        require(steps.flatMap { it.nextStepIds }.all { it in ids }) { "Step graph contains an unknown target" }
        require(maximumTransitions in 1..10_000) { "Transition bound must be 1..10000" }
    }
}

data class CycloneRoutineCapsule(
    val schemaVersion: Int,
    val routineId: RoutineId,
    val routineVersion: RoutineVersion,
    val intent: String,
    val inputs: List<RoutineInput>,
    val requiredCapabilities: Set<CapabilityId>,
    val requiredPackages: Set<String>,
    val graph: RoutineGraph,
    val verification: List<RoutineVerification>,
    val policyRequirements: List<RoutinePolicyRequirement>,
    val provenance: RoutineProvenance,
    val compatibility: RoutineCompatibility,
) {
    init {
        require(schemaVersion >= 1)
        require(intent.isNotBlank() && intent.length <= 4_000)
        require(inputs.map { it.name }.distinct().size == inputs.size)
        require(requiredPackages.all(PACKAGE_PATTERN::matches))
        require(verification.map { it.id }.distinct().size == verification.size)
        val verificationIds = verification.map { it.id }.toSet()
        require(graph.steps.flatMap { it.verificationIds }.all { it in verificationIds })
        val actions = graph.steps.mapNotNull { it.action }
        require(actions.all { it.capabilityId in requiredCapabilities }) {
            "Every action capability must be declared"
        }
        val policyCapabilities = policyRequirements.map { it.capabilityId }.toSet()
        require(actions.all { it.capabilityId in policyCapabilities }) {
            "Every action proposal needs a policy requirement"
        }
        val inputsByName = inputs.associateBy { it.name }
        actions.flatMap { it.arguments.values }.forEach { argument ->
            when (argument) {
                is RoutineArgument.InputReference -> require(argument.inputName in inputsByName)
                is RoutineArgument.SecretReference -> require(inputsByName[argument.inputName]?.sensitive == true)
                is RoutineArgument.NonSensitiveLiteral -> Unit
            }
        }
    }
}

internal fun CycloneRoutineCapsule.normalized(): CycloneRoutineCapsule = copy(
    inputs = inputs.sortedBy { it.name }.map { it.copy() },
    requiredCapabilities = requiredCapabilities.toSortedSet(),
    requiredPackages = requiredPackages.toSortedSet(),
    graph = graph.copy(
        steps = graph.steps.sortedBy { it.id }.map { step ->
            step.copy(
                action = step.action?.copy(arguments = step.action.arguments.toSortedMap()),
                nextStepIds = step.nextStepIds.sorted(),
                verificationIds = step.verificationIds.sorted(),
                recovery = step.recovery.copy(sequence = step.recovery.sequence.toList()),
            )
        },
    ),
    verification = verification.sortedBy { it.id }.map { it.copy() },
    policyRequirements = policyRequirements.sortedWith(compareBy({ it.capabilityId }, { it.policyCategory })),
    provenance = provenance.copy(),
    compatibility = compatibility.copy(),
)
