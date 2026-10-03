package com.cyclone.mobile.platform.catalog

import com.cyclone.mobile.platform.capability.CapabilityId
import com.cyclone.mobile.platform.capability.CapabilityPermission
import com.cyclone.mobile.platform.module.CycloneApiCompatibility
import com.cyclone.mobile.platform.module.CycloneApiVersion
import com.cyclone.mobile.platform.module.ModuleDependency
import com.cyclone.mobile.platform.module.ModuleDescriptor
import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.module.ModuleVersion
import com.cyclone.mobile.platform.module.RestartRequirement
import com.cyclone.mobile.platform.modules.ModuleHealthReport
import com.cyclone.mobile.platform.modules.ModuleImportance
import com.cyclone.mobile.platform.modules.ModuleOperationResult
import com.cyclone.mobile.platform.modules.ModuleState
import com.cyclone.mobile.platform.modules.ModuleSupervisor
import com.cyclone.mobile.platform.modules.ModuleUpdateCandidate
import com.cyclone.mobile.platform.modules.RestartPolicy
import com.cyclone.mobile.platform.modules.TrustedModuleDeclaration
import com.cyclone.mobile.platform.modules.TrustedModuleOrigin
import com.cyclone.mobile.platform.modules.TrustedModuleRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleCatalogTest {
    @Test
    fun emptyCatalogIsAHealthyOfflineFirstState() {
        val state = controller(supervisor()).state()

        assertTrue(state.isEmpty)
        assertEquals(0, state.issueCount)
        assertEquals(0, state.updateCount)
        assertTrue(state.localOnlyMessage.contains("No network"))
    }

    @Test
    fun modulesSortBuiltInsFirstThenByStableDisplayName() {
        val declaredAlpha = declaration("third.alpha", origin = TrustedModuleOrigin.DECLARED_TRUSTED)
        val builtZulu = declaration("core.zulu")
        val builtBeta = declaration("core.beta")
        val metadata = listOf(
            presentation("third.alpha", "Alpha"),
            presentation("core.zulu", "Zulu"),
            presentation("core.beta", "Beta"),
        )

        val forward = ModuleCatalogPresenter().present(
            supervisor(declaredAlpha, builtZulu, builtBeta).snapshot(),
            metadata,
            0,
        )
        val reverse = ModuleCatalogPresenter().present(
            supervisor(builtBeta, builtZulu, declaredAlpha).snapshot(),
            metadata.reversed(),
            0,
        )

        assertEquals(listOf("Beta", "Zulu", "Alpha"), forward.modules.map { it.displayName })
        assertEquals(forward, reverse)
        assertTrue(forward.modules[0].isBuiltIn)
        assertFalse(forward.modules.last().isBuiltIn)
    }

    @Test
    fun cardModelShowsTypedModuleMetadataAndExperimentalStatus() {
        val declaration = declaration(
            name = "vision.router",
            importance = ModuleImportance.CRITICAL_BUILT_IN,
            provides = setOf(CapabilityId("vision.inspect")),
            permissions = setOf(
                CapabilityPermission("policy.vision.remote", rationale = "Remote images require approval"),
            ),
            restartRequirement = RestartRequirement.APPLICATION,
        )
        val metadata = presentation(
            "vision.router",
            "Cyclone Vision",
            channel = CatalogReleaseChannel.EXPERIMENTAL,
        )

        val module = ModuleCatalogPresenter().present(supervisor(declaration).snapshot(), listOf(metadata), 0).modules.single()

        assertEquals("Cyclone Vision", module.displayName)
        assertEquals("Cyclone", module.providerName)
        assertEquals("1.0.0", module.version)
        assertTrue(module.isBuiltIn)
        assertTrue(module.isCritical)
        assertEquals(CatalogReleaseChannel.EXPERIMENTAL, module.releaseChannel)
        assertEquals(listOf("vision.inspect"), module.capabilities.map { it.id })
        assertEquals(listOf("policy.vision.remote"), module.permissions.map { it.id })
        assertEquals("Cyclone restart required", module.restartRequirementLabel)
        assertTrue(module.compatibilityLabel.contains("3.0"))
        assertFalse(module.management.canToggle)
    }

    @Test
    fun criticalDisableRequestCannotBypassSupervisorPolicy() {
        val supervisor = supervisor(
            declaration("policy.core", importance = ModuleImportance.CRITICAL_BUILT_IN),
        )
        val controller = controller(supervisor)

        val result = controller.setEnabled(id("policy.core"), enabled = false)

        assertTrue(result is ModuleCatalogCommandResult.Rejected)
        assertTrue(supervisor.status(id("policy.core"))!!.enabled)
    }

    @Test
    fun activeDependencyCannotBeDisabledThroughCatalog() {
        val supervisor = supervisor(
            declaration("page.core"),
            declaration("vision.router", dependencies = listOf(ModuleDependency(id("page.core")))),
        )
        supervisor.startAll(0)
        val result = controller(supervisor).setEnabled(id("page.core"), enabled = false)

        assertTrue(result is ModuleCatalogCommandResult.Rejected)
        assertEquals(ModuleState.READY, supervisor.status(id("page.core"))!!.state)
    }

    @Test
    fun updateRequestDelegatesToSupervisorPreflightAndState() {
        val supervisor = supervisor(declaration("vision.router"))
        val candidate = ModuleUpdateCandidate(
            descriptor = descriptor("vision.router", version = ModuleVersion(2, 0, 0)),
            manifestDigestSha256 = "a".repeat(64),
        )

        val result = controller(supervisor).prepareUpdate(id("vision.router"), candidate)

        assertTrue(result is ModuleCatalogCommandResult.Applied)
        assertEquals(ModuleState.UPDATE_PENDING, supervisor.status(id("vision.router"))!!.state)
        assertEquals(1, (result as ModuleCatalogCommandResult.Applied).state.updateCount)
    }

    @Test
    fun failedModuleHasReadableDiagnosticAndRetryTiming() {
        val broken = Runtime(startResult = ModuleOperationResult.Failure("provider failed"))
        val supervisor = ModuleSupervisor.fromDeclared(
            API,
            listOf(declaration("vision.router", runtime = broken)),
            RestartPolicy(maxStartAttempts = 3, initialBackoffMillis = 1_000),
        )
        supervisor.startAll(0)

        val module = ModuleCatalogPresenter().present(
            supervisor.snapshot(),
            listOf(presentation("vision.router", "Cyclone Vision")),
            nowEpochMillis = 250,
        ).modules.single()

        assertEquals(CatalogHealthTone.UNAVAILABLE, module.healthTone)
        assertEquals("Cyclone can retry this module in 1 second.", module.restartMessage)
        assertTrue(module.diagnostics.any { it.title == "Module did not start" })
        assertTrue(module.diagnostics.any { it.title == "Retry scheduled" })
        assertFalse(module.diagnostics.any { diagnostic ->
            listOf("DSH", "Cordis", "pnpm", "patch").any { forbidden ->
                forbidden in diagnostic.title || forbidden in diagnostic.explanation
            }
        })
    }

    @Test
    fun metadataFailureFallsBackToSupervisorInventory() {
        val controller = ModuleCatalogController(
            supervisor = supervisor(declaration("vision.router")),
            metadataSource = ModuleCatalogMetadataSource { error("offline") },
            nowEpochMillis = { 0 },
        )

        val state = controller.state()

        assertEquals(1, state.modules.size)
        assertEquals("Vision Router", state.modules.single().displayName)
    }

    @Test
    fun catalogDoesNotExposeAnInstallLifecycleEndpoint() {
        val methodNames = ModuleCatalogController::class.java.methods.map { it.name.lowercase() }

        assertFalse(methodNames.any { "install" in it })
    }

    private fun controller(supervisor: ModuleSupervisor) = ModuleCatalogController(
        supervisor = supervisor,
        metadataSource = BundledModuleCatalogMetadataSource(emptyList()),
        nowEpochMillis = { 0 },
    )

    private fun supervisor(vararg declarations: TrustedModuleDeclaration) =
        ModuleSupervisor.fromDeclared(API, declarations.asList())

    private fun declaration(
        name: String,
        runtime: Runtime = Runtime(),
        origin: TrustedModuleOrigin = TrustedModuleOrigin.COMPILED_IN,
        importance: ModuleImportance = ModuleImportance.OPTIONAL,
        dependencies: List<ModuleDependency> = emptyList(),
        provides: Set<CapabilityId> = emptySet(),
        permissions: Set<CapabilityPermission> = emptySet(),
        restartRequirement: RestartRequirement = RestartRequirement.NONE,
    ) = TrustedModuleDeclaration(
        descriptor = descriptor(
            name,
            dependencies = dependencies,
            provides = provides,
            permissions = permissions,
            restartRequirement = restartRequirement,
        ),
        runtime = runtime,
        origin = origin,
        importance = importance,
    )

    private fun descriptor(
        name: String,
        version: ModuleVersion = ModuleVersion(1, 0, 0),
        dependencies: List<ModuleDependency> = emptyList(),
        provides: Set<CapabilityId> = emptySet(),
        permissions: Set<CapabilityPermission> = emptySet(),
        restartRequirement: RestartRequirement = RestartRequirement.NONE,
    ) = ModuleDescriptor(
        id = id(name),
        version = version,
        compatibleCycloneApi = CycloneApiCompatibility(API, CycloneApiVersion(4, 0)),
        dependencies = dependencies,
        provides = provides,
        permissions = permissions,
        restartRequirement = restartRequirement,
    )

    private fun presentation(
        name: String,
        displayName: String,
        channel: CatalogReleaseChannel = CatalogReleaseChannel.STABLE,
    ) = ModulePresentationMetadata(
        moduleId = id(name),
        displayName = displayName,
        description = "$displayName capabilities for Cyclone.",
        releaseChannel = channel,
    )

    private fun id(name: String) = ModuleId(name)

    private class Runtime(
        private val startResult: ModuleOperationResult = ModuleOperationResult.Success,
    ) : TrustedModuleRuntime {
        override fun start() = startResult
        override fun stop() = ModuleOperationResult.Success
        override fun health() = ModuleHealthReport.healthy()
    }

    companion object {
        val API = CycloneApiVersion(3, 0)
    }
}
