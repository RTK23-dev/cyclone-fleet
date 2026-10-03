package com.cyclone.mobile.platform.modules

import com.cyclone.mobile.platform.capability.CapabilityId
import com.cyclone.mobile.platform.module.CycloneApiVersion
import com.cyclone.mobile.platform.module.ModuleId

internal data class AnalyzedDeclarations(
    val declarations: Map<ModuleId, TrustedModuleDeclaration>,
    val startOrder: List<ModuleId>,
    val moduleDiagnostics: Map<ModuleId, List<ModuleDiagnostic>>,
    val discoveryDiagnostics: List<ModuleDiagnostic>,
)

internal object ModuleDependencyGraph {
    fun analyze(
        declarations: Iterable<TrustedModuleDeclaration>,
        cycloneApiVersion: CycloneApiVersion,
    ): AnalyzedDeclarations {
        val grouped = declarations.groupBy { it.descriptor.id }.toSortedMap()
        val duplicates = grouped.filterValues { it.size > 1 }
        val accepted = grouped
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }
            .toSortedMap()
        val diagnostics = accepted.keys.associateWith { mutableListOf<ModuleDiagnostic>() }.toMutableMap()
        val discoveryDiagnostics = duplicates.map { (id, declarationsForId) ->
            ModuleDiagnostic(
                code = ModuleDiagnosticCode.DUPLICATE_MODULE,
                severity = ModuleDiagnosticSeverity.ERROR,
                message = "Rejected ${declarationsForId.size} declarations for duplicate module $id",
                moduleId = id,
            )
        }

        accepted.forEach { (id, declaration) ->
            val descriptor = declaration.descriptor
            if (!descriptor.compatibleCycloneApi.supports(cycloneApiVersion)) {
                diagnostics.getValue(id) += ModuleDiagnostic(
                    ModuleDiagnosticCode.INCOMPATIBLE_CYCLONE_API,
                    ModuleDiagnosticSeverity.ERROR,
                    "Module $id ${descriptor.version} is incompatible with Cyclone API $cycloneApiVersion",
                    id,
                )
            }
            descriptor.dependencies.sortedBy { it.moduleId }.forEach { dependency ->
                val installed = accepted[dependency.moduleId]
                if (installed == null) {
                    diagnostics.getValue(id) += ModuleDiagnostic(
                        ModuleDiagnosticCode.MISSING_DEPENDENCY,
                        ModuleDiagnosticSeverity.ERROR,
                        "Module $id requires missing module ${dependency.moduleId}",
                        id,
                        listOf(dependency.moduleId),
                    )
                } else if (!dependency.accepts(installed.descriptor.version)) {
                    diagnostics.getValue(id) += ModuleDiagnostic(
                        ModuleDiagnosticCode.INCOMPATIBLE_DEPENDENCY,
                        ModuleDiagnosticSeverity.ERROR,
                        "Module $id does not accept ${dependency.moduleId} ${installed.descriptor.version}",
                        id,
                        listOf(dependency.moduleId),
                    )
                }
            }
            descriptor.optionalDependencies.sortedBy { it.moduleId }.forEach { dependency ->
                val installed = accepted[dependency.moduleId] ?: return@forEach
                if (!dependency.accepts(installed.descriptor.version)) {
                    diagnostics.getValue(id) += ModuleDiagnostic(
                        ModuleDiagnosticCode.INCOMPATIBLE_DEPENDENCY,
                        ModuleDiagnosticSeverity.WARNING,
                        "Optional module ${dependency.moduleId} ${installed.descriptor.version} is incompatible with $id",
                        id,
                        listOf(dependency.moduleId),
                    )
                }
            }
        }

        providerConflicts(accepted).forEach { (capability, providers) ->
            providers.forEach { provider ->
                diagnostics.getValue(provider) += ModuleDiagnostic(
                    ModuleDiagnosticCode.DUPLICATE_PROVIDER,
                    ModuleDiagnosticSeverity.ERROR,
                    "Capability $capability has conflicting providers: ${providers.joinToString()}",
                    provider,
                    providers,
                )
            }
        }

        stronglyConnectedComponents(accepted)
            .filter { it.size > 1 }
            .forEach { component ->
                component.forEach { moduleId ->
                    diagnostics.getValue(moduleId) += ModuleDiagnostic(
                        ModuleDiagnosticCode.DEPENDENCY_CYCLE,
                        ModuleDiagnosticSeverity.ERROR,
                        "Required dependency cycle: ${component.joinToString(" -> ")}",
                        moduleId,
                        component,
                    )
                }
            }

        propagateUnavailableDependencies(accepted, diagnostics)
        val validIds = accepted.keys.filter { id -> diagnostics.getValue(id).none { it.severity == ModuleDiagnosticSeverity.ERROR } }.toSet()
        val order = topologicalOrder(accepted, validIds)
        return AnalyzedDeclarations(
            declarations = accepted,
            startOrder = order,
            moduleDiagnostics = diagnostics.mapValues { sortDiagnostics(it.value) },
            discoveryDiagnostics = sortDiagnostics(discoveryDiagnostics),
        )
    }

    private fun providerConflicts(
        declarations: Map<ModuleId, TrustedModuleDeclaration>,
    ): Map<CapabilityId, List<ModuleId>> = declarations.values
        .flatMap { declaration -> declaration.descriptor.provides.map { it to declaration.descriptor.id } }
        .groupBy({ it.first }, { it.second })
        .mapValues { it.value.distinct().sorted() }
        .filterValues { it.size > 1 }
        .toSortedMap()

    private fun stronglyConnectedComponents(
        declarations: Map<ModuleId, TrustedModuleDeclaration>,
    ): List<List<ModuleId>> {
        var index = 0
        val indices = mutableMapOf<ModuleId, Int>()
        val lowLinks = mutableMapOf<ModuleId, Int>()
        val stack = ArrayDeque<ModuleId>()
        val onStack = mutableSetOf<ModuleId>()
        val components = mutableListOf<List<ModuleId>>()

        fun visit(moduleId: ModuleId) {
            indices[moduleId] = index
            lowLinks[moduleId] = index
            index += 1
            stack.addLast(moduleId)
            onStack += moduleId

            declarations.getValue(moduleId).descriptor.dependencies
                .map { it.moduleId }
                .filter { it in declarations }
                .sorted()
                .forEach { dependency ->
                    if (dependency !in indices) {
                        visit(dependency)
                        lowLinks[moduleId] = minOf(lowLinks.getValue(moduleId), lowLinks.getValue(dependency))
                    } else if (dependency in onStack) {
                        lowLinks[moduleId] = minOf(lowLinks.getValue(moduleId), indices.getValue(dependency))
                    }
                }

            if (lowLinks.getValue(moduleId) == indices.getValue(moduleId)) {
                val component = mutableListOf<ModuleId>()
                do {
                    val member = stack.removeLast()
                    onStack -= member
                    component += member
                } while (member != moduleId)
                components += component.sorted()
            }
        }

        declarations.keys.sorted().forEach { if (it !in indices) visit(it) }
        return components.sortedBy { it.first() }
    }

    private fun propagateUnavailableDependencies(
        declarations: Map<ModuleId, TrustedModuleDeclaration>,
        diagnostics: MutableMap<ModuleId, MutableList<ModuleDiagnostic>>,
    ) {
        var changed: Boolean
        do {
            changed = false
            declarations.forEach { (id, declaration) ->
                if (diagnostics.getValue(id).any { it.severity == ModuleDiagnosticSeverity.ERROR }) return@forEach
                val unavailable = declaration.descriptor.dependencies
                    .map { it.moduleId }
                    .filter { dependency ->
                        dependency in declarations && diagnostics.getValue(dependency).any {
                            it.severity == ModuleDiagnosticSeverity.ERROR
                        }
                    }
                    .sorted()
                if (unavailable.isNotEmpty()) {
                    diagnostics.getValue(id) += ModuleDiagnostic(
                        ModuleDiagnosticCode.DEPENDENCY_UNAVAILABLE,
                        ModuleDiagnosticSeverity.ERROR,
                        "Module $id depends on unavailable modules: ${unavailable.joinToString()}",
                        id,
                        unavailable,
                    )
                    changed = true
                }
            }
        } while (changed)
    }

    private fun topologicalOrder(
        declarations: Map<ModuleId, TrustedModuleDeclaration>,
        included: Set<ModuleId>,
    ): List<ModuleId> {
        val remainingDependencies = included.associateWith { id ->
            declarations.getValue(id).descriptor.dependencies.map { it.moduleId }.filter { it in included }.toMutableSet()
        }.toMutableMap()
        val order = mutableListOf<ModuleId>()
        while (remainingDependencies.isNotEmpty()) {
            val ready = remainingDependencies.filterValues { it.isEmpty() }.keys.sorted()
            check(ready.isNotEmpty()) { "Validated module graph unexpectedly contains a cycle" }
            ready.forEach { id ->
                order += id
                remainingDependencies.remove(id)
                remainingDependencies.values.forEach { it.remove(id) }
            }
        }
        return order
    }
}

internal fun sortDiagnostics(diagnostics: Iterable<ModuleDiagnostic>): List<ModuleDiagnostic> = diagnostics.sortedWith(
    compareBy<ModuleDiagnostic>(
        { it.moduleId?.value ?: "" },
        { it.code.name },
        { it.relatedModuleIds.joinToString { id -> id.value } },
        { it.message },
    ),
)
