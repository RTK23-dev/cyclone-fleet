package com.cyclone.mobile.infrastructure.v31

import com.cyclone.mobile.brain.memory.providers.LocalMemoryLoadState
import com.cyclone.mobile.brain.memory.providers.LocalTieredMemoryProvider
import com.cyclone.mobile.platform.capability.CapabilityDescriptor
import com.cyclone.mobile.platform.capability.CapabilityHealth
import com.cyclone.mobile.platform.capability.CapabilityHealthState
import com.cyclone.mobile.platform.capability.CapabilityId
import com.cyclone.mobile.platform.capability.CapabilityKey
import com.cyclone.mobile.platform.capability.CapabilityProviderState
import com.cyclone.mobile.platform.capability.CapabilityProviderStatus
import com.cyclone.mobile.platform.capability.CapabilityRegistration
import com.cyclone.mobile.platform.capability.CapabilityServiceDeclaration
import com.cyclone.mobile.platform.capability.CapabilityVersion
import com.cyclone.mobile.platform.capability.CompiledCapabilityAdapter
import com.cyclone.mobile.platform.capability.CyclonePolicyCategories
import com.cyclone.mobile.platform.capability.ServiceFirstCapabilityRegistry
import com.cyclone.mobile.platform.module.CycloneApiCompatibility
import com.cyclone.mobile.platform.module.CycloneApiVersion
import com.cyclone.mobile.platform.module.ModuleDependency
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

/** Non-invokable metadata binding. Execution always lives behind the owning runtime contract. */
data class V31CapabilityHandle(
    val id: CapabilityId,
    val moduleId: ModuleId,
)

data class V31CapabilityBootstrap(
    val registry: ServiceFirstCapabilityRegistry,
    val failures: List<String>,
)

internal object V31Bootstrap {
    val CYCLONE_API = CycloneApiVersion(3, 1)
    val API_COMPATIBILITY = CycloneApiCompatibility(CycloneApiVersion(3, 0), CycloneApiVersion(4, 0))
    val MODULE_VERSION = ModuleVersion(3, 1, 0)
    val CAPABILITY_VERSION = CapabilityVersion(1, 0, 0)

    val CORE_PHONE = ModuleId("core.phone")
    val CORE_PAGE = ModuleId("core.page-awareness")
    val CORE_POLICY = ModuleId("core.policy")
    val CORE_MEMORY = ModuleId("core.memory")
    val CORE_GRAPH = ModuleId("core.app-graph")
    val CORE_AUTOMATION = ModuleId("core.automation")
    val CORE_AI = ModuleId("core.ai")
    val CORE_VISION = ModuleId("core.vision")
    val CORE_GATEWAY = ModuleId("core.gateway")
    val CORE_RECOVERY = ModuleId("core.recovery")

    val CRITICAL_MODULES: Set<ModuleId> = setOf(CORE_PHONE, CORE_PAGE, CORE_POLICY, CORE_RECOVERY)

    private data class CapabilitySpec(
        val id: String,
        val moduleId: ModuleId,
        val summary: String,
        val privacySensitive: Boolean = false,
    )

    private val capabilitySpecs = listOf(
        CapabilitySpec("workspace.list", CORE_PHONE, "Layer 2 workspace operation through the canonical executor"),
        CapabilitySpec("workspace.register", CORE_PHONE, "Layer 2 workspace operation through the canonical executor"),
        CapabilitySpec("workspace.switch", CORE_PHONE, "Layer 2 workspace operation through the canonical executor"),
        CapabilitySpec("phone.workspace_switch", CORE_PHONE, "Layer 2 workspace operation through the canonical executor"),
        CapabilitySpec("workspace.pause", CORE_PHONE, "Layer 2 workspace operation through the canonical executor"),
        CapabilitySpec("workspace.release", CORE_PHONE, "Layer 2 workspace operation through the canonical executor"),
        CapabilitySpec("workspace.arm", CORE_PHONE, "Layer 2 workspace operation through the canonical executor"),
        CapabilitySpec("workspace.next", CORE_PHONE, "Layer 2 workspace operation through the canonical executor"),

        CapabilitySpec("phone.observe", CORE_PHONE, "Observe current Android accessibility state"),
        CapabilitySpec("phone.find", CORE_PHONE, "Find a semantic target in the current observation"),
        CapabilitySpec("phone.click", CORE_PHONE, "Request a canonical semantic click"),
        CapabilitySpec("phone.long_press", CORE_PHONE, "Request a canonical semantic long press"),
        CapabilitySpec("phone.swipe", CORE_PHONE, "Request a canonical bounded swipe"),
        CapabilitySpec("phone.scroll", CORE_PHONE, "Request canonical semantic scrolling"),
        CapabilitySpec("phone.type", CORE_PHONE, "Request redacted text entry through the canonical executor", true),
        CapabilitySpec("phone.back", CORE_PHONE, "Request canonical Android back navigation"),
        CapabilitySpec("phone.home", CORE_PHONE, "Request canonical Android home navigation"),
        CapabilitySpec("phone.open_app", CORE_PHONE, "Request opening an installed application"),
        CapabilitySpec("phone.wait_for", CORE_PHONE, "Wait for a bounded semantic condition"),
        CapabilitySpec("page.observe", CORE_PAGE, "Read compact semantic page evidence"),
        CapabilitySpec("page.identify", CORE_PAGE, "Identify the current semantic page"),
        CapabilitySpec("page.search", CORE_PAGE, "Search normalized controls on the current page"),
        CapabilitySpec("brain.recall", CORE_GRAPH, "Recall scoped verified Cyclone knowledge"),
        CapabilitySpec("brain.store", CORE_MEMORY, "Propose durable knowledge through CycloneMemoryService", true),
        CapabilitySpec("automation.list", CORE_AUTOMATION, "List bound Cyclone routines"),
        CapabilitySpec("automation.run", CORE_AUTOMATION, "Request a governed Cyclone routine run"),
        CapabilitySpec("vision.inspect", CORE_VISION, "Inspect vision evidence only as semantic fallback", true),
        CapabilitySpec("gateway.status", CORE_GATEWAY, "Read bounded local gateway health"),
    )

    val REQUIRED_CAPABILITY_IDS: List<String> = capabilitySpecs.map { it.id }.sorted()

    fun createModuleSupervisor(
        bindings: V31RuntimeBindings,
        accessibilityReady: () -> Boolean,
        memoryProvider: LocalTieredMemoryProvider,
    ): ModuleSupervisor {
        val declarations = listOf(
            declaration(
                CORE_PHONE,
                critical = true,
                runtime = coreRuntime {
                    if (accessibilityReady()) ModuleHealthReport.healthy()
                    else ModuleHealthReport.degraded("accessibility-not-connected")
                },
            ),
            declaration(
                CORE_PAGE,
                critical = true,
                dependencies = listOf(CORE_PHONE),
                runtime = bindings.runtime(V31ExternalModule.PAGE_AWARENESS),
            ),
            declaration(CORE_POLICY, critical = true, runtime = coreRuntime()),
            declaration(
                CORE_MEMORY,
                runtime = coreRuntime {
                    when (memoryProvider.diagnostics().loadState) {
                        LocalMemoryLoadState.CORRUPT -> ModuleHealthReport.failed("memory-store-corrupt")
                        LocalMemoryLoadState.EMPTY,
                        LocalMemoryLoadState.READY,
                        -> ModuleHealthReport.healthy()
                    }
                },
            ),
            declaration(
                CORE_GRAPH,
                dependencies = listOf(CORE_PAGE),
                optionalDependencies = listOf(CORE_MEMORY),
                runtime = bindings.runtime(V31ExternalModule.APP_GRAPH),
            ),
            declaration(
                CORE_AUTOMATION,
                dependencies = listOf(CORE_PHONE, CORE_PAGE),
                optionalDependencies = listOf(CORE_GRAPH),
                runtime = bindings.runtime(V31ExternalModule.AUTOMATION),
            ),
            declaration(
                CORE_AI,
                dependencies = listOf(CORE_PHONE, CORE_PAGE),
                optionalDependencies = listOf(CORE_GRAPH, CORE_AUTOMATION, CORE_VISION),
                runtime = bindings.runtime(V31ExternalModule.AI),
            ),
            declaration(
                CORE_VISION,
                dependencies = listOf(CORE_PAGE),
                runtime = bindings.runtime(V31ExternalModule.VISION),
            ),
            declaration(
                CORE_GATEWAY,
                dependencies = listOf(CORE_PHONE, CORE_PAGE),
                runtime = bindings.runtime(V31ExternalModule.GATEWAY),
            ),
            declaration(CORE_RECOVERY, critical = true, runtime = coreRuntime()),
        )
        return ModuleSupervisor.fromDeclared(CYCLONE_API, declarations)
    }

    fun registerCapabilities(supervisor: ModuleSupervisor): V31CapabilityBootstrap {
        val registry = ServiceFirstCapabilityRegistry(CYCLONE_API)
        val failures = mutableListOf<String>()
        capabilitySpecs.sortedBy { it.id }.forEach { spec ->
            val id = CapabilityId(spec.id)
            val handle = V31CapabilityHandle(id, spec.moduleId)
            val provider = CompiledCapabilityAdapter(
                moduleId = spec.moduleId,
                descriptor = CapabilityDescriptor(
                    key = CapabilityKey(id, V31CapabilityHandle::class),
                    version = CAPABILITY_VERSION,
                    summary = spec.summary,
                ),
                implementation = handle,
                healthProbe = { capabilityHealth(supervisor, spec.moduleId) },
            )
            val registration = registry.declare(
                CapabilityServiceDeclaration(
                    provider = provider,
                    moduleVersion = MODULE_VERSION,
                    compatibleCycloneApi = API_COMPATIBILITY,
                    policyCategory = if (spec.privacySensitive) {
                        CyclonePolicyCategories.PRIVACY_SENSITIVE
                    } else {
                        CyclonePolicyCategories.ROUTINE
                    },
                    status = { providerStatus(supervisor, spec.moduleId) },
                ),
            )
            if (registration !is CapabilityRegistration.Registered &&
                registration !is CapabilityRegistration.AlreadyRegistered
            ) {
                failures += "CAPABILITY_${spec.id.uppercase().replace('.', '_').replace('-', '_')}_REGISTRATION_FAILED"
            }
        }
        return V31CapabilityBootstrap(registry, failures.distinct().sorted())
    }

    private fun declaration(
        moduleId: ModuleId,
        critical: Boolean = false,
        dependencies: List<ModuleId> = emptyList(),
        optionalDependencies: List<ModuleId> = emptyList(),
        runtime: TrustedModuleRuntime,
    ) = TrustedModuleDeclaration(
        descriptor = ModuleDescriptor(
            id = moduleId,
            version = MODULE_VERSION,
            compatibleCycloneApi = API_COMPATIBILITY,
            provides = capabilitySpecs.filter { it.moduleId == moduleId }.map { CapabilityId(it.id) }.toSet(),
            dependencies = dependencies.sorted().map(::ModuleDependency),
            optionalDependencies = optionalDependencies.sorted().map(::ModuleDependency),
        ),
        runtime = runtime,
        importance = if (critical) ModuleImportance.CRITICAL_BUILT_IN else ModuleImportance.OPTIONAL,
    )

    private fun coreRuntime(
        healthProbe: () -> ModuleHealthReport = { ModuleHealthReport.healthy() },
    ): TrustedModuleRuntime = object : TrustedModuleRuntime {
        override fun start(): ModuleOperationResult = ModuleOperationResult.Success
        override fun stop(): ModuleOperationResult = ModuleOperationResult.Success
        override fun health(): ModuleHealthReport = try {
            healthProbe()
        } catch (_: Exception) {
            ModuleHealthReport.failed("core-health-probe-failed")
        }
    }

    private fun capabilityHealth(supervisor: ModuleSupervisor, moduleId: ModuleId): CapabilityHealth =
        when (supervisor.status(moduleId)?.state) {
            ModuleState.READY -> CapabilityHealth.healthy()
            ModuleState.DEGRADED -> CapabilityHealth(CapabilityHealthState.DEGRADED, "module-degraded")
            ModuleState.FAILED,
            ModuleState.QUARANTINED,
            -> CapabilityHealth(CapabilityHealthState.FAILED, "module-failed")
            ModuleState.DISABLED,
            ModuleState.UPDATE_PENDING,
            ModuleState.INSTALLED,
            ModuleState.STARTING,
            null,
            -> CapabilityHealth(CapabilityHealthState.UNAVAILABLE, "module-not-ready")
        }

    private fun providerStatus(supervisor: ModuleSupervisor, moduleId: ModuleId): CapabilityProviderStatus {
        val status = supervisor.status(moduleId)
        return when {
            status == null -> CapabilityProviderStatus(CapabilityProviderState.QUARANTINED, "module-missing")
            status.state == ModuleState.QUARANTINED ->
                CapabilityProviderStatus(CapabilityProviderState.QUARANTINED, "module-quarantined")
            !status.enabled || status.state == ModuleState.DISABLED ->
                CapabilityProviderStatus(CapabilityProviderState.DISABLED, "module-disabled")
            else -> CapabilityProviderStatus.enabled()
        }
    }
}
