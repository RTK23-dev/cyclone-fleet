package com.cyclone.mobile.automation.skill

import com.cyclone.mobile.automation.AutomationDefinition

/**
 * Reads the cyclone-skill-capsule-v4 description marker already written by [SkillCompiler].
 *
 * Verified is a description marker (or equivalent), not a PC promotion path.
 * Capsules stay disabled in AutomationStore.
 */
object SkillStatusMarker {
    private val statusPattern = Regex("""(?:^|\s)status=([a-zA-Z_-]+)""")
    private val appPattern = Regex("""(?:^|\s)app=(\S+)""")
    private val pageKeyPattern = Regex("""(?:^|\s)pageKey=(\S+)""")

    fun isSkill(automation: AutomationDefinition): Boolean =
        automation.id.startsWith(SkillCompiler.ID_PREFIX) ||
            automation.description.contains(SkillCompiler.DESCRIPTION_MARKER)

    fun statusOf(automation: AutomationDefinition): String {
        val raw = statusPattern.find(automation.description)?.groupValues?.getOrNull(1)
            ?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        return raw.ifBlank { SkillCapsuleStatus.DRAFT.name.lowercase() }
    }

    fun isVerified(automation: AutomationDefinition): Boolean =
        isSkill(automation) && statusOf(automation) == SkillCapsuleStatus.VERIFIED.name.lowercase()

    fun isDraft(automation: AutomationDefinition): Boolean =
        isSkill(automation) && !isVerified(automation)

    fun appOf(automation: AutomationDefinition): String =
        appPattern.find(automation.description)?.groupValues?.getOrNull(1)
            ?.trim()?.trimEnd('.').orEmpty()

    fun pageKeyOf(automation: AutomationDefinition): String =
        pageKeyPattern.find(automation.description)?.groupValues?.getOrNull(1)
            ?.trim()?.trimEnd('.').orEmpty()

    fun goalOf(automation: AutomationDefinition): String {
        val prefix = "Draft skill · "
        return if (automation.name.startsWith(prefix)) automation.name.removePrefix(prefix) else automation.name
    }

    fun matchesVerified(automation: AutomationDefinition, goal: String, pageKey: String): Boolean {
        if (!isVerified(automation)) return false
        val wantGoal = goal.trim()
        val wantPage = pageKey.trim()
        if (wantGoal.isBlank() && wantPage.isBlank()) return false
        if (wantGoal.isNotBlank()) {
            val have = goalOf(automation).lowercase()
            val g = wantGoal.lowercase()
            if (have != g && g !in have && have !in g) return false
        }
        if (wantPage.isNotBlank()) {
            val havePage = pageKeyOf(automation)
            if (havePage.isNotBlank() && havePage != wantPage) return false
        }
        return true
    }
}
