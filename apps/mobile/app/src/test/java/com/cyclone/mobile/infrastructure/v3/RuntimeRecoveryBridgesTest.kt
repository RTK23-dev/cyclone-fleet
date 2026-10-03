package com.cyclone.mobile.infrastructure.v3

import com.cyclone.mobile.platform.module.CycloneApiCompatibility
import com.cyclone.mobile.platform.module.CycloneApiVersion
import com.cyclone.mobile.platform.module.ModuleDescriptor
import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.module.ModuleVersion
import com.cyclone.mobile.platform.modules.ModuleHealthReport
import com.cyclone.mobile.platform.modules.ModuleImportance
import com.cyclone.mobile.platform.modules.ModuleOperationResult
import com.cyclone.mobile.platform.modules.ModuleState
import com.cyclone.mobile.platform.modules.ModuleSupervisor
import com.cyclone.mobile.platform.modules.TrustedModuleDeclaration
import com.cyclone.mobile.platform.modules.TrustedModuleRuntime
import com.cyclone.mobile.runtime.recovery.InMemoryRecoveryStateStore
import com.cyclone.mobile.runtime.recovery.RecoveryCommand
import com.cyclone.mobile.runtime.recovery.RecoveryCommandOutcome
import com.cyclone.mobile.runtime.recovery.RecoveryFailureReason
import com.cyclone.mobile.runtime.recovery.RecoveryManager
import com.cyclone.mobile.runtime.recovery.RecoveryModuleSnapshot
import com.cyclone.mobile.runtime.recovery.RecoverySnapshot
import com.cyclone.mobile.runtime.recovery.RuntimeIdentity
import com.cyclone.mobile.runtime.update.ActivationRequestDecision
import com.cyclone.mobile.runtime.update.RuntimeActivationRequest
import com.cyclone.mobile.runtime.update.RuntimeApiVersion
import com.cyclone.mobile.runtime.update.RuntimeResourceKind
import com.cyclone.mobile.runtime.update.RuntimeSlotId
import com.cyclone.mobile.runtime.update.StagedResourceMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRecoveryBridgesTest {
    @Test
    fun updaterHandoffIsDurableIdempotentAndLeavesRecoveryAsDecisionAuthority() {
        val knownGood = snapshot()
        val store = InMemoryRecoveryStateStore()
        val recovery = RecoveryManager(store)
        recovery.initializeKnownGood(knownGood, 10)
        val bridge = RuntimeUpdateRecoveryBridge(KnownGoodSnapshotSource { knownGood }, recovery)
        val request = request()

        assertEquals(ActivationRequestDecision.Accepted, bridge.requestActivation(request))
        assertEquals(ActivationRequestDecision.Accepted, bridge.requestActivation(request))
        assertEquals("update.2", RecoveryManager(store).state().candidate?.updateId)
        assertTrue(RecoveryManager(store).state().pendingCommand == null)
    }

    @Test
    fun conflictingResourceSchemaVersionsAreRejectedBeforeRecovery() {
        val knownGood = snapshot()
        var recoveryCalls = 0
        val bridge = RuntimeUpdateRecoveryBridge(KnownGoodSnapshotSource { knownGood }) {
            recoveryCalls += 1
            error("must not be called")
        }
        val conflict = request().copy(
            resources = request().resources + request().resources.single().copy(path = "policy/b.json", schemaVersion = 2),
        )

        assertEquals(
            ActivationRequestDecision.Rejected("RECOVERY_SCHEMA_VERSION_CONFLICT"),
            bridge.requestActivation(conflict),
        )
        assertEquals(0, recoveryCalls)
    }

    @Test
    fun recoveryQuarantineUsesSupervisorPublicAuthorityAndProtectsCriticalCore() {
        val optional = declaration("optional.module", ModuleImportance.OPTIONAL)
        val critical = declaration("critical.module", ModuleImportance.CRITICAL_BUILT_IN)
        val supervisor = ModuleSupervisor.fromDeclared(CycloneApiVersion(3, 0), listOf(optional, critical))
        supervisor.startAll(1)
        val bridge = RecoveryModuleCommandBridge(supervisor)

        val optionalResult = bridge.execute(command("command.optional", optional.descriptor.id))
        val criticalResult = bridge.execute(command("command.critical", critical.descriptor.id))

        assertEquals(RecoveryCommandOutcome.SUCCEEDED, optionalResult.outcome)
        assertEquals(ModuleState.QUARANTINED, supervisor.status(optional.descriptor.id)?.state)
        assertEquals(RecoveryCommandOutcome.FAILED, criticalResult.outcome)
        assertEquals(ModuleState.READY, supervisor.status(critical.descriptor.id)?.state)
    }

    private fun request() = RuntimeActivationRequest(
        updateId = "update.2",
        activeKnownGoodSlot = RuntimeSlotId.A,
        candidateSlot = RuntimeSlotId.B,
        runtimeApiVersion = RuntimeApiVersion(3, 0),
        manifestSha256 = "b".repeat(64),
        resources = listOf(
            StagedResourceMetadata(
                path = "policy/a.json",
                kind = RuntimeResourceKind.POLICY_DATA,
                sha256 = "c".repeat(64),
                sizeBytes = 10,
                schemaId = "policy.rules",
                schemaVersion = 1,
            ),
        ),
        requestedAtEpochMillis = 20,
    )

    private fun snapshot() = RecoverySnapshot(
        snapshotId = "snapshot.good",
        capturedAtEpochMillis = 10,
        runtime = RuntimeIdentity("slot-a", "3.0", "a".repeat(64)),
        configurationSha256 = "d".repeat(64),
        modules = listOf(
            RecoveryModuleSnapshot(ModuleId("critical.module"), ModuleVersion(1, 0, 0), true, true),
        ),
        schemas = emptyList(),
        lastUpdateId = null,
    )

    private fun command(id: String, moduleId: ModuleId) = RecoveryCommand.QuarantineOptionalModule(
        commandId = id,
        issuedAtEpochMillis = 30,
        moduleId = moduleId,
        reason = RecoveryFailureReason.OPTIONAL_MODULE_UNHEALTHY,
    )

    private fun declaration(name: String, importance: ModuleImportance) = TrustedModuleDeclaration(
        descriptor = ModuleDescriptor(
            id = ModuleId(name),
            version = ModuleVersion(1, 0, 0),
            compatibleCycloneApi = CycloneApiCompatibility(CycloneApiVersion(3, 0), CycloneApiVersion(4, 0)),
        ),
        runtime = object : TrustedModuleRuntime {
            override fun start() = ModuleOperationResult.Success
            override fun stop() = ModuleOperationResult.Success
            override fun health() = ModuleHealthReport.healthy()
        },
        importance = importance,
    )
}
