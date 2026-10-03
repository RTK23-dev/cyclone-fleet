package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.mind.mission.MindMissions
import com.cyclone.mobile.mind.mission.MindRedaction
import com.cyclone.mobile.mind.mission.Mission
import com.cyclone.mobile.owner.MomentKind
import com.cyclone.mobile.owner.OwnerMoment
import com.cyclone.mobile.owner.OwnerMomentsRuntime
import com.cyclone.mobile.policy.PublishGate
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.secrets.DeviceKey
import com.cyclone.mobile.secrets.SealedDelivery
import com.cyclone.mobile.task.TaskCommand
import com.cyclone.mobile.task.TaskCommandResult
import com.cyclone.mobile.task.TaskCommands
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 33 (C0): the Command Center on the phone (`cc.start` / `cc.status` / `cc.answer`). The owner's PC assigns a
 * task; the phone runs it as an ordinary Mind mission (same PhoneToolExecutor, GATE and Secrets Card), reports how it
 * goes, and takes the owner's answers from the PC's Approvals inbox.
 *
 * Unlike the lab, the Command Center is the owner at their dashboard, so it may approve. It approves only the exact
 * request it was shown: [answer] names the request id, and a send is approvable at the PC only when the text it saw is
 * the text that will be sent (nothing redacted). Secure input and handing the phone over are never answered here.
 * Every answer is a [TaskCommand] through Task Kit, like any button.
 */
internal object GatewayV5CommandAdapter {
    const val MAX_GOAL = 2_000
    private val MISSION_ID = Regex("^m[a-z0-9]{6,40}$")
    private val REQUEST_ID = Regex("^[A-Za-z0-9._:-]{1,120}$")
    private val SIGNUP_PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val INLINE_SECRET = Regex("(?i)(password|passcode|passwd|pin|otp|token|secret|api[_-]?key|authorization|cookie|cvv|credential)\\s*[:=]")
    val ANSWERS = setOf("approve", "decline", "reply", "fill", "stop")

    /** Seams for JVM tests; production uses the overlay, the Mind and Task Kit. */
    internal var overlayReady: () -> Boolean = { OverlayChromeRuntime.isAttached() }
    /** The phone cannot take [goal] now (plan 26 §6: a mission may start behind the running one when the phone can). */
    internal var busyFor: (String) -> Boolean = { goal ->
        OverlayChromeRuntime.hasExecutingTask() || !WorkspaceTasks.canStartRequest() ||
            (MindMissions.isLive() && !MindMissions.canAccept(app(), goal))
    }
    internal var humanHasControl: () -> Boolean = { DeviceState.controller == DeviceState.Controller.HUMAN }
    internal var start: (String) -> String? = { goal -> MindMissions.startAssigned(app(), goal) }
    /** Plan 43 T6: a task that maps [package]'s sign-up; the phone checks the app is installed. */
    internal var startSignup: (String, String) -> String? = { goal, pkg -> MindMissions.startAssigned(app(), goal, signup = pkg) }
    /** Plan 43 T7: an Account Setup run (one account from a sign-up table row), and the phone's sign-up maps. */
    internal var startSetup: (String, com.cyclone.mobile.mind.signup.AccountSetupPlan) -> String? =
        { goal, plan -> MindMissions.startAssigned(app(), goal, setup = plan) }
    internal var signupMap: (String) -> com.cyclone.mobile.mind.signup.SignupMap? = { pkg -> com.cyclone.mobile.mind.signup.SignupMapStore.load(app(), pkg) }
    internal var installed: (String) -> Boolean = { pkg -> runCatching { app().packageManager.getPackageInfo(pkg, 0) }.isSuccess }
    /** The running mission [id], front or behind. */
    internal var running: (String) -> Mission? = { id -> MindMissions.find(id) }
    internal var load: (String) -> Mission? = { MindMissions.store(app()).load(it) }
    internal var moment: () -> OwnerMoment? = { OwnerMomentsRuntime.current() }
    /** The open moment of mission [id]: a mission behind the screen (plan 26 §6) has its own card and request. */
    internal var momentFor: (String) -> OwnerMoment? = { id ->
        if (MindMissions.isBehind(id)) com.cyclone.mobile.owner.OwnerMoments.project(
            MindMissions.behindTasks.value.firstOrNull { it.taskId == "mission-$id" }, MindMissions.inbox.openFor(id))
        else moment()
    }
    internal var send: (String, TaskCommand) -> TaskCommandResult = { taskId, command -> TaskCommands.send(app(), taskId, command) }
    internal var deviceKey: () -> DeviceKey.Public = { DeviceKey.ensure() }
    /** Leases handed to each mission, so status can report their outcomes (ids only). */
    private val missionLeases = mutableMapOf<String, List<String>>()

    @Volatile private var context: Context? = null
    fun install(context: Context) {
        this.context = context.applicationContext
        SealedDelivery.install(context)
        CommandMedia.install(context)
        PublishGate.liveMission = { MindMissions.live.value?.id }
        PublishGate.running = { id -> MindMissions.find(id) != null }
    }
    private fun app(): Context = checkNotNull(context) { "command adapter not installed" }

    fun dispatch(op: String, args: JSONObject): JSONObject = when (op) {
        "cc.start" -> start(args)
        "cc.status" -> status(args)
        "cc.answer" -> answer(args)
        "cc.key" -> key(args)
        "cc.media" -> CommandMedia.receive(args)
        else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported Command Center operation: $op")
    }

    /** Plan 33 C2: this phone's device key for sealed delivery. The public half and its fingerprint only. */
    fun key(args: JSONObject): JSONObject {
        requireOnly(args, emptySet())
        val key = runCatching { deviceKey() }.getOrElse { throw GatewayProtocolException("KEY_UNAVAILABLE", "This phone could not make its device key.") }
        return JSONObject().put("publicKey", java.util.Base64.getEncoder().encodeToString(key.raw)).put("fingerprint", key.fingerprint)
            .put("strongBox", key.strongBox).put("suite", "DHKEM(P-256,HKDF-SHA256)/HKDF-SHA256/AES-256-GCM")
    }

    fun start(args: JSONObject): JSONObject {
        requireOnly(args, setOf("goal", "taskId", "sealed", "publish", "signupMap", "signupRun"))
        val signup = args.opt("signupMap")?.let { raw ->
            val pkg = (raw as? String).orEmpty()
            if (!SIGNUP_PACKAGE.matches(pkg)) throw invalid("signupMap is the package of the app whose sign-up to map.")
            if (args.has("sealed") || args.has("publish")) throw invalid("A sign-up mapping task takes no sealed secrets or file to post.")
            if (!installed(pkg)) throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "$pkg is not installed on this phone.")
            pkg
        }
        val setup = args.opt("signupRun")?.let { raw ->
            val run = raw as? JSONObject ?: throw invalid("signupRun is {package, values}.")
            if (run.keys().asSequence().toSet() != setOf("package", "values")) throw invalid("signupRun is {package, values}.")
            val pkg = run.optString("package")
            if (!SIGNUP_PACKAGE.matches(pkg)) throw invalid("signupRun.package is an Android package name.")
            if (signup != null || args.has("publish")) throw invalid("An Account Setup run is not a mapping task or a post.")
            val values = com.cyclone.mobile.mind.signup.AccountSetupPlan.values(run.optJSONObject("values"))
                ?: throw invalid("signupRun.values is field key -> text.")
            if (!installed(pkg)) throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "$pkg is not installed on this phone.")
            val map = signupMap(pkg) ?: throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "This phone has no sign-up map for $pkg. Map the sign-up first.")
            com.cyclone.mobile.mind.signup.AccountSetupPlan(map, values)
        }
        val publish = args.opt("publish")
        if (publish != null && publish != true) throw invalid("publish is true or left out.")
        val goal = (args.opt("goal") as? String)?.trim().orEmpty()
        if (goal.isBlank() || goal.length > MAX_GOAL) throw invalid("goal must be 1..$MAX_GOAL characters of text.")
        if (INLINE_SECRET.containsMatchIn(goal)) throw invalid("Do not put secrets in a task; Cyclone asks on the phone.")
        if (!overlayReady()) throw GatewayProtocolException("OVERLAY_UNAVAILABLE", "Turn on Cyclone's accessibility service on the phone.")
        if (humanHasControl()) throw GatewayProtocolException("HUMAN_HAS_CONTROL", "You have control of the phone. Give it back to Cyclone first.")
        if (busyFor(goal)) throw GatewayProtocolException("ASK_BUSY", "The phone is already running a task.")
        // Sealed secrets (C2) are checked and opened before the mission starts, all or nothing.
        val sealed = args.optJSONArray("sealed")
        val opened = if (sealed == null || sealed.length() == 0) emptyMap() else {
            if (sealed.length() > 2) throw invalid("At most two sealed secrets per task.")
            val taskId = (args.opt("taskId") as? String).orEmpty()
            try {
                SealedDelivery.open(taskId, (0 until sealed.length()).map { SealedDelivery.parse(sealed.getJSONObject(it)) })
            } catch (rejected: SealedDelivery.Rejected) {
                throw GatewayProtocolException("SEALED_REJECTED", "${rejected.code}:${rejected.leaseId}")
            }
        }
        val id = (if (setup != null) startSetup(goal, setup) else if (signup != null) startSignup(goal, signup) else start(goal)) ?: run {
            SealedDelivery.wipe(opened)
            throw GatewayProtocolException("ASK_BUSY", if (signup != null || setup != null)
                "Another task has the phone's screen. The sign-up starts when it ends (or stop it on the phone)."
            else "The phone is already running a mission.")
        }
        // C3: a task that posts a file gates its final Share/Post as a send, for this mission only.
        PublishGate.mark(id, publish == true)
        if (opened.isNotEmpty()) {
            SealedDelivery.hold(id, opened)
            synchronized(missionLeases) { missionLeases[id] = opened.values.map { it.first.leaseId } }
        }
        return JSONObject().put("accepted", true).put("missionId", id)
    }

    fun status(args: JSONObject): JSONObject {
        val id = missionId(args, setOf("missionId"))
        val running = running(id)
        val mission = running ?: load(id) ?: throw GatewayProtocolException("RUN_NOT_FOUND", "No such mission.")
        val open = momentFor(id)?.takeIf { running != null && it.taskId == "mission-$id" }
        return JSONObject()
            .put("missionId", id)
            .put("status", mission.status.name.lowercase())
            .put("live", running != null)
            .put("turns", mission.turns)
            .put("workingMs", mission.workingMs)
            .put("costUsd", mission.usage.costUsd)
            .put("summary", MindRedaction.scrubText(mission.summary).take(600))
            .put("moment", open?.let(::momentJson) ?: JSONObject.NULL)
            .put("leases", leasesJson(id, finished = running == null))
            .also { out -> com.cyclone.mobile.mind.signup.AccountSetupProgress.of(id)?.let { out.put("setup", it.toJson()) } }
    }

    /** Each delivered lease's outcome (delivered, used, failed, expired, unused). A finished mission forgets its values. */
    private fun leasesJson(missionId: String, finished: Boolean): JSONArray {
        val ids = synchronized(missionLeases) { missionLeases[missionId] } ?: return JSONArray()
        if (finished) SealedDelivery.finish(missionId)
        return JSONArray().also { out -> SealedDelivery.outcomes(ids).forEach { (id, state) -> out.put(JSONObject().put("leaseId", id).put("state", state)) } }
    }

    fun answer(args: JSONObject): JSONObject {
        requireOnly(args, setOf("missionId", "requestId", "action", "text", "values"))
        val id = missionId(args, null)
        val action = (args.opt("action") as? String).orEmpty()
        if (action !in ANSWERS) throw invalid("action must be one of ${ANSWERS.joinToString()}.")
        running(id) ?: throw GatewayProtocolException("RUN_NOT_FOUND", "That mission is not running.")
        if (action == "stop") return result(send("mission-$id", TaskCommand.Stop))

        // Every other answer is to one open moment, named by its request id, so a changed request is never answered.
        val requestId = (args.opt("requestId") as? String).orEmpty()
        if (!REQUEST_ID.matches(requestId)) throw invalid("requestId is required.")
        val open = momentFor(id)?.takeIf { it.taskId == "mission-$id" && it.requestId == requestId }
            ?: throw GatewayProtocolException("MOMENT_CHANGED", "Cyclone is not waiting for that any more.")
        if (open.kind == MomentKind.SECRET || open.kind == MomentKind.HANDOVER) {
            throw GatewayProtocolException("ANSWER_ON_PHONE", "Secure input and taking over happen on the phone.")
        }
        val command = when (action) {
            "approve" -> {
                if (open.kind != MomentKind.APPROVAL) throw invalid("Nothing is waiting for approval.")
                if (!approvableHere(open)) throw GatewayProtocolException("ANSWER_ON_PHONE", "Approve this one on the phone; part of it is hidden here.")
                TaskCommand.Approve
            }
            "decline" -> TaskCommand.Decline
            "reply" -> (args.opt("text") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 500 }
                ?.also { if (INLINE_SECRET.containsMatchIn(it)) throw invalid("Do not put secrets in an answer.") }
                ?.let { TaskCommand.Reply(it) } ?: throw invalid("reply needs text of 1..500 characters.")
            else -> {
                if (open.kind != MomentKind.VALUES) throw invalid("Cyclone is not asking for details.")
                TaskCommand.Fill(values(args.optJSONObject("values")), remember = false)
            }
        }
        return result(send("mission-$id", command))
    }

    /**
     * The owner may approve at the PC only what the PC can show in full: the moment's text and a send's exact
     * message, recipient and app, with nothing redacted on the way.
     */
    internal fun approvableHere(moment: OwnerMoment): Boolean {
        if (moment.kind != MomentKind.APPROVAL) return false
        val shown = listOfNotNull(moment.text, moment.send?.text, moment.send?.recipient, moment.send?.app)
        return shown.all { MindRedaction.scrubText(it) == it && it.length <= 1_000 }
    }

    /** What the PC needs to show and answer a moment. Never includes typed values. */
    internal fun momentJson(moment: OwnerMoment): JSONObject = JSONObject()
        .put("kind", moment.kind.name.lowercase())
        .put("requestId", moment.requestId ?: JSONObject.NULL)
        .put("text", MindRedaction.scrubText(moment.text).take(1_000))
        .put("gate", moment.gate?.take(40) ?: JSONObject.NULL)
        .put("send", moment.send?.let {
            JSONObject().put("text", MindRedaction.scrubText(it.text).take(1_000)).put("recipient", MindRedaction.scrubText(it.recipient).take(200))
                .put("app", it.app.take(120))
        } ?: JSONObject.NULL)
        .put("choices", JSONArray(moment.choices.take(6).map { it.take(80) }))
        .put("fields", JSONArray().also { out ->
            moment.fields.take(8).forEach { out.put(JSONObject().put("label", it.label.take(60)).put("kind", it.kind.take(20))) }
        })
        .put("approvableHere", approvableHere(moment))

    private fun result(result: TaskCommandResult) = JSONObject().put("handled", result.handled).put("detail", result.detail.take(200))

    private fun values(json: JSONObject?): Map<String, String> {
        json ?: throw invalid("fill needs values.")
        val out = linkedMapOf<String, String>()
        json.keys().forEach { key ->
            val value = json.opt(key) as? String ?: throw invalid("fill values must be text.")
            if (key.length > 60 || value.length > 300) throw invalid("fill values are too long.")
            if (INLINE_SECRET.containsMatchIn("$key: $value") || INLINE_SECRET.containsMatchIn("$key=")) throw invalid("Secrets are typed on the phone, never sent from the PC.")
            if (value.isNotBlank()) out[key] = value.trim()
        }
        if (out.isEmpty() || out.size > 8) throw invalid("fill needs 1..8 values.")
        return out
    }

    private fun missionId(args: JSONObject, only: Set<String>?): String {
        only?.let { requireOnly(args, it) }
        val id = (args.opt("missionId") as? String).orEmpty()
        if (!MISSION_ID.matches(id)) throw invalid("missionId is malformed.")
        return id
    }

    private fun invalid(message: String) = GatewayProtocolException("INVALID_REQUEST", message)

    private fun requireOnly(args: JSONObject, allowed: Set<String>) {
        if (args.keys().asSequence().any { it !in allowed }) throw invalid("Unexpected Command Center field.")
    }
}
