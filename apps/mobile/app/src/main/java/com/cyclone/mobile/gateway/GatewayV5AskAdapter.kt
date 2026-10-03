package com.cyclone.mobile.gateway

import android.os.Handler
import android.os.Looper
import android.content.Context
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.mind.modes.AskLedger
import com.cyclone.mobile.task.TaskCommand
import com.cyclone.mobile.task.TaskCommands
import com.cyclone.mobile.runtime.background.TaskPresentationProjector
import com.cyclone.mobile.runtime.background.TaskPresentationSnapshot
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.runtime.session.ExecutionSession
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import org.json.JSONArray
import org.json.JSONObject

/**
 * V5 `ask.start` / `ask.status`: Glass types a goal, the phone runs it.
 *
 * The goal enters exactly like a sentence typed into the phone overlay (same run, same GATE, same
 * Secrets Card), on the foreground plane only. Glass never gets a second executor: it sends goal
 * text and reads back the same presentation snapshot the phone shows.
 */
internal object GatewayV5AskAdapter {
    const val MAX_GOAL = 2_000
    private val INLINE_SECRET = Regex(
        "(?i)(password|passcode|passwd|pin|otp|token|secret|api[_-]?key|authorization|cookie|cvv|credential)\\s*[:=]",
    )

    /** Seams for JVM tests; production uses the overlay runtime and the task state flow. */
    internal var overlayReady: () -> Boolean = { OverlayChromeRuntime.isAttached() }
    internal var busy: () -> Boolean = {
        OverlayChromeRuntime.hasExecutingTask() || !WorkspaceTasks.canStartRequest()
    }
    internal var humanHasControl: () -> Boolean = { DeviceState.controller == DeviceState.Controller.HUMAN }
    internal var submit: (String) -> Unit = { goal ->
        Handler(Looper.getMainLooper()).post { OverlayChromeRuntime.submitRequest(goal) }
    }
    @Volatile private var context: Context? = null

    fun install(context: Context) { this.context = context.applicationContext }

    /** Alpha 91: stopping what a request started goes through Task Kit for a task, and the voice/instant stop otherwise. */
    internal var stopTask: (String) -> Boolean = { taskId ->
        context?.let { TaskCommands.send(it, taskId, TaskCommand.Stop).handled } == true
    }
    internal var stopInstant: () -> Unit = { OverlayChromeRuntime.stopVoiceRequest() }
    internal var currentTask: () -> TaskPresentationSnapshot? = {
        WorkspaceTasks.state.value?.let { TaskPresentationProjector.project(it) }
    }

    fun dispatch(op: String, args: JSONObject): JSONObject = when (op) {
        "ask.start" -> start(args)
        "ask.status" -> status(args)
        "ask.cancel" -> cancel(args)
        else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported ask operation: $op")
    }

    fun start(args: JSONObject): JSONObject {
        requireOnly(args, setOf("goal", "sessionId", "displayId"))
        requireForeground(args)
        val goal = (args.opt("goal") as? String)?.trim().orEmpty()
        if (goal.isBlank() || goal.length > MAX_GOAL) {
            throw GatewayProtocolException("INVALID_REQUEST", "goal must be 1..$MAX_GOAL characters of text.")
        }
        if (INLINE_SECRET.containsMatchIn(goal)) {
            // Secrets enter only through the Secrets Card, never through a goal sentence.
            throw GatewayProtocolException("INVALID_REQUEST", "Do not put secrets in a goal; Cyclone will ask on the phone.")
        }
        if (!overlayReady()) {
            throw GatewayProtocolException("OVERLAY_UNAVAILABLE", "Turn on Cyclone's accessibility service on the phone.")
        }
        if (humanHasControl()) {
            throw GatewayProtocolException("HUMAN_HAS_CONTROL", "You have control of the phone. Give it back to Cyclone first.")
        }
        if (busy()) {
            throw GatewayProtocolException("ASK_BUSY", "The phone is already running a task.")
        }
        // Alpha 91: every request gets an id; ask.status {requestId} says which lane took it and how it ended.
        val requestId = AskLedger.begin(goal)
        submit(goal)
        return JSONObject()
            .put("accepted", true)
            .put("sessionId", ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID)
            .put("displayId", 0)
            .put("requestId", requestId)
    }

    fun status(args: JSONObject): JSONObject {
        requireOnly(args, setOf("sessionId", "displayId", "requestId"))
        requireForeground(args)
        val entry = requestOf(args) ?: AskLedger.latest()
        val snapshot = currentTask()
        // A request Instant, Answer or Ignore took has no task: the previous task is not shown as if it were this one.
        val own = entry == null || entry.lane == null || entry.lane in MISSION_LANES
        val base = if (snapshot == null || !own) idle() else snapshotJson(snapshot)
        return base.put("request", entry?.let(::entryJson) ?: JSONObject.NULL)
    }

    /** Stops what [requestId] (or the latest request) started: its Instant run, or its Flash/Mind task through Task Kit. */
    fun cancel(args: JSONObject): JSONObject {
        requireOnly(args, setOf("sessionId", "displayId", "requestId"))
        requireForeground(args)
        val entry = requestOf(args) ?: AskLedger.latest() ?: return JSONObject().put("cancelled", false).put("detail", "No request to stop.")
        if (entry.state in setOf("done", "failed", "cancelled")) {
            return JSONObject().put("cancelled", false).put("detail", "That request already ended.").put("request", entryJson(entry))
        }
        val stopped = if (entry.lane in MISSION_LANES) {
            currentTask()?.taskId?.let(stopTask) == true
        } else {
            stopInstant()
            true
        }
        val after = if (stopped) AskLedger.cancel(entry.requestId) ?: entry else entry
        return JSONObject().put("cancelled", stopped)
            .put("detail", if (stopped) "Stopped." else "Nothing was running for that request.")
            .put("request", entryJson(after))
    }

    private val MISSION_LANES = setOf("flash", "mind")
    private val REQUEST_ID = Regex("^req-[0-9a-f-]{8,40}$")

    private fun requestOf(args: JSONObject): AskLedger.Entry? {
        val id = args.opt("requestId") ?: return null
        if (id !is String || !REQUEST_ID.matches(id)) throw GatewayProtocolException("INVALID_REQUEST", "requestId is malformed.")
        return AskLedger.get(id) ?: throw GatewayProtocolException("INVALID_REQUEST", "That request is not known on the phone (it keeps the last ${AskLedger.KEEP}).")
    }

    internal fun entryJson(entry: AskLedger.Entry): JSONObject = JSONObject()
        .put("requestId", entry.requestId)
        .put("state", entry.state)
        .put("lane", entry.lane ?: JSONObject.NULL)
        .put("decider", entry.decider ?: JSONObject.NULL)
        .put("why", entry.why ?: JSONObject.NULL)
        .put("decideMs", entry.decideMs)
        .put("startedAtMs", entry.startedAtMs)
        .put("routedAtMs", entry.routedAtMs ?: JSONObject.NULL)
        .put("finishedAtMs", entry.finishedAtMs ?: JSONObject.NULL)
        .put("say", entry.say ?: JSONObject.NULL)

    internal fun snapshotJson(snapshot: TaskPresentationSnapshot): JSONObject = JSONObject()
        .put("taskId", snapshot.taskId.take(120))
        .put("state", snapshot.state.wireValue)
        .put("title", snapshot.title.take(200))
        .put("app", snapshot.app.take(80))
        .put("currentMilestone", snapshot.currentMilestone?.take(120) ?: JSONObject.NULL)
        .put("milestones", JSONArray().also { out ->
            snapshot.milestones.take(8).forEach { milestone ->
                out.put(JSONObject()
                    .put("label", milestone.label.take(90))
                    .put("state", milestone.state.name.lowercase().replace('_', '-')))
            }
        })
        .put("supportingCopy", snapshot.supportingCopy?.take(240) ?: JSONObject.NULL)
        .put("outcomeCopy", snapshot.outcomeCopy?.take(600) ?: JSONObject.NULL)
        .put("sessionId", ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID)
        .put("displayId", 0)

    private fun idle(): JSONObject = JSONObject()
        .put("taskId", JSONObject.NULL)
        .put("state", "idle")
        .put("title", "")
        .put("app", "")
        .put("currentMilestone", JSONObject.NULL)
        .put("milestones", JSONArray())
        .put("supportingCopy", JSONObject.NULL)
        .put("outcomeCopy", JSONObject.NULL)
        .put("sessionId", ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID)
        .put("displayId", 0)

    private fun requireForeground(args: JSONObject) {
        // Alpha 91: the phone's main screen is the default; a client only names it to be explicit.
        val sessionId = args.opt("sessionId") ?: ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID
        if (sessionId !is String || sessionId.isBlank()) {
            throw GatewayProtocolException("SESSION_REQUIRED", "sessionId is required for ask.")
        }
        val display = args.opt("displayId") ?: 0
        if (sessionId != ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID || display !is Number || display.toInt() != 0) {
            throw GatewayProtocolException(
                "SESSION_DISPLAY_MISMATCH",
                "Ask from Glass runs on the phone's main screen (default-foreground, display 0).",
            )
        }
    }

    private fun requireOnly(args: JSONObject, allowed: Set<String>) {
        val extra = args.keys().asSequence().filterNot { it in allowed }.toList()
        if (extra.isNotEmpty()) {
            throw GatewayProtocolException("INVALID_REQUEST", "Unexpected ask field.")
        }
    }
}
