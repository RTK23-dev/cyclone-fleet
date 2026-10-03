package com.cyclone.mobile.mind.lab

import com.cyclone.mobile.mind.MindListener
import com.cyclone.mobile.mind.MindMessage
import com.cyclone.mobile.mind.MindModel
import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.MindToolResult
import com.cyclone.mobile.mind.MindCheckpoint
import com.cyclone.mobile.mind.mission.MindRedaction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cyclone Lab: missions started by the PC lab to measure the Mind. A variant is one arm of an experiment; it changes
 * only what it names, for this one mission, and is recorded with the mission so every result says what produced it.
 * Nothing here can loosen a boundary: approvals, secrets and the one-mutation rule are the harness's, not the variant's.
 */
data class MindLabVariant(
    val name: String,
    /** OpenRouter model id; null keeps the owner's active model. */
    val modelId: String? = null,
    /** low / medium / high; null keeps the owner's setting. */
    val effort: String? = null,
    /** Working-time budget for this mission; null keeps the owner's setting. */
    val workingMinutes: Int? = null,
    /** Numbered boxes on screenshots (set-of-marks). */
    val marks: Boolean = true,
    /** Start without the owner's Mind memory and recent missions, and keep what this mission learns out of them. */
    val freshMemory: Boolean = true,
    /** Extra instruction appended to the system prompt, to A/B test prompt changes without a new build. */
    val promptAddendum: String = "",
    /**
     * Runs from the map: learned-screen advice, the app's map card and the go_to walker. With it on, the lab also
     * learns each mission when it ends, so later trials of the arm start from what earlier ones saw.
     */
    val useMap: Boolean = true,
    /**
     * Plan 26 (A42-9): where the mission works, for the Lab planes suite: automatic, screen or background. Null keeps
     * lab runs on the screen (as before), so older experiments measure the same thing.
     */
    val plane: String? = null,
    /** Plan 37: classic or workspace context. Null keeps lab runs classic, so older experiments measure the same thing. */
    val context: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("modelId", modelId ?: JSONObject.NULL)
        .put("effort", effort ?: JSONObject.NULL)
        .put("workingMinutes", workingMinutes ?: JSONObject.NULL)
        .put("marks", marks)
        .put("freshMemory", freshMemory)
        .put("promptAddendum", promptAddendum)
        .put("useMap", useMap)
        .put("plane", plane ?: JSONObject.NULL)
        .put("context", context ?: JSONObject.NULL)

    companion object {
        val KEYS = setOf("name", "modelId", "effort", "workingMinutes", "marks", "freshMemory", "promptAddendum", "useMap", "plane", "context")
        const val CLASSIC = "classic"
        const val WORKSPACE = "workspace"
        val CONTEXTS = setOf(CLASSIC, WORKSPACE)
        val PLANES = setOf("automatic", "screen", "background")
        private val NAME = Regex("^[A-Za-z0-9 ._-]{1,40}$")
        private val MODEL = Regex("^[a-z0-9][a-z0-9._-]{0,60}/[A-Za-z0-9][A-Za-z0-9._:-]{0,80}$")
        private val EFFORTS = setOf("low", "medium", "high")
        private val INLINE_SECRET = Regex("(?i)(password|passcode|otp|api[_-]?key|secret|token|cvv)\\s*[:=]")
        const val MAX_ADDENDUM = 1_500

        /** Parses a variant sent by the PC; every field is checked, anything unknown is refused. */
        fun parse(json: JSONObject?): Result<MindLabVariant> = runCatching {
            requireNotNull(json) { "variant is required." }
            val extra = json.keys().asSequence().filterNot { it in KEYS }.toList()
            require(extra.isEmpty()) { "Unknown variant field." }
            val name = json.optString("name").trim()
            require(NAME.matches(name)) { "variant.name must be 1..40 letters, digits, space, dot, dash or underscore." }
            val model = json.optNullableString("modelId")
            require(model == null || MODEL.matches(model)) { "variant.modelId must be an OpenRouter id like vendor/model." }
            val effort = json.optNullableString("effort")
            require(effort == null || effort in EFFORTS) { "variant.effort must be low, medium or high." }
            val minutes = if (json.isNull("workingMinutes") || !json.has("workingMinutes")) null else json.get("workingMinutes").let {
                require(it is Int || it is Long) { "variant.workingMinutes must be a whole number." }
                (it as Number).toInt()
            }
            require(minutes == null || minutes in 2..60) { "variant.workingMinutes must be 2..60." }
            val addendum = json.optString("promptAddendum", "").trim()
            require(addendum.length <= MAX_ADDENDUM) { "variant.promptAddendum is longer than $MAX_ADDENDUM characters." }
            require(!INLINE_SECRET.containsMatchIn(addendum)) { "Do not put secrets in a lab prompt." }
            val plane = json.optNullableString("plane")
            require(plane == null || plane in PLANES) { "variant.plane must be automatic, screen or background." }
            val context = json.optNullableString("context")
            require(context == null || context in CONTEXTS) { "variant.context must be classic or workspace." }
            MindLabVariant(name, model, effort, minutes, json.optBooleanStrict("marks", true), json.optBooleanStrict("freshMemory", true), addendum,
                json.optBooleanStrict("useMap", true), plane, context)
        }

        private fun JSONObject.optNullableString(key: String): String? =
            if (!has(key) || isNull(key)) null else (get(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("variant.$key must be text.")

        private fun JSONObject.optBooleanStrict(key: String, default: Boolean): Boolean =
            if (!has(key)) default else (get(key) as? Boolean) ?: throw IllegalArgumentException("variant.$key must be true or false.")
    }
}

/** The lab tag on a mission: which experiment run it belongs to and the variant that produced it. */
data class MissionLab(val runId: String, val variant: MindLabVariant) {
    fun toJson(): JSONObject = JSONObject().put("runId", runId).put("variant", variant.toJson())

    companion object {
        val RUN_ID = Regex("^[A-Za-z0-9_-]{6,80}$")
        fun fromJson(json: JSONObject?): MissionLab? = json?.let {
            val variant = MindLabVariant.parse(it.optJSONObject("variant")).getOrNull() ?: return null
            MissionLab(it.optString("runId").takeIf { id -> RUN_ID.matches(id) } ?: return null, variant)
        }
    }
}

/**
 * What a mission did, counted as it happens: every tool call and failure, screen changes, owner waits, provider
 * trouble and loops. Recorded for every mission (lab or not) so real use is data too. Only tool names, counts and
 * redacted one-line briefs are kept; never arguments, typed values or screen contents.
 */
class MissionMetrics(private val clock: () -> Long = System::currentTimeMillis) : MindListener {
    private val lock = Any()
    private val calls = linkedMapOf<String, Int>()
    private val errors = linkedMapOf<String, Int>()
    private val errorTail = ArrayDeque<String>()
    private var turns = 0
    private var screenChanges = 0
    private var ownerWaitMs = 0L
    private var notices = 0
    private var modelSwitches = 0
    private var rateLimits = 0
    private var providerRetries = 0
    private var finishRejections = 0
    private var repeats = 0
    private var firstErrorTurn: Int? = null
    private var lastAction: String? = null
    private var modelMs = 0L
    private var modelStartedAt: Long? = null
    private var slowestTurnMs = 0L
    private var mapMoves = 0

    override fun onModelStart(turn: Int, model: MindModel) = synchronized(lock) {
        turns = maxOf(turns, turn)
        modelStartedAt = clock()
    }

    override fun onAssistant(turn: Int, message: MindMessage.Assistant) = synchronized(lock) {
        modelStartedAt?.let { started ->
            val spent = (clock() - started).coerceAtLeast(0)
            modelMs += spent
            slowestTurnMs = maxOf(slowestTurnMs, spent)
        }
        modelStartedAt = null
    }

    override fun onToolResult(turn: Int, call: MindToolCall, result: MindToolResult) = synchronized(lock) {
        calls[call.name] = (calls[call.name] ?: 0) + 1
        if (result.changedScreen) screenChanges++
        ownerWaitMs += result.ownerWaitMs.coerceAtLeast(0)
        mapMoves += result.mapMoves.coerceAtLeast(0)
        val signature = call.name + ":" + call.arguments.trim()
        if (result.changedScreen && signature == lastAction) repeats++
        if (result.changedScreen) lastAction = signature
        if (!result.ok) {
            errors[call.name] = (errors[call.name] ?: 0) + 1
            if (call.name == "task_finish") finishRejections++
            if (firstErrorTurn == null) firstErrorTurn = turn
            errorTail.addLast("${call.name}: ${MindRedaction.scrubText(result.brief).take(140)}")
            while (errorTail.size > MAX_TAIL) errorTail.removeFirst()
        }
    }

    override fun onNotice(turn: Int, text: String) = synchronized(lock) {
        notices++
        when {
            text.startsWith("Switched from") -> modelSwitches++
            text.contains("rate-limited") -> rateLimits++
            text.contains("Retrying") || text.contains("Asking again") -> providerRetries++
        }
    }

    override fun checkpoint(checkpoint: MindCheckpoint) = Unit

    /** Plan 37: the mission workspace's record (stays, checks, diversions, done checks), when the mission has one. */
    @Volatile var workspace: (() -> JSONObject?)? = null
    /** Plan 38: plan versions, steers and the model's diversions. */
    @Volatile var divert: (() -> JSONObject?)? = null

    fun toJson(): JSONObject = synchronized(lock) {
        JSONObject()
            .put("workspace", workspace?.let { runCatching { it() }.getOrNull() } ?: JSONObject.NULL)
            .put("divert", divert?.let { runCatching { it() }.getOrNull() } ?: JSONObject.NULL)
            .put("schema", SCHEMA)
            .put("turns", turns)
            .put("toolCalls", JSONObject(calls as Map<*, *>))
            .put("toolErrors", JSONObject(errors as Map<*, *>))
            .put("actions", calls.values.sum())
            .put("errors", errors.values.sum())
            .put("screenChanges", screenChanges)
            .put("mapMoves", mapMoves)
            .put("repeatedActions", repeats)
            .put("ownerWaitMs", ownerWaitMs)
            .put("modelMs", modelMs)
            .put("slowestTurnMs", slowestTurnMs)
            .put("notices", notices)
            .put("modelSwitches", modelSwitches)
            .put("rateLimits", rateLimits)
            .put("providerRetries", providerRetries)
            .put("finishRejections", finishRejections)
            .put("firstErrorTurn", firstErrorTurn ?: JSONObject.NULL)
            .put("errorTail", JSONArray(errorTail.toList()))
    }

    companion object {
        const val SCHEMA = "cyclone-mission-metrics-v1"
        const val MAX_TAIL = 8
    }
}

/** Sends every loop event to several listeners; one failing listener never stops the others or the mission. */
class TeeMindListener(private val listeners: List<MindListener>) : MindListener {
    override fun onModelStart(turn: Int, model: MindModel) = each { it.onModelStart(turn, model) }
    override fun onAssistant(turn: Int, message: MindMessage.Assistant) = each { it.onAssistant(turn, message) }
    override fun onToolStart(turn: Int, call: MindToolCall) = each { it.onToolStart(turn, call) }
    override fun onToolResult(turn: Int, call: MindToolCall, result: MindToolResult) = each { it.onToolResult(turn, call, result) }
    override fun onNotice(turn: Int, text: String) = each { it.onNotice(turn, text) }
    override fun checkpoint(checkpoint: MindCheckpoint) = each { it.checkpoint(checkpoint) }
    private inline fun each(block: (MindListener) -> Unit) = listeners.forEach { runCatching { block(it) } }
}
