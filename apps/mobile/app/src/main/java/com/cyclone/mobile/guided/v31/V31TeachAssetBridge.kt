package com.cyclone.mobile.guided.v31

import com.cyclone.mobile.applearner.v31.V31GraphLearningBridge
import com.cyclone.mobile.applearner.v31.V31LearnedTransition
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.capsule.CycloneRoutineCapsule
import com.cyclone.mobile.automation.capsule.LegacyAutomationCapsuleAdapter
import com.cyclone.mobile.automation.capsule.RoutineMigrationResult
import com.cyclone.mobile.brain.memory.api.MemoryClass
import com.cyclone.mobile.brain.memory.api.MemoryVerificationState
import com.cyclone.mobile.brain.v31.V31MemoryBridge
import com.cyclone.mobile.brain.v31.V31MemoryProducer
import com.cyclone.mobile.brain.v31.V31MemoryWriteOutcome
import com.cyclone.mobile.brain.v31.V31StructuredMemoryWrite

data class V31TeachCompletion(
    val compiledAutomation: AutomationDefinition,
    val appPackage: String,
    val safeSummary: String,
    val pagesLearned: Int,
    val evidenceId: String,
    val observedAtEpochMillis: Long,
    val author: String,
    val physicalDeviceEvidence: Boolean,
    val transitions: List<V31LearnedTransition>,
) {
    init {
        require(appPackage.isNotBlank() && evidenceId.isNotBlank() && author.isNotBlank())
        require(pagesLearned >= 0 && observedAtEpochMillis >= 0)
    }
}

sealed interface V31TeachAssetResult {
    data class Ready(
        val capsule: CycloneRoutineCapsule,
        val memory: V31MemoryWriteOutcome,
        val graphEdgesRecorded: Int,
        val warnings: List<String>,
    ) : V31TeachAssetResult

    data class Blocked(val reasons: List<String>) : V31TeachAssetResult
}

/**
 * Consumes the existing Follow Me / TeachingRoutineCompiler output. No parallel teaching store is
 * created: the existing capture remains canonical and this bridge only proposes V3 assets.
 */
class V31TeachAssetBridge(
    private val graph: V31GraphLearningBridge,
    private val memory: V31MemoryBridge,
) {
    fun compile(completion: V31TeachCompletion): V31TeachAssetResult {
        val migrated = LegacyAutomationCapsuleAdapter.migrate(
            source = completion.compiledAutomation,
            createdAtEpochMillis = completion.observedAtEpochMillis,
            author = completion.author,
        )
        if (migrated is RoutineMigrationResult.Blocked) return V31TeachAssetResult.Blocked(migrated.reasons)
        val ready = migrated as RoutineMigrationResult.Ready
        val graphResults = completion.transitions.map(graph::recordTransition)
        val graphFailures = graphResults.flatMap { it.rejected }
        val memoryResult = memory.write(
            V31StructuredMemoryWrite(
                producer = V31MemoryProducer.TEACH,
                appPackage = completion.appPackage,
                routineId = ready.capsule.routineId.value,
                evidenceId = completion.evidenceId,
                observedAtEpochMillis = completion.observedAtEpochMillis,
                confidence = if (completion.physicalDeviceEvidence) 0.95 else 0.65,
                verificationState = if (completion.physicalDeviceEvidence) {
                    MemoryVerificationState.VERIFIED
                } else {
                    MemoryVerificationState.OBSERVED
                },
                verifiedRuntimeEvidence = completion.physicalDeviceEvidence,
                memoryClass = MemoryClass.DOCUMENT_REFERENCE,
                fields = mapOf(
                    "summary" to completion.safeSummary,
                    "pagesLearned" to completion.pagesLearned.toString(),
                    "routineId" to ready.capsule.routineId.value,
                    "routineVersion" to ready.capsule.routineVersion.toString(),
                ),
            ),
        )
        return V31TeachAssetResult.Ready(
            capsule = ready.capsule,
            memory = memoryResult,
            graphEdgesRecorded = graphResults.sumOf { it.recorded },
            warnings = (ready.warnings + graphFailures.map { "Graph evidence rejected: ${it.reason}" }).distinct(),
        )
    }
}
