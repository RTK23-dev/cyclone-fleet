package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.skill.SkillDraftListing

/**
 * Routines / Automations listing model used by the Feature Pages surface.
 *
 * Draft skill capsules already in [com.cyclone.mobile.automation.AutomationStore] appear as
 * disabled rows. No visual redesign of routine cards.
 */
fun v32RoutinesAutomationsListing(automations: List<AutomationDefinition>): List<AutomationDefinition> =
    SkillDraftListing.forRoutinesAutomations(automations)

fun v32IsDraftSkillOnAutomations(automation: AutomationDefinition): Boolean =
    SkillDraftListing.isDraftSkill(automation)
