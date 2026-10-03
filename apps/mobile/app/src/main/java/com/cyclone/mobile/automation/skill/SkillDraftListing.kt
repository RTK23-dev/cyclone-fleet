package com.cyclone.mobile.automation.skill

import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.TriggerType

/**
 * Routines / Automations listing model for draft skill capsules already in AutomationStore.
 *
 * Does not invent a second store. Disabled capsules stay disabled.
 */
object SkillDraftListing {
    fun isDraftSkill(automation: AutomationDefinition): Boolean =
        !automation.enabled && (
            automation.id.startsWith(SkillCompiler.ID_PREFIX) ||
                automation.description.contains(SkillCompiler.DESCRIPTION_MARKER)
        )

    /**
     * Items the Routines/Automations surface should show: existing Automations-tab rows
     * plus disabled draft skills (even if they were compiled with a manual trigger).
     */
    fun forRoutinesAutomations(automations: List<AutomationDefinition>): List<AutomationDefinition> {
        val drafts = automations.filter(::isDraftSkill)
        val automationsTab = automations.filter { it.trigger.type != TriggerType.MANUAL }
        return (drafts + automationsTab).distinctBy { it.id }
    }
}
