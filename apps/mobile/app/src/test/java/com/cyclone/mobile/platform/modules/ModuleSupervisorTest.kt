package com.cyclone.mobile.platform.modules

import com.cyclone.mobile.platform.capability.CapabilityId
import com.cyclone.mobile.platform.capability.CapabilityPermission
import com.cyclone.mobile.platform.module.CycloneApiCompatibility
import com.cyclone.mobile.platform.module.CycloneApiVersion
import com.cyclone.mobile.platform.module.ModuleDependency
import com.cyclone.mobile.platform.module.ModuleDescriptor
import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.module.ModuleVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSupervisorTest {
    @Test
    fun startsDependenciesFirstAndStopsInReverseOrder() {
        val events = mutableListOf<String>()
        val supervisor = supervisor(
            declaration("ui", dependencies = listOf(dependency("knowledge")), runtime = runtime("ui", events)),
            declaration("knowledge", dependencies = listOf(dependency("page")), runtime = runtime("knowledge", events)),
            declaration("page", runtime = runtime("page", events)),
        )

        val started = supervisor.startAll(nowEpochMillis = 10)

        assertEquals(listOf(id("page"), id("knowledge"), id("ui")), started.deterministicStartOrder)
        assertEquals(listOf("start:page", "start:knowledge", "start:ui"), events)
        assertTrue(started.modules.all { it.state == ModuleState.READY })

        supervisor.stopAll()
        assertEquals(
            listOf(
                "start:page",
                "start:knowledge",
                "start:ui",
                "stop:ui",
                "stop:knowledge",
                "stop:page",
            ),
            events,
        )
    }

    @Test
    fun cycleFailsSafelyWithoutInvokingRuntime() {
        val events = mutableListOf<String>()
        val supervisor = supervisor(
            declaration("alpha", listOf(dependency("beta")), runtime("alpha", events)),
            declaration("beta", listOf(dependency("alpha")), runtime("beta", events)),
            declaration("independent", runtime = runtime("independent", events)),
        )

        val snapshot = supervisor.startAll(0)

        assertEquals(listOf(id("independent")), snapshot.deterministicStartOrder)
        assertEquals(listOf("start:independent"), events)
        assertEquals(ModuleState.FAILED, snapshot.module("alpha").state)
        assertEquals(ModuleState.FAILED, snapshot.module("beta").state)
        assertTrue(snapshot.module("alpha").has(ModuleDiagnosticCode.DEPENDENCY_CYCLE))
        assertTrue(snapshot.module("beta").has(ModuleDiagnosticCode.DEPENDENCY_CYCLE))
    }

    @Test
    fun missingAndIncompatibleDependenciesAreExplained() {
        val current = declaration("current", version = ModuleVersion(1, 0, 0))
        val missing = declaration("missing.consumer", listOf(dependency("absent")))
        val versionMismatch = declaration(
            "version.consumer",
            listOf(ModuleDependency(id("current"), minimumVersion = ModuleVersion(2, 0, 0))),
        )
        val apiMismatch = declaration(
            "api.mismatch",
            compatibility = CycloneApiCompatibility(CycloneApiVersion(4, 0), CycloneApiVersion(5, 0)),
        )

        val snapshot = supervisor(current, missing, versionMismatch, apiMismatch).snapshot()

        assertTrue(snapshot.module("missing.consumer").has(ModuleDiagnosticCode.MISSING_DEPENDENCY))
        assertTrue(snapshot.module("version.consumer").has(ModuleDiagnosticCode.INCOMPATIBLE_DEPENDENCY))
        assertTrue(snapshot.module("api.mismatch").has(ModuleDiagnosticCode.INCOMPATIBLE_CYCLONE_API))
    }

    @Test
    fun failedOptionalModuleDoesNotPreventUnrelatedStartup() {
        val throwing = object : TrustedModuleRuntime {
            override fun start() = ModuleOperationResult.Success
            override fun stop() = ModuleOperationResult.Success
            override fun health(): ModuleHealthReport = error("private provider detail")
        }
        val supervisor = supervisor(
            declaration("broken", runtime = throwing),
            declaration("healthy"),
        )

        val snapshot = supervisor.startAll(0)

        assertEquals(ModuleState.FAILED, snapshot.module("broken").state)
        assertTrue(snapshot.module("broken").has(ModuleDiagnosticCode.HEALTH_CHECK_FAILED))
        assertFalse(snapshot.module("broken").diagnostics.any { "private provider detail" in it.message })
        assertEquals(ModuleState.READY, snapshot.module("healthy").state)
    }

    @Test
    fun restartBudgetUsesExplicitTimeAndQuarantinesAfterExhaustion() {
        val broken = RecordingRuntime(startResult = ModuleOperationResult.Failure("not ready"))
        val supervisor = ModuleSupervisor.fromDeclared(
            API,
            listOf(declaration("broken", runtime = broken)),
            RestartPolicy(maxStartAttempts = 3, initialBackoffMillis = 100, backoffMultiplier = 2),
        )

        var status = supervisor.startAll(0).module("broken")
        assertEquals(ModuleState.FAILED, status.state)
        assertEquals(100L, status.nextRestartAtEpochMillis)
        assertEquals(1, broken.startCalls)

        supervisor.restartDue(99)
        assertEquals(1, broken.startCalls)
        status = supervisor.restartDue(100).module("broken")
        assertEquals(2, broken.startCalls)
        assertEquals(300L, status.nextRestartAtEpochMillis)

        supervisor.restartDue(299)
        assertEquals(2, broken.startCalls)
        status = supervisor.restartDue(300).module("broken")
        assertEquals(3, broken.startCalls)
        assertEquals(ModuleState.QUARANTINED, status.state)
        assertNull(status.nextRestartAtEpochMillis)
        assertNotNull(status.quarantineReason)
        assertTrue(status.has(ModuleDiagnosticCode.RESTART_EXHAUSTED))
    }

    @Test
    fun criticalBuiltInCannotBeDisabledAndActiveDependentsProtectDependency() {
        val critical = declaration("critical", importance = ModuleImportance.CRITICAL_BUILT_IN)
        val parent = declaration("parent")
        val child = declaration("child", dependencies = listOf(dependency("parent")))
        val criticalSupervisor = supervisor(critical)
        val graphSupervisor = supervisor(parent, child)

        val criticalResult = criticalSupervisor.disable(id("critical"))
        assertTrue(criticalResult is SupervisorCommandResult.Rejected)
        assertEquals(ModuleDiagnosticCode.CRITICAL_MODULE, (criticalResult as SupervisorCommandResult.Rejected).diagnostic.code)

        graphSupervisor.startAll(0)
        val dependencyResult = graphSupervisor.disable(id("parent"))
        assertTrue(dependencyResult is SupervisorCommandResult.Rejected)
        assertEquals(
            ModuleDiagnosticCode.ACTIVE_DEPENDENTS,
            (dependencyResult as SupervisorCommandResult.Rejected).diagnostic.code,
        )
    }

    @Test
    fun duplicateProvidersAndDuplicateDeclarationsNeverSelectByInputOrder() {
        val providerOne = declaration("provider.one", provides = setOf(CapabilityId("vision.inspect")))
        val providerTwo = declaration("provider.two", provides = setOf(CapabilityId("vision.inspect")))
        val duplicateA = declaration("duplicate", version = ModuleVersion(1, 0, 0))
        val duplicateB = declaration("duplicate", version = ModuleVersion(2, 0, 0))

        val forward = supervisor(providerOne, providerTwo, duplicateA, duplicateB).snapshot()
        val reverse = supervisor(duplicateB, duplicateA, providerTwo, providerOne).snapshot()

        assertEquals(forward.deterministicStartOrder, reverse.deterministicStartOrder)
        assertEquals(forward.discoveryDiagnostics, reverse.discoveryDiagnostics)
        assertEquals(forward.modules.map { it.descriptor.id to it.diagnostics }, reverse.modules.map { it.descriptor.id to it.diagnostics })
        assertTrue(forward.module("provider.one").has(ModuleDiagnosticCode.DUPLICATE_PROVIDER))
        assertTrue(forward.module("provider.two").has(ModuleDiagnosticCode.DUPLICATE_PROVIDER))
        assertFalse(forward.modules.any { it.descriptor.id == id("duplicate") })
        assertTrue(forward.discoveryDiagnostics.any { it.code == ModuleDiagnosticCode.DUPLICATE_MODULE })
    }

    @Test
    fun inventoryExposesPermissionAndCapabilityDeclarationsWithoutExecutingThem() {
        val permission = CapabilityPermission("policy.phone.type", rationale = "Typing requires policy approval")
        val declaration = declaration(
            "typing.adapter",
            provides = setOf(CapabilityId("phone.type")),
            permissions = setOf(permission),
        )
        val status = supervisor(declaration).snapshot().module("typing.adapter")

        assertEquals(setOf(CapabilityId("phone.type")), status.descriptor.provides)
        assertEquals(setOf(permission), status.descriptor.permissions)
        assertEquals(0, (declaration.runtime as RecordingRuntime).startCalls)
    }

    private fun supervisor(vararg declarations: TrustedModuleDeclaration): ModuleSupervisor =
        ModuleSupervisor.fromDeclared(API, declarations.asList())

    private fun declaration(
        name: String,
        dependencies: List<ModuleDependency> = emptyList(),
        runtime: TrustedModuleRuntime = RecordingRuntime(),
        version: ModuleVersion = ModuleVersion(1, 0, 0),
        compatibility: CycloneApiCompatibility = COMPATIBILITY,
        provides: Set<CapabilityId> = emptySet(),
        permissions: Set<CapabilityPermission> = emptySet(),
        importance: ModuleImportance = ModuleImportance.OPTIONAL,
    ) = TrustedModuleDeclaration(
        descriptor = ModuleDescriptor(
            id = id(name),
            version = version,
            compatibleCycloneApi = compatibility,
            dependencies = dependencies,
            provides = provides,
            permissions = permissions,
        ),
        runtime = runtime,
        importance = importance,
    )

    private fun dependency(name: String) = ModuleDependency(id(name))
    private fun id(name: String) = ModuleId(name)

    private fun ModuleSupervisorSnapshot.module(name: String) = modules.single { it.descriptor.id == id(name) }
    private fun ModuleStatus.has(code: ModuleDiagnosticCode) = diagnostics.any { it.code == code }

    private class RecordingRuntime(
        private val name: String? = null,
        private val events: MutableList<String>? = null,
        var startResult: ModuleOperationResult = ModuleOperationResult.Success,
        var stopResult: ModuleOperationResult = ModuleOperationResult.Success,
        var healthReport: ModuleHealthReport = ModuleHealthReport.healthy(),
    ) : TrustedModuleRuntime {
        var startCalls = 0
            private set

        override fun start(): ModuleOperationResult {
            startCalls += 1
            name?.let { events?.add("start:$it") }
            return startResult
        }

        override fun stop(): ModuleOperationResult {
            name?.let { events?.add("stop:$it") }
            return stopResult
        }

        override fun health(): ModuleHealthReport = healthReport
    }

    private fun runtime(name: String, events: MutableList<String>) = RecordingRuntime(name, events)

    companion object {
        val API = CycloneApiVersion(3, 0)
        val COMPATIBILITY = CycloneApiCompatibility(CycloneApiVersion(3, 0), CycloneApiVersion(4, 0))
    }
}
