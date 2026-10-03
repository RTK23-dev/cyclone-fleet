package com.cyclone.mobile.platform.modules

import com.cyclone.mobile.platform.module.CycloneApiCompatibility
import com.cyclone.mobile.platform.module.CycloneApiVersion
import com.cyclone.mobile.platform.module.ModuleDescriptor
import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.module.ModuleVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSupervisorUpdateTest {
    @Test
    fun migrationPlanningBlocksStartWithoutRunningMigration() {
        val runtime = Runtime()
        val declaration = declaration(
            runtime = runtime,
            migrationPlanner = ModuleMigrationPlanner { descriptor ->
                ModuleMigrationPlan(
                    MigrationDisposition.REQUIRED,
                    descriptor.migrationVersion,
                    steps = listOf("copy-v1-to-v2"),
                    reason = "Stored schema is version 1",
                )
            },
            migrationVersion = 2,
        )
        val supervisor = ModuleSupervisor.fromDeclared(API, listOf(declaration))

        val status = supervisor.startAll(0).modules.single()

        assertEquals(ModuleState.UPDATE_PENDING, status.state)
        assertEquals(0, runtime.startCalls)
        assertEquals(MigrationDisposition.REQUIRED, supervisor.migrationPlan(id("sample"))?.disposition)
        assertTrue(status.diagnostics.any { it.code == ModuleDiagnosticCode.MIGRATION_REQUIRED })
    }

    @Test
    fun updatePreflightRejectsWrongModuleAndAcceptsSchemaAwareCandidate() {
        val supervisor = ModuleSupervisor.fromDeclared(API, listOf(declaration(migrationVersion = 1)))
        val wrong = candidate("different", ModuleVersion(2, 0, 0), migrationVersion = 1)
        val migration = candidate("sample", ModuleVersion(2, 0, 0), migrationVersion = 2)

        val rejected = supervisor.preflightUpdate(id("sample"), wrong)
        val accepted = supervisor.preflightUpdate(id("sample"), migration)

        assertEquals(UpdatePreflightDecision.REJECTED, rejected.decision)
        assertEquals(UpdatePreflightDecision.MIGRATION_REQUIRED, accepted.decision)
        val prepared = supervisor.prepareUpdate(id("sample"), migration)
        assertTrue(prepared is SupervisorCommandResult.Applied)
        assertEquals(ModuleState.UPDATE_PENDING, (prepared as SupervisorCommandResult.Applied).status.state)
    }

    @Test
    fun moduleSpecificPreflightCannotBypassSupervisorSafetyChecks() {
        val declaration = declaration(
            updatePreflight = ModuleUpdatePreflight { _, _, _ ->
                ModuleUpdatePreflightResult(UpdatePreflightDecision.READY)
            },
        )
        val supervisor = ModuleSupervisor.fromDeclared(API, listOf(declaration))

        val result = supervisor.preflightUpdate(
            id("sample"),
            candidate("different", ModuleVersion(9, 0, 0), migrationVersion = 0),
        )

        assertEquals(UpdatePreflightDecision.REJECTED, result.decision)
    }

    @Test
    fun rollbackUsesOnlyExplicitTrustedHook() {
        var calledWith: Pair<ModuleVersion, ModuleVersion>? = null
        val declaration = declaration(
            rollbackHook = ModuleRollbackHook { from, target ->
                calledWith = from to target
                ModuleOperationResult.Success
            },
        )
        val supervisor = ModuleSupervisor.fromDeclared(API, listOf(declaration))

        val result = supervisor.rollback(id("sample"), ModuleVersion(0, 9, 0))

        assertTrue(result is SupervisorCommandResult.Applied)
        assertEquals(ModuleVersion(1, 0, 0) to ModuleVersion(0, 9, 0), calledWith)
        assertTrue((result as SupervisorCommandResult.Applied).status.diagnostics.any {
            it.code == ModuleDiagnosticCode.ROLLBACK_COMPLETED
        })
    }

    @Test
    fun rollbackRejectsNonOlderTargetBeforeCallingHook() {
        var calls = 0
        val declaration = declaration(
            rollbackHook = ModuleRollbackHook { _, _ ->
                calls += 1
                ModuleOperationResult.Success
            },
        )
        val supervisor = ModuleSupervisor.fromDeclared(API, listOf(declaration))

        val result = supervisor.rollback(id("sample"), ModuleVersion(1, 0, 0))

        assertTrue(result is SupervisorCommandResult.Rejected)
        assertEquals(0, calls)
    }

    @Test
    fun healthCanDegradeAndRecoverWithoutRestarting() {
        val runtime = Runtime()
        val supervisor = ModuleSupervisor.fromDeclared(API, listOf(declaration(runtime = runtime)))
        supervisor.startAll(0)
        runtime.healthReport = ModuleHealthReport.degraded("provider is slow")

        var status = supervisor.refreshHealth(1).modules.single()
        assertEquals(ModuleState.DEGRADED, status.state)
        assertEquals(1, runtime.startCalls)

        runtime.healthReport = ModuleHealthReport.healthy()
        status = supervisor.refreshHealth(2).modules.single()
        assertEquals(ModuleState.READY, status.state)
        assertEquals(1, runtime.startCalls)
    }

    private fun declaration(
        runtime: Runtime = Runtime(),
        migrationPlanner: ModuleMigrationPlanner = CurrentModuleMigrationPlanner,
        updatePreflight: ModuleUpdatePreflight = DefaultModuleUpdatePreflight,
        rollbackHook: ModuleRollbackHook? = null,
        migrationVersion: Int = 0,
    ) = TrustedModuleDeclaration(
        descriptor = descriptor("sample", ModuleVersion(1, 0, 0), migrationVersion),
        runtime = runtime,
        migrationPlanner = migrationPlanner,
        updatePreflight = updatePreflight,
        rollbackHook = rollbackHook,
    )

    private fun candidate(name: String, version: ModuleVersion, migrationVersion: Int) = ModuleUpdateCandidate(
        descriptor(name, version, migrationVersion),
        manifestDigestSha256 = "a".repeat(64),
    )

    private fun descriptor(name: String, version: ModuleVersion, migrationVersion: Int) = ModuleDescriptor(
        id = id(name),
        version = version,
        compatibleCycloneApi = CycloneApiCompatibility(API, CycloneApiVersion(4, 0)),
        migrationVersion = migrationVersion,
    )

    private fun id(name: String) = ModuleId(name)

    private class Runtime : TrustedModuleRuntime {
        var startCalls = 0
        var healthReport = ModuleHealthReport.healthy()

        override fun start(): ModuleOperationResult {
            startCalls += 1
            return ModuleOperationResult.Success
        }

        override fun stop() = ModuleOperationResult.Success
        override fun health() = healthReport
    }

    companion object {
        val API = CycloneApiVersion(3, 0)
    }
}
