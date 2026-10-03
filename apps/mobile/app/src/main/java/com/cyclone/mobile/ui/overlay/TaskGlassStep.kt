package com.cyclone.mobile.ui.overlay

/**
 * Maps real executor ticks onto the collapsed glass subtitle.
 * Fast Path / compiled skill / Layer 2 slice are distinct; internal Brain bookkeeping is dropped.
 */
enum class GlassStepKind { FAST_PATH, SKILL, LAYER2_SLICE }

data class GlassStep(val kind: GlassStepKind, val label: String)

object TaskGlassStep {
    private val SKILL = Regex("(?i)compiled skill|skill\\.replay|verified app map|replaying compiled")
    private val LAYER2 = Regex("(?i)layer 2|workspace slice|workspace\\.next|time-sliced")
    private val INTERNAL = Regex("(?i)^cyclone brain updated$|writing verified results to second brain")

    fun fromProgress(raw: String): GlassStep? {
        val text = raw.trim()
        if (text.isBlank() || INTERNAL.containsMatchIn(text)) return null
        val kind = when {
            SKILL.containsMatchIn(text) -> GlassStepKind.SKILL
            LAYER2.containsMatchIn(text) -> GlassStepKind.LAYER2_SLICE
            else -> GlassStepKind.FAST_PATH
        }
        return GlassStep(kind, subtitle(kind, text))
    }

    fun subtitle(kind: GlassStepKind, raw: String): String {
        val text = raw.trim().replace(Regex("\\s+"), " ").take(120)
        return when (kind) {
            GlassStepKind.SKILL -> {
                val playbook = text.substringAfter("·", "").trim()
                when {
                    playbook.isNotBlank() -> "Skill · $playbook".take(120)
                    text.contains("verified app map", ignoreCase = true) -> "Skill · verified app map"
                    else -> "Skill · replaying"
                }
            }
            GlassStepKind.LAYER2_SLICE -> {
                val rest = text.substringAfter("·", "").trim()
                if (rest.isNotBlank()) "Layer 2 slice · $rest".take(120) else "Layer 2 slice"
            }
            GlassStepKind.FAST_PATH -> when {
                text.contains("verif", ignoreCase = true) -> "Fast Path · checking the result"
                text.contains("observ", ignoreCase = true) ||
                    text.contains("Checking the page", ignoreCase = true) -> "Fast Path · checking the page"
                else -> "Fast Path · $text".take(120)
            }
        }
    }

    fun layer2Slice(workspaceLabel: String, step: String? = null): GlassStep {
        val detail = step?.trim().orEmpty()
        val label = if (detail.isBlank()) "Layer 2 slice · $workspaceLabel"
        else "Layer 2 slice · $workspaceLabel · $detail"
        return GlassStep(GlassStepKind.LAYER2_SLICE, label.take(120))
    }
}
