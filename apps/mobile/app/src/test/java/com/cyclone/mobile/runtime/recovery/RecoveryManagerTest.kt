package com.cyclone.mobile.runtime.recovery

import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.module.ModuleVersion
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecoveryManagerTest {
    @Test
    fun badCandidateIssuesRollbackAndRestoresLastKnownGood() {
        val manager = manager()
        activate(manager)
        val bad = health("health-bad", 3, coreHealth = ObservedHealth.FAILED)

        val decision = manager.observeCandidateHealth(bad)
        assertTrue(decision is RecoveryDecision.CommandRequired)
        val command = (decision as RecoveryDecision.CommandRequired).command as RecoveryCommand.RollbackRuntime
        assertEquals(candidateRuntime(), command.failedRuntime)
        assertEquals(knownGood().runtime, command.targetKnownGood)
        assertTrue(command.preservesUserData)

        manager.recordCommandResult(RecoveryCommandResult(command.commandId, RecoveryCommandOutcome.SUCCEEDED, 4))
        assertEquals(knownGood().runtime, manager.state().activeRuntime)
        assertEquals(knownGood(), manager.state().lastKnownGood)
        assertNull(manager.state().candidate)
    }

    @Test
    fun goodCandidatePromotesOnlyAfterAllHealthCriteria() {
        val manager = manager(RecoveryHealthCriteria(2, 10, 3, 3))
        activate(manager)

        assertTrue(manager.observeCandidateHealth(health("health-1", 3)) is RecoveryDecision.StateChanged)
        assertEquals(knownGood(), manager.state().lastKnownGood)
        val promotion = manager.observeCandidateHealth(health("health-2", 13))
        assertTrue(promotion is RecoveryDecision.CommandRequired)
        assertEquals(knownGood(), manager.state().lastKnownGood)
        val command = (promotion as RecoveryDecision.CommandRequired).command as RecoveryCommand.PromoteCandidate

        manager.recordCommandResult(RecoveryCommandResult(command.commandId, RecoveryCommandOutcome.SUCCEEDED, 14))
        assertEquals(candidate(), manager.state().lastKnownGood)
        assertEquals(candidateRuntime(), manager.state().activeRuntime)
    }

    @Test
    fun optionalBrokenModuleIsQuarantinedWithoutLifecycleBypass() {
        val manager = manager(RecoveryHealthCriteria(2, 10, 3, 3))
        activate(manager)
        val decision = manager.observeCandidateHealth(health("health-optional", 3, optionalHealth = ObservedHealth.FAILED))

        val command = (decision as RecoveryDecision.CommandRequired).command as RecoveryCommand.QuarantineOptionalModule
        assertEquals(ModuleId("vision.optional"), command.moduleId)
        assertFalse(ModuleId("vision.optional") in manager.state().quarantinedModules)
        manager.recordCommandResult(RecoveryCommandResult(command.commandId, RecoveryCommandOutcome.SUCCEEDED, 4))
        assertTrue(ModuleId("vision.optional") in manager.state().quarantinedModules)
        assertTrue(manager.state().candidate != null)
    }

    @Test
    fun stateAndPendingCommandSurviveRestartWithCanonicalOrdering() {
        val directory = Files.createTempDirectory("cyclone-recovery-test").toFile()
        val file = directory.resolve("state.json")
        try {
            val first = manager(store = FileRecoveryStateStore(file))
            activate(first)
            val issued = first.observeCandidateHealth(health("health-bad", 3, schemasReadable = false))
            assertTrue(issued is RecoveryDecision.CommandRequired)

            val restored = manager(store = FileRecoveryStateStore(file)).state()
            assertEquals(first.state(), restored)
            assertTrue(restored.pendingCommand is RecoveryCommand.RollbackRuntime)
            assertEquals(RecoveryStateCodec.encode(restored), RecoveryStateCodec.encode(RecoveryStateCodec.decode(RecoveryStateCodec.encode(restored))))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun crashLoopEntersOneLauncherTrustedCoreSafeModeWithoutDataErase() {
        val manager = manager(RecoveryHealthCriteria(2, 0, crashLoopThreshold = 3, maximumCandidateBootAttempts = 3))
        manager.initializeKnownGood(knownGood(), 1)
        assertTrue(manager.recordCrash(knownGood().runtime, RecoveryFailureReason.CRASH_LOOP, 2) is RecoveryDecision.StateChanged)
        assertTrue(manager.recordCrash(knownGood().runtime, RecoveryFailureReason.CRASH_LOOP, 3) is RecoveryDecision.StateChanged)
        val decision = manager.recordCrash(knownGood().runtime, RecoveryFailureReason.CRASH_LOOP, 4)

        val command = (decision as RecoveryDecision.CommandRequired).command as RecoveryCommand.EnterSafeMode
        assertEquals(listOf(SafeModePlan.CYCLONE_LAUNCHER), command.plan.launcherComponents)
        assertEquals(SafeModePlan.REQUIRED_TRUSTED_CORE, command.plan.trustedCore)
        assertEquals(setOf(ModuleId("vision.optional")), command.plan.disabledOptionalModules)
        assertTrue(command.plan.preserveUserData)
        assertFalse(command.plan.allowsAutomaticDataErase)
        manager.recordCommandResult(RecoveryCommandResult(command.commandId, RecoveryCommandOutcome.SUCCEEDED, 5))
        assertEquals(command.plan, manager.state().safeModePlan)
    }

    @Test
    fun decisionsAreDeterministicAcrossInputOrderingAndDuplicatesDoNotAdvanceHealth() {
        val first = manager(RecoveryHealthCriteria(2, 0, 3, 3))
        val second = manager(RecoveryHealthCriteria(2, 0, 3, 3))
        activate(first)
        activate(second)
        val ordered = health("health-order", 3, optionalHealth = ObservedHealth.FAILED)
        val reversed = ordered.copy(
            trustedCore = ordered.trustedCore.entries.reversed().associate { it.toPair() },
            modules = ordered.modules.reversed(),
        )

        assertEquals(first.observeCandidateHealth(ordered), second.observeCandidateHealth(reversed))
        assertEquals(RecoveryStateCodec.encode(first.state()), RecoveryStateCodec.encode(second.state()))

        val duplicateManager = manager(RecoveryHealthCriteria(2, 0, 3, 3))
        activate(duplicateManager)
        val healthy = health("same-observation", 3)
        duplicateManager.observeCandidateHealth(healthy)
        assertTrue(duplicateManager.observeCandidateHealth(healthy) is RecoveryDecision.NoChange)
        assertEquals(1, duplicateManager.state().candidate!!.consecutiveHealthyObservations)
    }

    @Test
    fun snapshotsAndAuditCannotCarryPlaintextSecrets() {
        val manager = manager()
        activate(manager)
        val encoded = RecoveryStateCodec.encode(manager.state())

        assertFalse(encoded.contains("hunter2"))
        assertFalse(Regex("(?i)password|passcode|otp|api[_-]?key|bearer|secret").containsMatchIn(encoded))
        expectFailure { knownGood().copy(snapshotId = "password:hunter2") }
        expectFailure {
            SafeModePlan(
                launcherComponents = listOf("com.example/.AlternateLauncher"),
                trustedCore = SafeModePlan.REQUIRED_TRUSTED_CORE,
                disabledOptionalModules = emptySet(),
                preserveUserData = true,
                allowsAutomaticDataErase = false,
            )
        }
        expectFailure {
            SafeModePlan(
                launcherComponents = listOf(SafeModePlan.CYCLONE_LAUNCHER),
                trustedCore = SafeModePlan.REQUIRED_TRUSTED_CORE,
                disabledOptionalModules = emptySet(),
                preserveUserData = true,
                allowsAutomaticDataErase = true,
            )
        }
    }

    @Test
    fun crashAttributionAndBootLimitFreezePriorRuntimeModulesSchemasAndUpdate() {
        val manager = manager(RecoveryHealthCriteria(2, 0, 3, maximumCandidateBootAttempts = 2))
        activate(manager)
        assertTrue(manager.recordCandidateBootAttempt(candidateRuntime(), 3) is RecoveryDecision.StateChanged)
        val decision = manager.recordCandidateBootAttempt(candidateRuntime(), 4)

        assertTrue(decision is RecoveryDecision.CommandRequired)
        val attribution = manager.state().lastCrashAttribution!!
        assertEquals(candidateRuntime(), attribution.previousActiveRuntime)
        assertEquals(candidate().modules, attribution.moduleSet)
        assertEquals(candidate().schemas, attribution.schemas)
        assertEquals("update-2", attribution.lastUpdateId)
        assertEquals(2, attribution.bootAttempts)
        assertEquals(RecoveryFailureReason.CANDIDATE_BOOT_LIMIT, attribution.safeFailureReason)
    }

    @Test
    fun candidateAndHealthCannotOmitEssentialModule() {
        val manager = manager()
        manager.initializeKnownGood(knownGood(), 1)
        val stripped = candidate().copy(modules = candidate().modules.filter { !it.essential })
        val rejected = manager.requestActivation(
            RecoveryActivationHandoff("request-2", "update-2", knownGood(), stripped, 2),
        )
        assertEquals(
            RecoveryActivationDecision.Rejected(RecoveryRejection.CANDIDATE_OMITS_ESSENTIAL_MODULE),
            rejected,
        )

        activate(manager)
        val missingEssentialHealth = health("health-missing", 3).copy(
            modules = listOf(RecoveryModuleHealth(ModuleId("vision.optional"), ObservedHealth.HEALTHY)),
        )
        val rollback = manager.observeCandidateHealth(missingEssentialHealth)
        assertTrue((rollback as RecoveryDecision.CommandRequired).command is RecoveryCommand.RollbackRuntime)
    }

    private fun manager(
        criteria: RecoveryHealthCriteria = RecoveryHealthCriteria(2, 10, 3, 3),
        store: RecoveryStateStore = InMemoryRecoveryStateStore(),
    ) = RecoveryManager(store, criteria)

    private fun activate(manager: RecoveryManager) {
        manager.initializeKnownGood(knownGood(), 1)
        assertEquals(
            RecoveryActivationDecision.Accepted,
            manager.requestActivation(
                RecoveryActivationHandoff("request-2", "update-2", knownGood(), candidate(), 2),
            ),
        )
    }

    private fun health(
        id: String,
        at: Long,
        coreHealth: ObservedHealth = ObservedHealth.HEALTHY,
        optionalHealth: ObservedHealth = ObservedHealth.HEALTHY,
        schemasReadable: Boolean = true,
    ) = RecoveryHealthObservation(
        observationId = id,
        updateId = "update-2",
        runtime = candidateRuntime(),
        observedAtEpochMillis = at,
        trustedCore = TrustedCoreService.entries.associateWith { coreHealth },
        modules = listOf(
            RecoveryModuleHealth(ModuleId("recovery.core"), ObservedHealth.HEALTHY),
            RecoveryModuleHealth(ModuleId("vision.optional"), optionalHealth),
        ),
        runtimeStable = true,
        schemasReadable = schemasReadable,
    )

    private fun knownGood() = snapshot(
        id = "snapshot-a",
        at = 1,
        runtime = RuntimeIdentity("slot-a", "1.0", "a".repeat(64)),
        updateId = "update-1",
    )

    private fun candidate() = snapshot(
        id = "snapshot-b",
        at = 2,
        runtime = candidateRuntime(),
        updateId = "update-2",
    )

    private fun candidateRuntime() = RuntimeIdentity("slot-b", "1.0", "b".repeat(64))

    private fun snapshot(id: String, at: Long, runtime: RuntimeIdentity, updateId: String) = RecoverySnapshot(
        snapshotId = id,
        capturedAtEpochMillis = at,
        runtime = runtime,
        configurationSha256 = "c".repeat(64),
        modules = listOf(
            RecoveryModuleSnapshot(ModuleId("recovery.core"), ModuleVersion(1, 0, 0), enabled = true, essential = true),
            RecoveryModuleSnapshot(ModuleId("vision.optional"), ModuleVersion(1, 1, 0), enabled = true, essential = false),
        ),
        schemas = listOf(RecoverySchemaVersion("runtime.config", 1), RecoverySchemaVersion("module.state", 2)),
        lastUpdateId = updateId,
    ).normalized()

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected validation failure")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
