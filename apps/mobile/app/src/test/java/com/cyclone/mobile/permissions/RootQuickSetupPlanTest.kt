package com.cyclone.mobile.permissions

import com.cyclone.mobile.runtime.workspaces.ProfileBootstrapContract
import org.junit.Assert.*
import org.junit.Test

class RootQuickSetupPlanTest {
    @Test fun preservesOtherAccessibilityServicesAndDoesNotDuplicateCyclone() {
        val existing = "other.app/.Reader:${ProfileBootstrapContract.ACCESSIBILITY}"
        val command = RootQuickSetupPlan.commands(10, existing).single { "enabled_accessibility_services" in it }
        assertEquals(existing, command.last())
    }
    @Test fun allUserScopedGrantsUseExactUserAndOnlyCyclonePackage() {
        val commands = RootQuickSetupPlan.commands(12, "null")
        commands.filter { "--user" in it }.forEach { assertEquals("12", it[it.indexOf("--user") + 1]) }
        commands.filter { it.first() == "pm" }.forEach {
            assertEquals(ProfileBootstrapContract.PACKAGE, it[4])
            assertTrue(it.last() in ProfileBootstrapContract.permissions)
        }
        assertFalse(commands.flatten().any { it.contains("magisk") || it.contains("PROJECT_MEDIA") })
        assertFalse(commands.any { it.take(2) == listOf("ime", "set") })
    }
    @Test fun repairRebindsOnlyCycloneAndPreservesOtherServices() {
        val commands = RootQuickSetupPlan.commands(0, "other.app/.Reader:${ProfileBootstrapContract.ACCESSIBILITY}", true)
            .filter { "enabled_accessibility_services" in it }
        assertEquals("other.app/.Reader", commands.first().last())
        assertTrue(commands.last().last().contains(ProfileBootstrapContract.ACCESSIBILITY))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidUserRejected() {
        RootQuickSetupPlan.commands(-1, "")
    }
}
