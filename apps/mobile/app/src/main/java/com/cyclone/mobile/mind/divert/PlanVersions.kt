package com.cyclone.mobile.mind.divert

import com.cyclone.mobile.mind.MindPlanStep
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 38: the mission's plan and goal over time. Always on, whatever the settings (D6).
 *
 * - The owner **steers**: the goal gets a new version and the mission must re-plan (one reminder at finish if not).
 * - The model **diverts** on its own (D3): a blocked step, a new fact, another app. It may say so with `divert`; a
 *   plan that drops steps it had not done is a diversion either way.
 * - Every diversion is a **plan version** with its trigger, who decided and why; the card shows the dropped steps
 *   struck through and the new ones as a branch.
 * - Serious actions confirm at their own approval (D3): after a model's diversion, the approval says what changed.
 *
 * Pure, on the mission's loop thread. Nothing here calls a model or refuses an action.
 */
class PlanVersions(goal: String, private val clock: () -> Long = System::currentTimeMillis) {
    data class Goal(val number: Int, val text: String, val by: String, val atMs: Long)

    data class Version(
        val number: Int,
        val steps: List<MindPlanStep>,
        val trigger: String,
        val decidedBy: String,
        val why: String?,
        val from: String?,
        val to: String?,
        val turn: Int,
        val atMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().put("number", number).put("trigger", trigger).put("decidedBy", decidedBy)
            .put("why", why ?: JSONObject.NULL).put("from", from ?: JSONObject.NULL).put("to", to ?: JSONObject.NULL)
            .put("turn", turn).put("at", atMs).put("steps", JSONArray(steps.map { "${it.status}:${it.text}" }))
    }

    /** A diversion the model declared (`plan_update(divert=…)`). */
    data class Divert(val from: String, val to: String, val why: String)

    private val goals = mutableListOf(Goal(1, goal.trim(), OWNER, clock()))
    private val versions = mutableListOf<Version>()
    /** The plan as the model last wrote it (without display rows). */
    private var current: List<MindPlanStep> = emptyList()
    /** What the card shows now: the current plan with the last diversion's dropped and branch rows. */
    var display: List<MindPlanStep> = emptyList()
        private set
    /** A steer arrived and the model has not re-planned since. */
    var needsReplan = false
        private set
    private var steerPending: Goal? = null
    private var replanNoted = false
    var turn = 0

    val goal: Goal get() = goals.last()
    val goalHistory: List<Goal> get() = goals.toList()
    val versionNumber: Int get() = versions.size + 1
    val history: List<Version> get() = versions.toList()
    val label: String? get() = versions.lastOrNull()?.let { if (it.trigger == STEER) "You changed this" else "Changed course" }

    /** Earlier plans as the owner saw them, oldest first, for "See v1". */
    val earlierPlans: List<List<MindPlanStep>> get() = versions.map { it.steps }

    /** The owner changed the task. Returns the new goal version. */
    fun steer(text: String): Goal {
        val next = Goal(goals.size + 1, text.trim().take(2_000), OWNER, clock())
        goals += next
        steerPending = next
        needsReplan = true
        replanNoted = false
        return next
    }

    /**
     * The model wrote a plan. Returns true when it is a diversion (a new plan version): the owner steered since the last
     * plan, the model declared one, or the new plan drops steps that were not done yet.
     */
    fun planned(steps: List<MindPlanStep>, divert: Divert? = null): Boolean {
        val clean = steps.filter { it.text.isNotBlank() }.map { it.copy(note = null, branch = false) }
        val steered = steerPending
        val dropped = if (current.isEmpty()) emptyList() else current.filter { old -> old.status !in FINISHED && clean.none { same(it.text, old.text) } }
        // A declared divert counts even on the first plan (the model found the route blocked before it planned).
        val isDiversion = divert != null || current.isNotEmpty() && (steered != null || dropped.isNotEmpty())
        if (isDiversion) {
            val trigger = when {
                steered != null -> STEER
                divert != null -> DECLARED
                else -> REPLANNED
            }
            versions += Version(versions.size + 1, display.ifEmpty { current }, trigger, if (steered != null) OWNER else MODEL,
                divert?.why?.take(200), divert?.from?.take(160) ?: dropped.firstOrNull()?.text, divert?.to?.take(160), turn, clock())
            display = branch(clean, dropped, divert, steered != null)
        } else {
            // Not a diversion: the same plan moving on. Earlier dropped rows stay visible until the plan diverts again.
            val keep = display.filter { it.dropped }
            display = if (keep.isEmpty() || versions.isEmpty()) clean else merge(clean, keep, versions.last())
        }
        current = clean
        steerPending = null
        needsReplan = false
        return isDiversion
    }

    /** The one reminder at finish after a steer the model did not plan for; any later finish is accepted. */
    fun finishNote(): String? {
        if (!needsReplan || replanNoted) return null
        replanNoted = true
        return "Before finishing: the owner changed the task (goal v${goal.number}: \"${goal.text.take(200)}\"). Update the plan with " +
            "plan_update and do what the new goal asks (or say in the summary why it is already done), then call task_finish again."
    }

    /**
     * Plan 38 D3: a serious action (the ones the harness already gates) right after the model changed course says so on
     * its approval. The owner's own steer needs no note: they asked for it.
     */
    fun approvalNote(): String? {
        val last = versions.lastOrNull()?.takeIf { it.decidedBy == MODEL } ?: return null
        val change = listOfNotNull(last.from?.let { "\"${it.take(60)}\"" }, last.to?.let { "\"${it.take(60)}\"" }).joinToString(" → ")
        return "Changed course" + (if (change.isNotBlank()) ": $change" else "") + (last.why?.let { " ($it)" } ?: "") + "."
    }

    fun record(): JSONObject = JSONObject()
        .put("goalVersions", goals.size)
        .put("goals", JSONArray(goals.map { JSONObject().put("number", it.number).put("by", it.by).put("at", it.atMs) }))
        .put("versions", JSONArray(versions.map { it.toJson() }))
        .put("diversions", versions.size)
        .put("steered", versions.count { it.trigger == STEER })
        .put("byModel", versions.count { it.decidedBy == MODEL })

    private fun branch(steps: List<MindPlanStep>, dropped: List<MindPlanStep>, divert: Divert?, steered: Boolean): List<MindPlanStep> {
        val note = if (steered) "You changed this" else divert?.why?.takeIf { it.isNotBlank() }?.take(120)
        val struck = dropped.ifEmpty { divert?.from?.let { listOf(MindPlanStep(it.take(140), "todo")) }.orEmpty() }
            .map { it.copy(status = MindPlanStep.DROPPED, note = null, branch = false) }
        val firstNew = steps.indexOfFirst { step -> step.status !in FINISHED }.let { if (it < 0) steps.size else it }
        val added = steps.mapIndexed { i, step -> i >= firstNew && current.none { same(it.text, step.text) } }
        var noted = false
        val marked = steps.mapIndexed { i, step ->
            if (!added[i]) step else step.copy(branch = true, note = if (!noted) { noted = true; note } else null)
        }
        val at = marked.indexOfFirst { it.branch }.let { if (it < 0) firstNew else it }
        return marked.take(at) + struck + marked.drop(at)
    }

    private fun merge(steps: List<MindPlanStep>, keep: List<MindPlanStep>, last: Version): List<MindPlanStep> {
        val branchTexts = display.filter { it.branch }.map { it.text }
        val marked = steps.map { step ->
            val wasBranch = branchTexts.any { same(it, step.text) }
            if (!wasBranch) step else step.copy(branch = true, note = display.firstOrNull { it.branch && same(it.text, step.text) }?.note)
        }
        val at = marked.indexOfFirst { it.branch }.let { if (it < 0) marked.indexOfFirst { s -> s.status !in FINISHED }.let { i -> if (i < 0) marked.size else i } else it }
        return marked.take(at) + keep + marked.drop(at)
    }

    companion object {
        const val OWNER = "owner"
        const val MODEL = "model"
        const val STEER = "steer"
        const val DECLARED = "declared"
        const val REPLANNED = "replanned"
        private val FINISHED = setOf("done", "skipped")
        private val STOP = setOf("the", "a", "an", "to", "in", "on", "of", "and", "my", "her", "his", "de", "het", "een", "op", "van")

        /**
         * Two plan steps are the same step when their words mostly match (a reworded step is not a diversion) and their
         * numbers agree: "Set 5 minutes" and "Set 3 minutes" are different steps.
         */
        fun same(a: String, b: String): Boolean {
            val x = words(a)
            val y = words(b)
            if (x.isEmpty() || y.isEmpty()) return a.trim().equals(b.trim(), true)
            if (x.filter { w -> w.any(Char::isDigit) }.toSet() != y.filter { w -> w.any(Char::isDigit) }.toSet()) return false
            return x.intersect(y).size.toDouble() / x.union(y).size >= 0.5
        }

        private fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}@.]+")).filter { it.isNotBlank() && it !in STOP }.toSet()
    }
}
