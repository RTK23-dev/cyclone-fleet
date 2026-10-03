package com.cyclone.mobile.platform.capability

import com.cyclone.mobile.platform.module.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRegistryContractTest {
    private interface ObserveCapability {
        fun label(): String
    }

    private interface RecallCapability {
        fun count(): Int
    }

    private class FakeProvider<T : Any>(
        override val moduleId: ModuleId,
        override val descriptor: CapabilityDescriptor<T>,
        override val implementation: T,
        private val healthProbe: () -> CapabilityHealth = { CapabilityHealth.healthy() },
    ) : CapabilityProvider<T> {
        override fun health(): CapabilityHealth = healthProbe()
    }

    private val observeKey = CapabilityKey(CapabilityId("page.observe"), ObserveCapability::class)
    private val recallKey = CapabilityKey(CapabilityId("brain.recall"), RecallCapability::class)

    @Test
    fun twoFakeModulesRegisterAndResolveSeparateTypedCapabilities() {
        val registry = InMemoryCapabilityRegistry()
        val observer = object : ObserveCapability {
            override fun label() = "page"
        }
        val recall = object : RecallCapability {
            override fun count() = 2
        }

        registry.register(provider(ModuleId("page.module"), observeKey, observer))
        registry.register(provider(ModuleId("brain.module"), recallKey, recall))

        val observed = registry.lookup(observeKey)
        val recalled = registry.lookup(recallKey)
        assertTrue(observed is CapabilityLookup.Available)
        assertTrue(recalled is CapabilityLookup.Available)
        assertSame(observer, (observed as CapabilityLookup.Available).implementation)
        assertSame(recall, (recalled as CapabilityLookup.Available).implementation)
        assertEquals(listOf("brain.recall", "page.observe"), registry.snapshot().map { it.provider.capabilityId.value })
    }

    @Test
    fun duplicateProviderConflictIsIndependentOfRegistrationOrder() {
        fun conflict(order: List<ModuleId>): List<String> {
            val registry = InMemoryCapabilityRegistry()
            order.forEach { moduleId ->
                registry.register(
                    provider(
                        moduleId,
                        observeKey,
                        object : ObserveCapability {
                            override fun label() = moduleId.value
                        },
                    ),
                )
            }
            val result = registry.lookup(observeKey)
            assertTrue(result is CapabilityLookup.Conflict)
            return (result as CapabilityLookup.Conflict).providers.map { it.moduleId.value }
        }

        val forward = conflict(listOf(ModuleId("zeta.module"), ModuleId("alpha.module")))
        val reverse = conflict(listOf(ModuleId("alpha.module"), ModuleId("zeta.module")))

        assertEquals(listOf("alpha.module", "zeta.module"), forward)
        assertEquals(forward, reverse)
    }

    @Test
    fun failedHealthProbeDoesNotCrashOrHideUnrelatedModule() {
        val registry = InMemoryCapabilityRegistry()
        val failed = provider(
            ModuleId("broken.module"),
            observeKey,
            object : ObserveCapability {
                override fun label() = "broken"
            },
            healthProbe = { error("probe exploded") },
        )
        val healthyImplementation = object : RecallCapability {
            override fun count() = 7
        }
        registry.register(failed)
        registry.register(provider(ModuleId("healthy.module"), recallKey, healthyImplementation))

        val failedLookup = registry.lookup(observeKey)
        val healthyLookup = registry.lookup(recallKey)

        assertTrue(failedLookup is CapabilityLookup.Unhealthy)
        assertEquals(CapabilityHealthState.FAILED, (failedLookup as CapabilityLookup.Unhealthy).health.state)
        assertTrue(healthyLookup is CapabilityLookup.Available)
        assertSame(healthyImplementation, (healthyLookup as CapabilityLookup.Available).implementation)
    }

    @Test
    fun lookupWithWrongContractReturnsTypedDiagnostic() {
        val registry = InMemoryCapabilityRegistry()
        registry.register(
            provider(
                ModuleId("page.module"),
                observeKey,
                object : ObserveCapability {
                    override fun label() = "page"
                },
            ),
        )

        val wrongKey = CapabilityKey(CapabilityId("page.observe"), RecallCapability::class)
        val result = registry.lookup(wrongKey)

        assertTrue(result is CapabilityLookup.TypeMismatch)
        assertEquals(RecallCapability::class.qualifiedName, (result as CapabilityLookup.TypeMismatch).requestedContract)
    }

    private fun <T : Any> provider(
        moduleId: ModuleId,
        key: CapabilityKey<T>,
        implementation: T,
        healthProbe: () -> CapabilityHealth = { CapabilityHealth.healthy() },
    ) = FakeProvider(
        moduleId = moduleId,
        descriptor = CapabilityDescriptor(
            key = key,
            version = CapabilityVersion(1, 0, 0),
            summary = "Contract fixture for ${key.id}",
        ),
        implementation = implementation,
        healthProbe = healthProbe,
    )
}
