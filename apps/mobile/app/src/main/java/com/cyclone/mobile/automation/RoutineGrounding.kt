package com.cyclone.mobile.automation

/**
 * Whether a routine runs on the app's map (plan 23). A routine whose phone work is saved skills is grounded: each skill
 * walks the map and the Mind finishes the goal, with the same checks as an Ask. A routine of scripted taps (fixed
 * controls or coordinates) is not grounded: it keeps working as before but does not follow the app when it changes.
 */
enum class RoutineGround(val label: String) {
    GROUNDED("Grounded on the map"),
    SCRIPTED("Scripted taps · not grounded"),
    NO_PHONE("No phone steps"),
}

object RoutineGrounding {
    private val SCRIPTED = setOf(StepType.PHONE_TOOL, StepType.INVOKE_SKILL, StepType.STOCK_SKILL)

    fun of(routine: AutomationDefinition): RoutineGround = when {
        routine.steps.any { it.type in SCRIPTED } -> RoutineGround.SCRIPTED
        routine.steps.any { it.type == StepType.RUN_GROUNDED_SKILL } -> RoutineGround.GROUNDED
        else -> RoutineGround.NO_PHONE
    }

    /** The step that runs a saved skill. */
    fun skillStep(id: String, skillId: String, name: String = "Run skill"): StepDefinition =
        StepDefinition(id = id, name = name, type = StepType.RUN_GROUNDED_SKILL, parameters = mapOf("skillId" to skillId))
}
