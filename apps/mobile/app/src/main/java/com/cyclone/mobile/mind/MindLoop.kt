package com.cyclone.mobile.mind

import org.json.JSONObject

/** Limits for one mission. Working time excludes time spent waiting for the owner. */
data class MindBudget(
    val workingMs: Long = 30 * 60_000L,
    val maxTurns: Int = 400,
    val perCallMs: Long = 180_000L,
    /** Alpha 93: 150k → 100k; with compaction to 60% this keeps a long run near 15-25k tokens a turn. */
    val maxContextChars: Int = 100_000,
    /** Plan 37: the ceiling for workspace runs, which fold per app stay long before this. */
    val workspaceChars: Int = 100_000,
    val warnBeforeEndMs: Long = 3 * 60_000L,
)

enum class MindStatus { COMPLETED, GAVE_UP, FAILED, CANCELLED, OUT_OF_BUDGET }

data class MindOutcome(
    val status: MindStatus,
    val summary: String,
    val evidence: String? = null,
    val turns: Int,
    val usage: MindUsage,
    val workingMs: Long,
    val modelLabel: String,
)

/** Durable progress of a mission, written after every turn so it can resume after an interruption. */
data class MindCheckpoint(
    val turn: Int,
    val workingMs: Long,
    val usage: MindUsage,
    val nativeTools: Boolean,
    val modelId: String,
    val conversation: MindConversation,
    /** Plan 37: the mission workspace's state, when the mission runs with one. */
    val workspace: org.json.JSONObject? = null,
)

/** What the loop reports while it runs. Implementations must not block for long. */
interface MindListener {
    fun onModelStart(turn: Int, model: MindModel) {}
    fun onAssistant(turn: Int, message: MindMessage.Assistant) {}
    fun onToolStart(turn: Int, call: MindToolCall) {}
    fun onToolResult(turn: Int, call: MindToolCall, result: MindToolResult) {}
    fun onNotice(turn: Int, text: String) {}
    fun checkpoint(checkpoint: MindCheckpoint) {}
}

/**
 * The engine: one model, one conversation, one mission. Each turn the model sees the whole conversation (compacted
 * to fit), calls tools, and every result goes back into the conversation. The loop decides nothing about the goal;
 * it keeps time, recovers from provider trouble, enforces one screen change per turn and stops when a tool ends the
 * mission.
 */
class MindLoop(
    primary: MindModel,
    private val backup: MindModel?,
    private val toolbox: MindToolbox,
    private val budget: MindBudget = MindBudget(),
    private val listener: MindListener = object : MindListener {},
    private val cancelled: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /** Owner messages sent while the mission runs; drained at the start of each turn. */
    private val ownerMessages: () -> List<String> = { emptyList() },
    nativeTools: Boolean = true,
    /**
     * Plan 37: the mission workspace. With it, each call ends with the live state (never stored), closed app stays are
     * folded with a journal block, and the context ceiling is [MindBudget.workspaceChars]. Null runs the classic loop.
     */
    private val workspace: com.cyclone.mobile.mind.workspace.MissionWorkspace? = null,
    /** Plan 38: the owner's steers (Steer in the Ask bar): each one is a new goal version the plan must follow. */
    private val ownerSteers: () -> List<String> = { emptyList() },
    /** Plan 38: the owner paused the mission; it holds before its next step, and the pause is not working time. */
    private val paused: () -> Boolean = { false },
    /**
     * Alpha 92: for a goal that is only phone settings, what the phone reads when they all hold (else null). Asked
     * before each turn; the first time it holds, the model is told to finish.
     */
    private val goalMet: () -> String? = { null },
) {
    private var goalNoted = false
    private var model: MindModel = primary
    private var usingBackup = false
    private var native = nativeTools
    private var usage = MindUsage()
    private var turn = 0
    private var workedBefore = 0L
    private var ownerWaited = 0L
    private var startedAt = 0L
    private var warned = false
    private var compactionNoted = false
    private val failures = mutableMapOf<String, Int>()
    private var workspaceFailed = false

    val currentModel: MindModel get() = model
    val nativeToolsActive: Boolean get() = native

    private fun working(): Long = workedBefore + (clock() - startedAt - ownerWaited).coerceAtLeast(0)

    /** Continue [conversation] (a fresh mission or a resumed journal) until the mission ends. */
    fun run(conversation: MindConversation, resumeFrom: MindCheckpoint? = null): MindOutcome {
        startedAt = clock()
        resumeFrom?.let {
            turn = it.turn
            workedBefore = it.workingMs
            usage = it.usage
            native = native && it.nativeTools
        }
        var silentTurns = 0
        workspace?.attach { conversation.all() }
        while (true) {
            if (cancelled()) return end(MindStatus.CANCELLED, "Stopped by the owner.", null, conversation)
            if (turn >= budget.maxTurns) return end(MindStatus.OUT_OF_BUDGET, outOfBudget("turn limit reached", conversation), null, conversation)
            val remaining = budget.workingMs - working()
            if (remaining <= 0) return end(MindStatus.OUT_OF_BUDGET, outOfBudget("working time used up", conversation), null, conversation)
            if (!warned && remaining <= budget.warnBeforeEndMs) {
                warned = true
                conversation.add(MindMessage.User(MindPrompt.budgetWarning((remaining / 60_000).coerceAtLeast(1)), origin = MindMessage.User.Origin.HARNESS))
            }
            if (paused()) {
                val from = clock()
                listener.onNotice(turn, "Paused by the owner.")
                while (paused() && !cancelled()) sleep(PAUSE_POLL_MS)
                ownerWaited += (clock() - from).coerceAtLeast(0)
                if (cancelled()) return end(MindStatus.CANCELLED, "Stopped by the owner.", null, conversation)
                conversation.add(MindMessage.User(MindPrompt.PAUSED, origin = MindMessage.User.Origin.HARNESS))
                listener.onNotice(turn, "Resumed.")
            }
            ownerSteers().filter(String::isNotBlank).forEach { text ->
                val version = runCatching { toolbox.onSteer(text) }.getOrDefault(0)
                conversation.add(MindMessage.User(MindPrompt.steered(text, version.coerceAtLeast(2))))
                listener.onNotice(turn, "Owner changed the task: ${text.take(160)}")
                silentTurns = 0
            }
            if (!goalNoted) runCatching { goalMet() }.getOrNull()?.let { met ->
                goalNoted = true
                conversation.add(MindMessage.User(MindPrompt.goalMet(met), origin = MindMessage.User.Origin.HARNESS))
                listener.onNotice(turn, "The phone shows the goal is met: ${met.take(160)}")
            }
            ownerMessages().filter(String::isNotBlank).forEach {
                conversation.add(MindMessage.User(MindPrompt.ownerMessage(it)))
                // Plan 37 W3: a memory request mid-mission is named for the model, so it is never missed.
                runCatching { toolbox.onOwnerMessage(it) }
                RememberIntent.detect(it)?.let { asked ->
                    conversation.add(MindMessage.User(MindPrompt.rememberAsked(asked), origin = MindMessage.User.Origin.HARNESS))
                }
                listener.onNotice(turn, "Owner: ${it.take(160)}")
                silentTurns = 0
            }
            turn++
            val ceiling = if (workspace != null) budget.workspaceChars else budget.maxContextChars
            if (conversation.compact(ceiling) > 0 && !compactionNoted) {
                compactionNoted = true
                conversation.add(MindMessage.User(MindPrompt.COMPACTED, origin = MindMessage.User.Origin.HARNESS))
            }
            workspace?.let { ws -> guarded { ws.beginTurn(turn, conversation.size(), working(), budget.workingMs) } }
            val reply = when (val attempt = ask(conversation, remaining)) {
                is Attempt.Reply -> attempt.reply
                is Attempt.Stop -> return end(attempt.status, attempt.summary, null, conversation)
            }
            usage += reply.usage
            val assistant = MindMessage.Assistant(reply.text, reply.toolCalls, reply.reasoningDetails)
            conversation.add(assistant)
            listener.onAssistant(turn, assistant)
            if (reply.toolCalls.isEmpty()) {
                silentTurns++
                if (silentTurns > MAX_SILENT_TURNS) {
                    val said = reply.text.trim().ifBlank { "The model stopped acting without finishing or explaining why." }
                    return end(MindStatus.FAILED, "Stopped without a result. The model's last words: ${said.take(600)}", null, conversation)
                }
                conversation.add(MindMessage.User(MindPrompt.NUDGE, origin = MindMessage.User.Origin.HARNESS))
                checkpoint(conversation)
                continue
            }
            silentTurns = 0
            val ending = executeCalls(reply.toolCalls, conversation)
            workspace?.let { ws ->
                guarded { ws.endTurn(conversation) }
                if (ending == null) guarded { ws.stepAdvice()?.let { conversation.add(MindMessage.User(it, origin = MindMessage.User.Origin.HARNESS)) } }
            }
            checkpoint(conversation)
            if (ending != null) return end(
                if (ending.ending == MindEnding.COMPLETED) MindStatus.COMPLETED else MindStatus.GAVE_UP,
                ending.summary ?: ending.text, ending.evidence, conversation,
            )
        }
    }

    /** Runs the calls of one model turn in order. Every call gets a result so the conversation stays well formed. */
    private fun executeCalls(calls: List<MindToolCall>, conversation: MindConversation): MindToolResult? {
        val screens = mutableListOf<MindMessage.User>()
        var ending: MindToolResult? = null
        var stoppedBy: String? = null
        val known = toolbox.specs().map { it.name }.toSet()
        calls.forEach { call ->
            val result = when {
                ending != null -> MindToolResult("NOT RUN: the mission already ended with ${ending!!.ending?.name?.lowercase()}.", ok = false)
                stoppedBy != null -> MindToolResult("NOT RUN: $stoppedBy changed the screen first. Look at the new screen and decide again.", ok = false)
                cancelled() -> MindToolResult("NOT RUN: the owner stopped the mission.", ok = false)
                call.name !in known -> MindToolResult.error("There is no tool named ${call.name}. Available: ${known.sorted().joinToString()}.")
                else -> call.argumentsJson()?.let { arguments -> runTool(call, arguments) }
                    ?: MindToolResult.error("The arguments for ${call.name} were not valid JSON: ${call.arguments.take(200)}")
            }
            ownerWaited += result.ownerWaitMs.coerceAtLeast(0)
            conversation.add(MindMessage.Tool(call.id, call.name, result.text, result.brief))
            listener.onToolResult(turn, call, result)
            result.imageDataUrl?.let { image ->
                screens += if (model.vision) MindMessage.User("Screenshot after ${call.name}:", image, MindMessage.User.Origin.SCREEN)
                else MindMessage.User("A screenshot was taken, but the current model cannot see images; rely on the text description.", origin = MindMessage.User.Origin.HARNESS)
            }
            trackFailure(call, result, conversation)
            if (result.ending != null && ending == null) ending = result
            if (result.changedScreen && stoppedBy == null) stoppedBy = call.name
        }
        screens.forEach(conversation::add)
        return ending
    }

    private fun runTool(call: MindToolCall, arguments: JSONObject): MindToolResult {
        listener.onToolStart(turn, call)
        return try {
            toolbox.execute(call, arguments)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            MindToolResult("NOT RUN: the owner stopped the mission.", ok = false)
        } catch (error: Exception) {
            MindToolResult.error("${call.name} failed inside Cyclone: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun trackFailure(call: MindToolCall, result: MindToolResult, conversation: MindConversation) {
        val key = call.name + "|" + (call.argumentsJson()?.toString() ?: call.arguments)
        if (result.ok) { failures.remove(key); return }
        if (result.text.startsWith("NOT RUN")) return
        val count = (failures[key] ?: 0) + 1
        failures[key] = count
        if (count == REPEATED_FAILURE_NOTE_AT) {
            conversation.add(MindMessage.User(MindPrompt.repeatedFailure(call.name, count), origin = MindMessage.User.Origin.HARNESS))
        }
    }

    private sealed class Attempt {
        data class Reply(val reply: MindModelReply) : Attempt()
        data class Stop(val status: MindStatus, val summary: String) : Attempt()
    }

    /** One model decision, with recovery: wait out rate limits, retry transient trouble, switch to the backup model. */
    private fun ask(conversation: MindConversation, remainingMs: Long): Attempt {
        var transient = 0
        var rateLimited = 0
        var deadlines = 0
        var toolsFallback = false
        while (true) {
            if (cancelled()) return Attempt.Stop(MindStatus.CANCELLED, "Stopped by the owner.")
            val left = budget.workingMs - working()
            if (left <= 0) return Attempt.Stop(MindStatus.OUT_OF_BUDGET, outOfBudget("working time used up", conversation))
            listener.onModelStart(turn, model)
            val tail = workspace?.let { ws -> guarded { ws.liveState() } }
            val request = MindModelRequest(conversation.toWire(native, tail), toolbox.specs(), native,
                budget.perCallMs.coerceAtMost(left.coerceAtLeast(MIN_CALL_MS)))
            try {
                return Attempt.Reply(model.complete(request))
            } catch (error: MindModelError.Cancelled) {
                return Attempt.Stop(MindStatus.CANCELLED, "Stopped by the owner.")
            } catch (error: MindModelError.ToolsUnsupported) {
                if (toolsFallback || !native) return switchOrStop(conversation, error.message.orEmpty()) ?: continue
                toolsFallback = true
                native = false
                replaceSystemPrompt(conversation)
                listener.onNotice(turn, "${model.label} has no native tool calling on this route; continuing with text tool calls.")
            } catch (error: MindModelError.RateLimited) {
                rateLimited++
                if (!usingBackup && backup != null) { switchOrStop(conversation, "rate limited"); continue }
                if (rateLimited > MAX_RATE_LIMIT_WAITS) return Attempt.Stop(MindStatus.FAILED, "${model.label} stayed rate-limited: ${error.message}")
                val wait = (error.retryAfterMs.coerceAtLeast(1_000) * rateLimited).coerceAtMost(MAX_WAIT_MS)
                listener.onNotice(turn, "${model.label} is rate-limited; waiting ${wait / 1000} s.")
                pause(wait)
            } catch (error: MindModelError.Deadline) {
                deadlines++
                if (deadlines == 1) { listener.onNotice(turn, "${error.message} Asking again."); continue }
                return switchOrStop(conversation, "too slow") ?: continue
            } catch (error: MindModelError.Transient) {
                transient++
                if (transient <= MAX_TRANSIENT_RETRIES) {
                    val wait = (1_000L shl transient).coerceAtMost(MAX_WAIT_MS)
                    listener.onNotice(turn, "${error.message} Retrying in ${wait / 1000} s.")
                    pause(wait)
                    continue
                }
                return switchOrStop(conversation, error.message.orEmpty()) ?: continue
            } catch (error: MindModelError.Fatal) {
                return switchOrStop(conversation, error.message.orEmpty()) ?: continue
            }
        }
    }

    /** Switches to the backup model once; returns a Stop when there is nothing left to switch to. */
    private fun switchOrStop(conversation: MindConversation, why: String): Attempt.Stop? {
        val next = backup
        if (usingBackup || next == null) return Attempt.Stop(MindStatus.FAILED, "${model.label} could not continue: $why")
        val from = model
        model = next
        usingBackup = true
        conversation.forgetReasoning()
        conversation.add(MindMessage.User(MindPrompt.modelSwitched(from.label, next.label, why), origin = MindMessage.User.Origin.HARNESS))
        listener.onNotice(turn, "Switched from ${from.label} to ${next.label}: $why")
        return null
    }

    /** The text tool-call protocol lives in the system prompt; rewrite it when native tools turn out to be unavailable. */
    private fun replaceSystemPrompt(conversation: MindConversation) {
        val messages = conversation.all()
        val first = messages.firstOrNull() as? MindMessage.System ?: return
        val specs = toolbox.specs()
        if (first.text.contains(TEXT_PROTOCOL_MARKER)) return
        val rebuilt = first.text + "\n\n## Tool calls\nReply with exactly one JSON object and nothing else:\n" +
            "{\"say\": \"a short note on what you are doing\", \"calls\": [{\"tool\": \"<name>\", \"arguments\": {…}}]}\n" +
            "Results come back as messages that start with \"RESULT of <tool>\". Available tools:\n" +
            specs.joinToString("\n") { it.toText() }
        conversation.replaceFirst(MindMessage.System(rebuilt))
    }

    private fun pause(ms: Long) {
        val until = clock() + ms
        while (clock() < until && !cancelled()) sleep(minOf(250L, until - clock()).coerceAtLeast(1))
    }

    private fun outOfBudget(why: String, conversation: MindConversation): String {
        val last = conversation.lastAssistant()?.text?.trim().orEmpty()
        return "Paused: $why." + if (last.isNotBlank()) " Last progress: ${last.take(400)}" else ""
    }

    private fun checkpoint(conversation: MindConversation) =
        listener.checkpoint(MindCheckpoint(turn, working(), usage, native, model.id, conversation,
            workspace?.let { ws -> guarded { ws.toJson() } }))

    /**
     * Plan 37: the workspace must never stop a mission. A failure in it is reported once and that part of the turn
     * runs as a classic one.
     */
    private fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (error: Exception) {
        if (!workspaceFailed) {
            workspaceFailed = true
            listener.onNotice(turn, "Mission workspace trouble (${error.javaClass.simpleName}); this turn continues without it.")
        }
        null
    }

    private fun end(status: MindStatus, summary: String, evidence: String?, conversation: MindConversation): MindOutcome {
        checkpoint(conversation)
        return MindOutcome(status, summary, evidence, turn, usage, working(), model.label)
    }

    companion object {
        const val MAX_SILENT_TURNS = 2
        const val MAX_RATE_LIMIT_WAITS = 4
        const val MAX_TRANSIENT_RETRIES = 3
        const val MAX_WAIT_MS = 30_000L
        const val MIN_CALL_MS = 20_000L
        const val REPEATED_FAILURE_NOTE_AT = 3
        const val PAUSE_POLL_MS = 250L
        const val TEXT_PROTOCOL_MARKER = "## Tool calls"
    }
}
