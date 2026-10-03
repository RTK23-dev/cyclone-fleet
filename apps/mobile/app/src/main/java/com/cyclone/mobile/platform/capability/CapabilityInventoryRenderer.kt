package com.cyclone.mobile.platform.capability

internal fun renderCapabilityInventory(
    actual: List<CapabilityInventoryEntry>,
    cycles: List<CapabilityDependencyCycle>,
): String {
    val registeredIds = actual.map { it.provider.capabilityId }.toSet()
    val lines = mutableListOf("# Cyclone capability inventory", "")
    if (actual.isEmpty()) {
        lines += "No providers are registered."
    } else {
        actual.forEach { item ->
            lines += "- `${item.provider.capabilityId}` — ${item.inventoryStatus.name.lowercase()}"
            lines += "  - provider: `${item.provider.moduleId}` @ `${item.provider.capabilityVersion}`"
            lines += "  - contract: `${item.contract}`"
            lines += "  - policy: `${item.policyCategory}`"
            lines += "  - health: `${item.health.state.name.lowercase()}`"
            lines += "  - requires: ${item.requiredDependencies.renderRequirements()}"
            lines += "  - optional: ${item.optionalDependencies.renderRequirements()}"
            lines += "  - permissions: ${item.permissions.renderPermissions()}"
        }
    }

    val missingKnown = CycloneCapabilityFamilies.known.filter { it.id !in registeredIds }
    if (missingKnown.isNotEmpty()) {
        lines += ""
        lines += "## Known but not registered"
        lines += ""
        missingKnown.forEach { known ->
            lines += "- `${known.id}` — ${known.family.name.lowercase()}, policy `${known.policyCategory}`"
        }
    }
    if (cycles.isNotEmpty()) {
        lines += ""
        lines += "## Dependency diagnostics"
        lines += ""
        cycles.forEach { cycle ->
            lines += "- cycle: " + cycle.members.joinToString(" -> ") { "`$it`" }
        }
    }
    return lines.joinToString("\n") + "\n"
}

private fun List<CapabilityVersionRequirement>.renderRequirements(): String =
    if (isEmpty()) "none" else joinToString { "`${it.capabilityId}` (${it.displayRange()})" }

private fun List<CapabilityPermission>.renderPermissions(): String =
    if (isEmpty()) "none" else joinToString { permission ->
        "`${permission.id}` (${if (permission.required) "required" else "optional"})"
    }
