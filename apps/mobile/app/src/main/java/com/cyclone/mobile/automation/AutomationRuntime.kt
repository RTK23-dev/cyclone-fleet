package com.cyclone.mobile.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.cyclone.mobile.BridgeClient
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest as NativePhoneToolRequest
import com.cyclone.mobile.automation.stock.instagram.InstagramStockSkillCollectionGateway
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object AutomationRuntime {
    @Volatile private var initialized = false
    lateinit var store: AutomationStore
        private set
    lateinit var recorder: AutomationRecorder
        private set
    lateinit var router: AutomationEventRouter
        private set

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        store = AutomationStore(app)
        recorder = AutomationRecorder()
        val runner = AutomationRunner(
            store = store,
            phoneTools = CyclonePhoneToolAdapter(app),
            integrations = CycloneIntegrationGateway(app),
            confirmations = ConfirmationGateway { _, _ -> false },
            takeover = TakeoverGateway { reason, runId, stepId ->
                DeviceState.setController(DeviceState.Controller.HUMAN)
                DeviceState.addLog("Automation takeover required run=$runId step=$stepId reason=$reason")
                true
            },
            stockSkills = InstagramStockSkillCollectionGateway(app),
        )
        router = AutomationEventRouter(store, runner)
        seedStockSkills()
        seedExamples()
        initialized = true
        store.listAutomations().filter { it.enabled && it.trigger.type == TriggerType.SCHEDULE }.forEach { registerSchedule(app, it) }
    }

    fun importAiProposal(context: Context, document: JSONObject): Result<AutomationDefinition> {
        initialize(context)
        return runCatching {
            AutomationProposalCompiler.compile(document).also { compiled ->
                check(!compiled.enabled) { "AI workflow proposals must remain disabled until review" }
                store.saveAutomation(compiled)
                DeviceState.addLog("AI workflow proposal compiled and saved disabled: ${compiled.name}")
            }
        }.onFailure { DeviceState.addLog("AI workflow proposal rejected: ${it.message}") }
    }

    fun onNotification(context: Context, packageName: String, title: String, text: String) {
        initialize(context)
        router.emit(TriggerEvent(TriggerType.NOTIFICATION, mapOf("package" to packageName, "title" to title, "text" to text)))
    }

    fun onAppOpened(context: Context, packageName: String) {
        initialize(context)
        recorder.recordAppOpened(packageName)
        router.emit(TriggerEvent(TriggerType.APP_OPENED, mapOf("package" to packageName)))
    }

    fun onCycloneRemote(context: Context, payload: Map<String, String>) {
        initialize(context)
        router.emit(TriggerEvent(TriggerType.CYCLONE_REMOTE, payload))
    }

    fun onWebSocketEvent(context: Context, eventType: String, payload: Map<String, String>) {
        initialize(context)
        router.emit(TriggerEvent(TriggerType.WEBSOCKET, payload + ("eventType" to eventType)))
    }

    fun onCalendarTime(context: Context, payload: Map<String, String>) {
        initialize(context)
        router.emit(TriggerEvent(TriggerType.CALENDAR_TIME, payload))
    }

    fun schedule(context: Context, automationId: String, atMillis: Long) {
        initialize(context)
        scheduleInternal(context.applicationContext, automationId, atMillis)
    }

    fun registerSchedule(context: Context, automation: AutomationDefinition) {
        initialize(context)
        if (!automation.enabled || automation.trigger.type != TriggerType.SCHEDULE) {
            cancelSchedule(context, automation.id)
            return
        }
        val now = System.currentTimeMillis()
        val configuredAt = automation.trigger.parameters["atMillis"]?.toLongOrNull()
        val interval = automation.trigger.parameters["intervalMs"]?.toLongOrNull()?.takeIf { it > 0 }
        val next = configuredAt?.takeIf { it > now } ?: interval?.let { now + it }
        if (next != null) scheduleInternal(context.applicationContext, automation.id, next)
    }

    fun cancelSchedule(context: Context, automationId: String) {
        val app = context.applicationContext
        val intent = Intent(app, AutomationAlarmReceiver::class.java).putExtra("automationId", automationId)
        val pending = PendingIntent.getBroadcast(app, automationId.hashCode(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pending != null) {
            app.getSystemService(AlarmManager::class.java).cancel(pending)
            pending.cancel()
        }
    }

    fun emitScheduled(context: Context, automationId: String) {
        initialize(context)
        val automation = store.getAutomation(automationId) ?: return
        if (!automation.enabled || automation.trigger.type != TriggerType.SCHEDULE) {
            cancelSchedule(context, automationId)
            return
        }
        router.emit(TriggerEvent(TriggerType.SCHEDULE, mapOf("automationId" to automationId)))
        val interval = automation.trigger.parameters["intervalMs"]?.toLongOrNull()?.takeIf { it > 0 } ?: return
        scheduleInternal(context.applicationContext, automationId, System.currentTimeMillis() + interval)
    }

    private fun scheduleInternal(context: Context, automationId: String, atMillis: Long) {
        val intent = Intent(context, AutomationAlarmReceiver::class.java).putExtra("automationId", automationId)
        val pending = PendingIntent.getBroadcast(context, automationId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis.coerceAtLeast(System.currentTimeMillis() + 1_000), pending)
    }

    private fun seedStockSkills() {
        InstagramStockSkillCollectionGateway.definitions.forEach { definition ->
            val existing = store.getSkill(definition.id)
            if (existing == null || existing.version < definition.version) store.saveSkill(definition)
        }
        InstagramStockSkillCollectionGateway.routines().forEach { routine ->
            val existing = store.getAutomation(routine.id)
            if (existing == null || existing.version < routine.version) store.saveAutomation(routine)
        }
    }

    private fun seedExamples() {
        if (store.listAutomations().isNotEmpty()) return
        store.saveAutomation(
            AutomationDefinition(
                id = "example-open-settings",
                name = "Open Android Settings",
                description = "Harmless manual example showing a deterministic phone-tool workflow.",
                enabled = true,
                trigger = TriggerDefinition(TriggerType.MANUAL),
                steps = listOf(StepDefinition(id = "open-settings", name = "Open Settings", type = StepType.PHONE_TOOL,
                    parameters = mapOf("tool" to "phone.open_app", "package" to "com.android.settings"), recovery = RecoveryPolicy(maxRetries = 1)))
            )
        )
        store.saveAutomation(
            AutomationDefinition(
                id = "example-work-shift-template",
                name = "Work shift notification template",
                description = "Disabled generic template. Configure the target app, parsing, verification and claim skill before enabling.",
                enabled = false,
                trigger = TriggerDefinition(TriggerType.NOTIFICATION, mapOf("package" to "your.work.app")),
                steps = listOf(
                    StepDefinition("extract-shift", "Extract shift", StepType.REGEX_EXTRACT, mapOf("source" to "${'$'}text", "pattern" to "(\\d{1,2}[:.]\\d{2}\\s*(?:-|–|—|to)\\s*\\d{1,2}[:.]\\d{2})", "group" to "1", "target" to "shift")),
                    StepDefinition("open-work-app", "Open work app", StepType.PHONE_TOOL, mapOf("tool" to "phone.open_notification")),
                    StepDefinition("claim-skill", "Invoke configured claim skill", StepType.INVOKE_SKILL, mapOf("skillId" to "configure-me"), confirmationRequired = true)
                )
            )
        )
    }
}

class AutomationAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("automationId")?.let { AutomationRuntime.emitScheduled(context, it) }
    }
}

/** Agent 2's deterministic workflow API mapped directly onto Agent 1's authoritative phone executor. */
class CyclonePhoneToolAdapter(private val context: Context) : PhoneToolGateway {
    override fun execute(request: PhoneToolRequest): PhoneToolResult {
        val params = JSONObject()
        request.arguments.forEach { (key, value) -> params.put(key, scalar(value)) }
        if ((request.name == "phone.type" || request.name == "phone.replace_text") && !params.has("value") && params.has("text")) {
            params.put("value", params.optString("text"))
        }
        request.selector?.let { params.put("selector", selectorJson(it)) }
        val native = PhoneToolExecutor.execute(
            context,
            NativePhoneToolRequest("automation-${UUID.randomUUID()}", request.name, params)
        )
        return PhoneToolResult(
            success = native.ok,
            output = payloadMap(native.payload),
            errorCode = native.error?.code?.name,
            message = native.error?.message,
        )
    }

    private fun scalar(value: String): Any = when {
        value.equals("true", true) -> true
        value.equals("false", true) -> false
        value.toLongOrNull() != null -> value.toLong()
        value.toDoubleOrNull() != null -> value.toDouble()
        else -> value
    }

    private fun selectorJson(selector: Selector): JSONObject = JSONObject().apply {
        selector.resourceId?.let { put("resourceId", it) }
        selector.text?.let { put("text", it) }
        selector.partialText?.let { put("textContains", it) }
        selector.contentDescription?.let { put("contentDescription", it) }
        selector.contentDescriptionContains?.let { put("contentDescriptionContains", it) }
        selector.role?.let { put("role", it) }
        selector.className?.let { put("class", it) }
        selector.ancestor?.let { put("ancestorText", it) }
        selector.descendant?.let { put("descendantText", it) }
        selector.relativeToText?.let { put("relativeToText", it) }
        selector.relativeDirection?.let { put("relativeDirection", it) }
        selector.fuzzyText?.let { put("fuzzyText", it) }
        selector.minFuzzyScore?.let { put("minFuzzyScore", it) }
        selector.requireClickable?.let { put("clickable", it) }
        selector.requireEditable?.let { put("editable", it) }
        selector.requireScrollable?.let { put("scrollable", it) }
        selector.x?.let { put("x", it) }
        selector.y?.let { put("y", it) }
        selector.relativePosition?.let { raw ->
            val pieces = raw.split(':', limit = 2)
            if (pieces.size == 2) {
                if (!has("relativeDirection")) put("relativeDirection", pieces[0])
                if (!has("relativeToText")) put("relativeToText", pieces[1])
            }
        }
    }

    private fun payloadMap(payload: Any?): Map<String, String> = when (payload) {
        null, JSONObject.NULL -> emptyMap()
        is JSONObject -> buildMap {
            payload.keys().forEach { key -> payload.opt(key)?.takeIf { it !== JSONObject.NULL }?.let { put(key, it.toString()) } }
            putIfAbsent("json", payload.toString())
        }
        is JSONArray -> mapOf("json" to payload.toString())
        else -> mapOf("value" to payload.toString())
    }
}

private class CycloneIntegrationGateway(private val context: Context) : IntegrationGateway {
    /** A saved skill runs through the same entry as a typed Ask (Marketplace.run): GATE, approvals, Secrets Card. */
    override fun runGroundedSkill(skillId: String): PhoneToolResult {
        val refusal = com.cyclone.mobile.market.Marketplace.run(context, skillId)
            ?: return PhoneToolResult(true, output = mapOf("skillStarted" to skillId), message = "skill_started")
        return PhoneToolResult(false, errorCode = refusal.code, message = refusal.message)
    }

    override fun refreshObservation(): Boolean = PhoneToolExecutor.execute(
        context, NativePhoneToolRequest("automation-observe-${UUID.randomUUID()}", "phone.observe", JSONObject())
    ).ok

    override fun goBack(): Boolean = PhoneToolExecutor.execute(
        context, NativePhoneToolRequest("automation-back-${UUID.randomUUID()}", "phone.back", JSONObject())
    ).ok

    override fun restartApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return PhoneToolExecutor.execute(
            context, NativePhoneToolRequest("automation-open-${UUID.randomUUID()}", "phone.open_app", JSONObject().put("package", packageName))
        ).ok
    }

    override fun sendCycloneEvent(type: String, payload: Map<String, String>): PhoneToolResult =
        if (BridgeClient.sendAutomationEvent(type, payload)) PhoneToolResult(true, mapOf("event" to type))
        else PhoneToolResult(false, errorCode = "LEGACY_INTEGRATION_RETIRED", message = "This routine uses the retired Core/Hermes integration. Rebuild it with local phone actions.")
}
