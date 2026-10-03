package com.cyclone.mobile.infrastructure.v3

import com.cyclone.mobile.platform.modules.ModuleSupervisor
import com.cyclone.mobile.platform.modules.SupervisorCommandResult
import com.cyclone.mobile.runtime.recovery.RecoveryActivationDecision
import com.cyclone.mobile.runtime.recovery.RecoveryActivationHandoff
import com.cyclone.mobile.runtime.recovery.RecoveryActivationHandoffSink
import com.cyclone.mobile.runtime.recovery.RecoveryCommand
import com.cyclone.mobile.runtime.recovery.RecoveryCommandOutcome
import com.cyclone.mobile.runtime.recovery.RecoveryCommandResult
import com.cyclone.mobile.runtime.recovery.RecoverySchemaVersion
import com.cyclone.mobile.runtime.recovery.RecoverySnapshot
import com.cyclone.mobile.runtime.recovery.RuntimeIdentity
import com.cyclone.mobile.runtime.update.ActivationRequestDecision
import com.cyclone.mobile.runtime.update.RuntimeActivationRequest
import com.cyclone.mobile.runtime.update.RuntimeActivationRequestSink
import com.cyclone.mobile.runtime.update.RuntimeSlotId
import java.security.MessageDigest

/** Read-only recovery inventory used to bind an updater request to the exact active snapshot. */
fun interface KnownGoodSnapshotSource {
    fun snapshotFor(slot: RuntimeSlotId): RecoverySnapshot?
}

/**
 * Durable authority-preserving handoff from runtime staging to Recovery.
 *
 * This adapter never activates, promotes, or rolls back a runtime. Idempotence and conflict
 * detection remain RecoveryManager responsibilities through [RecoveryActivationHandoffSink].
 */
class RuntimeUpdateRecoveryBridge(
    private val knownGood: KnownGoodSnapshotSource,
    private val recovery: RecoveryActivationHandoffSink,
) : RuntimeActivationRequestSink {
    override fun requestActivation(request: RuntimeActivationRequest): ActivationRequestDecision {
        val baseline = knownGood.snapshotFor(request.activeKnownGoodSlot)
            ?: return ActivationRequestDecision.Rejected("RECOVERY_NO_KNOWN_GOOD_STATE")
        val schemas = request.resources
            .groupBy { it.schemaId }
            .map { (schemaId, resources) ->
                val versions = resources.map { it.schemaVersion }.distinct()
                if (versions.size != 1) return ActivationRequestDecision.Rejected("RECOVERY_SCHEMA_VERSION_CONFLICT")
                RecoverySchemaVersion(schemaId, versions.single())
            }
            .sortedBy { it.schemaId }
        val configurationSha256 = sha256(
            request.resources.sortedBy { it.path }.joinToString("\n") {
                "${it.path}|${it.kind.wireName}|${it.sha256}|${it.sizeBytes}|${it.schemaId}|${it.schemaVersion}"
            },
        )
        val candidate = RecoverySnapshot(
            snapshotId = "candidate.${safeId(request.updateId)}.${configurationSha256.take(12)}",
            capturedAtEpochMillis = request.requestedAtEpochMillis,
            runtime = RuntimeIdentity(
                runtimeId = "slot-${request.candidateSlot.name.lowercase()}",
                runtimeApiVersion = request.runtimeApiVersion.toString(),
                manifestSha256 = request.manifestSha256,
            ),
            configurationSha256 = configurationSha256,
            modules = baseline.modules.map { it.copy() },
            schemas = schemas,
            lastUpdateId = request.updateId,
        )
        val handoff = RecoveryActivationHandoff(
            requestId = "activate.${safeId(request.updateId)}.${request.manifestSha256.take(12)}",
            updateId = request.updateId,
            activeKnownGood = baseline.normalized(),
            candidate = candidate.normalized(),
            requestedAtEpochMillis = request.requestedAtEpochMillis,
        )
        return when (val decision = recovery.requestActivation(handoff)) {
            RecoveryActivationDecision.Accepted -> ActivationRequestDecision.Accepted
            is RecoveryActivationDecision.Rejected ->
                ActivationRequestDecision.Rejected("RECOVERY_${decision.reason.name}")
        }
    }

    private fun safeId(value: String): String = value.take(80)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * Executes only Recovery's optional-module isolation command through the public lifecycle
 * authority. It cannot access supervisor internals and cannot promote or roll back runtimes.
 */
class RecoveryModuleCommandBridge(
    private val supervisor: ModuleSupervisor,
) {
    fun execute(command: RecoveryCommand.QuarantineOptionalModule): RecoveryCommandResult {
        val outcome = when (supervisor.quarantineOptional(command.moduleId, command.reason.name)) {
            is SupervisorCommandResult.Applied -> RecoveryCommandOutcome.SUCCEEDED
            is SupervisorCommandResult.Missing,
            is SupervisorCommandResult.Rejected,
            -> RecoveryCommandOutcome.FAILED
        }
        return RecoveryCommandResult(command.commandId, outcome, command.issuedAtEpochMillis)
    }
}
