package com.cyclone.mobile.platform.module

import com.cyclone.mobile.platform.capability.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleContractsTest {
    @Test
    fun descriptorCapturesCompatibilityDependenciesAndPersistence() {
        val descriptor = ModuleDescriptor(
            id = ModuleId("vision.router"),
            version = ModuleVersion(1, 2, 0),
            compatibleCycloneApi = CycloneApiCompatibility(
                minimumInclusive = CycloneApiVersion(3, 0),
                maximumExclusive = CycloneApiVersion(4, 0),
            ),
            provides = setOf(CapabilityId("vision.inspect")),
            consumes = setOf(CapabilityId("page.observe")),
            dependencies = listOf(ModuleDependency(ModuleId("page.awareness"), ModuleVersion(1, 0, 0))),
            optionalDependencies = listOf(ModuleDependency(ModuleId("gateway.vision"))),
            healthProbes = listOf(HealthProbeDescriptor("provider-availability", 1_000)),
            persistentSchemas = listOf(PersistentSchemaDescriptor("vision-attempts", 1, containsSensitiveData = true)),
            restartRequirement = RestartRequirement.MODULE,
            migrationVersion = 1,
        )

        assertTrue(descriptor.compatibleCycloneApi.supports(CycloneApiVersion(3, 5)))
        assertFalse(descriptor.compatibleCycloneApi.supports(CycloneApiVersion(4, 0)))
        assertEquals(setOf(CapabilityId("vision.inspect")), descriptor.provides)
        assertTrue(descriptor.persistentSchemas.single().containsSensitiveData)
    }

    @Test(expected = IllegalArgumentException::class)
    fun descriptorRejectsDependencyThatIsBothRequiredAndOptional() {
        ModuleDescriptor(
            id = ModuleId("vision.router"),
            version = ModuleVersion(1, 0, 0),
            compatibleCycloneApi = CycloneApiCompatibility(CycloneApiVersion(3, 0), CycloneApiVersion(4, 0)),
            dependencies = listOf(ModuleDependency(ModuleId("page.awareness"))),
            optionalDependencies = listOf(ModuleDependency(ModuleId("page.awareness"))),
        )
    }
}
