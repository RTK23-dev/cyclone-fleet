package com.cyclone.mobile.automation.skill

import com.cyclone.mobile.automation.AutomationStore
import com.cyclone.mobile.policy.PcGateEnvelope

enum class SkillActor {
    ON_DEVICE_HUMAN,
    FLEET_WORKER,
    PC_COMPANION,
}

sealed interface SkillPromotionResult {
    data class Rejected(val reason: String) : SkillPromotionResult
}

/**
 * Workers and PC cannot flip `draft → verified`. Phone-side review in Automations
 * does not auto-enable capsules this sprint.
 */
object SkillPromotion {
    const val FUNCTION = "SkillPromotion.requestStatus"

    fun requestStatus(
        store: AutomationStore,
        capsuleId: String,
        actor: SkillActor,
        requested: SkillCapsuleStatus,
        pcEnvelope: PcGateEnvelope? = null,
    ): SkillPromotionResult {
        val existing = store.getAutomation(capsuleId)
        if (existing != null && existing.enabled) {
            store.saveAutomation(existing.copy(enabled = false))
        }
        if (pcEnvelope?.autoApprove == true ||
            pcEnvelope?.requestedCapsuleStatus.equals("verified", ignoreCase = true)
        ) {
            return SkillPromotionResult.Rejected("PC autoApprove is ignored; workers/PC cannot flip draft → verified")
        }
        if (requested == SkillCapsuleStatus.VERIFIED) {
            return SkillPromotionResult.Rejected(
                if (actor == SkillActor.ON_DEVICE_HUMAN) {
                    "review in Automations; capsules stay draft until explicit on-device enable"
                } else {
                    "workers and PC cannot flip draft → verified"
                },
            )
        }
        return SkillPromotionResult.Rejected("status stays draft")
    }
}
