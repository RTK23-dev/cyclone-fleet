package com.cyclone.mobile.platform.capability

import com.cyclone.mobile.platform.module.CycloneApiCompatibility
import com.cyclone.mobile.platform.module.CycloneApiVersion
import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.module.ModuleVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceFirstCapabilityRegistryTest {
    private interface TestCapability {
        fun value(): String
    }

    private val currentApi = CycloneApiVersion(1, 0)
    private val compatibleApi = CycloneApiCompatibility(CycloneApiVersion(1, 0), CycloneApiVersion(2, 0))

    @Test
    fun typedLookupAndDuplicateConflictPreserveFoundationSemantics() {
        val registry = ServiceFirstCapabilityRegistry(currentApi)
        val key = key("page.observe")
        val first = implementation("first")
        registry.declare(declaration("zeta.module", key, first))
        registry.declare(declaration("alpha.module", key, implementation("second")))

        val lookup = registry.lookup(key)

        assertTrue(lookup is CapabilityLookup.Conflict)
        assertEquals(
            listOf("alpha.module", "zeta.module"),
            (lookup as CapabilityLookup.Conflict).providers.map { it.moduleId.value },
        )
        assertTrue(registry.resolve(key) is CapabilityServiceLookup.Unavailable)
        assertEquals(
            listOf("alpha.module", "zeta.module"),
            registry.describe(key.id).map { it.provider.moduleId.value },
        )
    }

    @Test
    fun dependencyCycleIsExplicitAndIndependentOfDeclarationOrder() {
        fun cycles(order: List<String>): Pair<List<List<String>>, List<CapabilityDiagnosticCode>> {
            val registry = ServiceFirstCapabilityRegistry(currentApi)
            val observe = key("page.observe")
            val search = key("page.search")
            val declarations = mapOf(
                "page.observe" to declaration(
                    "observe.module",
                    observe,
                    implementation("observe"),
                    required = listOf(requirement("page.search")),
                ),
                "page.search" to declaration(
                    "search.module",
                    search,
                    implementation("search"),
                    required = listOf(requirement("page.observe")),
                ),
            )
            order.forEach { registry.declare(declarations.getValue(it)) }

            val resolution = registry.resolve(observe)
            assertTrue(resolution is CapabilityServiceLookup.Unavailable)
            return registry.dependencyCycles().map { cycle -> cycle.members.map { it.value } } to
                (resolution as CapabilityServiceLookup.Unavailable).diagnostics.map { it.code }
        }

        val forward = cycles(listOf("page.observe", "page.search"))
        val reverse = cycles(listOf("page.search", "page.observe"))

        assertEquals(listOf(listOf("page.observe", "page.search")), forward.first)
        assertEquals(forward, reverse)
        assertTrue(CapabilityDiagnosticCode.DEPENDENCY_CYCLE in forward.second)
    }

    @Test
    fun missingOptionalIsWarningButUnhealthyRequiredCapabilityBlocksResolution() {
        val registry = ServiceFirstCapabilityRegistry(currentApi)
        val observe = key("page.observe")
        val run = key("automation.run")
        registry.declare(
            declaration(
                "page.module",
                observe,
                implementation("observe"),
                health = { CapabilityHealth(CapabilityHealthState.FAILED, "simulated failure") },
            ),
        )
        registry.declare(
            declaration(
                "automation.module",
                run,
                implementation("run"),
                required = listOf(requirement("page.observe")),
                optional = listOf(requirement("vision.inspect")),
            ),
        )

        val result = registry.resolve(run)

        assertTrue(result is CapabilityServiceLookup.Unavailable)
        val diagnostics = (result as CapabilityServiceLookup.Unavailable).diagnostics
        assertTrue(diagnostics.any {
            it.code == CapabilityDiagnosticCode.DEPENDENCY_UNHEALTHY &&
                it.severity == CapabilityDiagnosticSeverity.BLOCKING
        })
        assertTrue(diagnostics.any {
            it.code == CapabilityDiagnosticCode.OPTIONAL_DEPENDENCY_MISSING &&
                it.severity == CapabilityDiagnosticSeverity.WARNING
        })
    }

    @Test
    fun missingOptionalDependencyDoesNotHideHealthyImplementation() {
        val registry = ServiceFirstCapabilityRegistry(currentApi)
        val key = key("page.observe")
        val implementation = implementation("observe")
        registry.declare(
            declaration(
                "page.module",
                key,
                implementation,
                optional = listOf(requirement("vision.inspect")),
            ),
        )

        val result = registry.resolve(key)

        assertTrue(result is CapabilityServiceLookup.Available)
        result as CapabilityServiceLookup.Available
        assertSame(implementation, result.implementation)
        assertEquals(
            listOf(CapabilityDiagnosticCode.OPTIONAL_DEPENDENCY_MISSING),
            result.warnings.map { it.code },
        )
    }

    @Test
    fun optionalDependencyCycleIsReportedWithoutBlockingCapability() {
        val registry = ServiceFirstCapabilityRegistry(currentApi)
        val observe = key("page.observe")
        val vision = key("vision.inspect")
        registry.declare(
            declaration(
                "page.module",
                observe,
                implementation("observe"),
                optional = listOf(requirement("vision.inspect")),
            ),
        )
        registry.declare(
            declaration(
                "vision.module",
                vision,
                implementation("vision"),
                optional = listOf(requirement("page.observe")),
            ),
        )

        val result = registry.resolve(observe)

        assertTrue(result is CapabilityServiceLookup.Available)
        val warning = (result as CapabilityServiceLookup.Available).warnings.single {
            it.code == CapabilityDiagnosticCode.DEPENDENCY_CYCLE
        }
        assertEquals(CapabilityDiagnosticSeverity.WARNING, warning.severity)
        assertEquals(
            listOf(listOf("page.observe", "vision.inspect")),
            registry.dependencyCycles().map { cycle -> cycle.members.map { it.value } },
        )
    }

    @Test
    fun disabledAndIncompatibleProvidersAreVisibleButCannotResolve() {
        val disabledRegistry = ServiceFirstCapabilityRegistry(currentApi)
        val key = key("vision.inspect")
        disabledRegistry.declare(
            declaration(
                "vision.module",
                key,
                implementation("vision"),
                status = { CapabilityProviderStatus(CapabilityProviderState.DISABLED, "user disabled") },
            ),
        )

        val disabled = disabledRegistry.resolve(key) as CapabilityServiceLookup.Unavailable
        assertTrue(disabled.diagnostics.any { it.code == CapabilityDiagnosticCode.PROVIDER_DISABLED })
        assertEquals(CapabilityInventoryStatus.DISABLED, disabledRegistry.inventory().single().inventoryStatus)

        val incompatibleRegistry = ServiceFirstCapabilityRegistry(currentApi)
        incompatibleRegistry.declare(
            declaration(
                "future.module",
                key,
                implementation("future"),
                compatible = CycloneApiCompatibility(CycloneApiVersion(2, 0), CycloneApiVersion(3, 0)),
            ),
        )
        val incompatible = incompatibleRegistry.resolve(key) as CapabilityServiceLookup.Unavailable
        assertTrue(incompatible.diagnostics.any {
            it.code == CapabilityDiagnosticCode.CYCLONE_API_INCOMPATIBLE
        })
        assertEquals(CapabilityInventoryStatus.INCOMPATIBLE, incompatibleRegistry.inventory().single().inventoryStatus)
    }

    @Test
    fun incompatibleRequiredCapabilityVersionBlocksResolution() {
        val registry = ServiceFirstCapabilityRegistry(currentApi)
        val observe = key("page.observe")
        val run = key("automation.run")
        registry.declare(declaration("page.module", observe, implementation("observe")))
        registry.declare(
            declaration(
                "automation.module",
                run,
                implementation("run"),
                required = listOf(
                    CapabilityVersionRequirement(
                        capabilityId = observe.id,
                        minimumInclusive = CapabilityVersion(2, 0, 0),
                    ),
                ),
            ),
        )

        val result = registry.resolve(run) as CapabilityServiceLookup.Unavailable

        assertTrue(result.diagnostics.any {
            it.code == CapabilityDiagnosticCode.DEPENDENCY_VERSION_INCOMPATIBLE &&
                it.relatedCapabilityId == observe.id
        })
    }

    @Test
    fun failedHealthIsIsolatedAndInventoryOrderingIsStable() {
        val registry = ServiceFirstCapabilityRegistry(currentApi)
        val healthyKey = key("brain.recall")
        val failedKey = key("vision.inspect")
        val healthy = implementation("healthy")
        registry.declare(declaration("zeta.module", failedKey, implementation("failed"), health = { error("boom") }))
        registry.declare(declaration("alpha.module", healthyKey, healthy))

        assertTrue(registry.resolve(failedKey) is CapabilityServiceLookup.Unavailable)
        val available = registry.resolve(healthyKey)
        assertTrue(available is CapabilityServiceLookup.Available)
        assertSame(healthy, (available as CapabilityServiceLookup.Available).implementation)
        assertEquals(
            listOf("brain.recall", "vision.inspect"),
            registry.inventory().map { it.provider.capabilityId.value },
        )
        val dump = registry.agentReadableDump()
        assertTrue(dump.indexOf("`brain.recall`") < dump.indexOf("`vision.inspect`"))
        assertFalse(dump.contains("boom"))
    }

    @Test
    fun capabilityFamiliesExposePolicyMetadataWithoutGrantingAuthority() {
        assertEquals(
            CyclonePolicyCategories.PRIVACY_SENSITIVE,
            CycloneCapabilityFamilies.describe(CycloneCapabilityFamilies.PHONE_TYPE)?.policyCategory,
        )
        assertEquals(
            CapabilityFamily.VISION,
            CycloneCapabilityFamilies.describe(CycloneCapabilityFamilies.VISION_INSPECT)?.family,
        )
        assertEquals(
            CycloneCapabilityFamilies.known.sortedBy { it.id },
            CycloneCapabilityFamilies.known,
        )
    }

    private fun key(id: String) = CapabilityKey(CapabilityId(id), TestCapability::class)

    private fun requirement(id: String) = CapabilityVersionRequirement(CapabilityId(id))

    private fun implementation(value: String) = object : TestCapability {
        override fun value(): String = value
    }

    private fun declaration(
        moduleId: String,
        key: CapabilityKey<TestCapability>,
        implementation: TestCapability,
        required: List<CapabilityVersionRequirement> = emptyList(),
        optional: List<CapabilityVersionRequirement> = emptyList(),
        compatible: CycloneApiCompatibility = compatibleApi,
        status: () -> CapabilityProviderStatus = { CapabilityProviderStatus.enabled() },
        health: () -> CapabilityHealth = { CapabilityHealth.healthy() },
    ) = CapabilityServiceDeclaration(
        provider = CompiledCapabilityAdapter(
            moduleId = ModuleId(moduleId),
            descriptor = CapabilityDescriptor(
                key = key,
                version = CapabilityVersion(1, 0, 0),
                summary = "Test capability ${key.id}",
            ),
            implementation = implementation,
            healthProbe = health,
        ),
        moduleVersion = ModuleVersion(1, 0, 0),
        compatibleCycloneApi = compatible,
        dependencies = CapabilityDependencies(required, optional),
        status = status,
    )
}
