package com.cyclone.mobile.automation

/** Conservative legacy migration: only explicit package parameters, never names or free text. */
object RoutineAssociations {
    fun infer(routine: AutomationDefinition): List<String> {
        val packageKeys = setOf("package", "packageName", "package_name", "appPackage")
        val parameters = buildList {
            if (routine.trigger.type == TriggerType.APP_OPENED) add(routine.trigger.parameters)
            routine.steps.filter { it.type == StepType.PHONE_TOOL }.forEach { add(it.parameters) }
        }
        return parameters.flatMap { params -> params.filterKeys { it in packageKeys }.values }
            .filter { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) }
            .distinct().sorted()
    }
}
