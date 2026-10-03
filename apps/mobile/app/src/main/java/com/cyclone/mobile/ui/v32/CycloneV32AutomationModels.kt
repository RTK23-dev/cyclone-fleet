package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.RecoveryPolicy
import com.cyclone.mobile.automation.StepDefinition
import com.cyclone.mobile.automation.StepType
import com.cyclone.mobile.automation.TriggerDefinition
import com.cyclone.mobile.automation.TriggerType
import java.util.UUID

enum class V32TriggerChoice(
    val label: String,
    val description: String,
    val type: TriggerType,
) {
    ONE_TAP("One tap", "Run it yourself whenever you need it.", TriggerType.MANUAL),
    NOTIFICATION("Notification received", "React to an alert from an app.", TriggerType.NOTIFICATION),
    SCHEDULE("At a time", "Run once or on a simple repeat schedule.", TriggerType.SCHEDULE),
    APP_OPENED("App opened", "Start when you enter a chosen app.", TriggerType.APP_OPENED),
    CALENDAR("Calendar event", "Start from an approved calendar event.", TriggerType.CALENDAR_TIME),
    CYCLONE("Cyclone or Codex", "Start from your constrained Cyclone connection.", TriggerType.CYCLONE_REMOTE),
}

enum class V32ActionChoice(val label: String, val description: String) {
    RUN_SKILL("Run one of your skills", "Runs a saved skill. It finds its way on the app's map and finishes with the same checks as an Ask."),
    OPEN_APP("Open an app", "Launch a package on this phone."),
    HOME("Go Home", "Return to the Android home screen."),
    BACK("Go Back", "Navigate back one screen."),
    WAIT("Wait a moment", "Pause briefly before the next action."),
    HUMAN("Ask me to continue", "Pause safely and hand control to you."),
}

data class V32ActionDraft(
    val id: String = UUID.randomUUID().toString(),
    val choice: V32ActionChoice,
    val value: String = "",
) {
    fun toStep(): StepDefinition = when (choice) {
        V32ActionChoice.RUN_SKILL -> com.cyclone.mobile.automation.RoutineGrounding.skillStep(id, value.trim())
        V32ActionChoice.OPEN_APP -> StepDefinition(
            id = id,
            name = "Open ${value.trim()}",
            type = StepType.PHONE_TOOL,
            parameters = mapOf("tool" to "phone.open_app", "package" to value.trim()),
            recovery = RecoveryPolicy(maxRetries = 1),
        )
        V32ActionChoice.HOME -> StepDefinition(id, "Go Home", StepType.PHONE_TOOL, mapOf("tool" to "phone.home"))
        V32ActionChoice.BACK -> StepDefinition(id, "Go Back", StepType.PHONE_TOOL, mapOf("tool" to "phone.back"))
        V32ActionChoice.WAIT -> StepDefinition(
            id,
            "Wait a moment",
            StepType.DELAY,
            mapOf("ms" to (value.toLongOrNull()?.coerceIn(250, 60_000) ?: 1_000).toString()),
        )
        V32ActionChoice.HUMAN -> StepDefinition(
            id,
            "Ask me to continue",
            StepType.REQUEST_HUMAN_TAKEOVER,
            mapOf("reason" to value.trim().ifBlank { "Routine needs your confirmation" }),
            confirmationRequired = true,
        )
    }

    fun validationIssue(): String? = when {
        choice == V32ActionChoice.RUN_SKILL && !value.trim().startsWith("you.") -> "Choose one of your skills."
        choice == V32ActionChoice.OPEN_APP && value.trim().isBlank() -> "Choose an app package."
        choice == V32ActionChoice.OPEN_APP && !PACKAGE_PATTERN.matches(value.trim()) -> "Use an Android package such as com.example.app."
        choice == V32ActionChoice.WAIT && value.isNotBlank() && value.toLongOrNull() == null -> "Wait time must be milliseconds."
        else -> null
    }

    companion object {
        private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    }
}

data class V32AutomationDraft(
    val name: String = "",
    val trigger: V32TriggerChoice = V32TriggerChoice.ONE_TAP,
    val sourcePackage: String = "",
    val containsText: String = "",
    val scheduledAtMillis: Long? = null,
    val scheduleRepeatMillis: Long? = null,
    val calendarName: String = "",
    val remoteKey: String = "",
    val actions: List<V32ActionDraft> = emptyList(),
) {
    fun validationIssues(nowMillis: Long = System.currentTimeMillis()): List<String> = buildList {
        if (name.trim().isBlank()) add("Give the routine a name.")
        when (trigger) {
            V32TriggerChoice.NOTIFICATION, V32TriggerChoice.APP_OPENED -> if (sourcePackage.trim().isBlank()) add("Choose the source app.")
            V32TriggerChoice.SCHEDULE -> if (scheduledAtMillis == null || scheduledAtMillis <= nowMillis) add("Choose a future time.")
            V32TriggerChoice.CALENDAR -> if (calendarName.trim().isBlank()) add("Choose a calendar.")
            V32TriggerChoice.CYCLONE -> if (remoteKey.trim().isBlank()) add("Add a connection event key.")
            V32TriggerChoice.ONE_TAP -> Unit
        }
        if (actions.isEmpty()) add("Add at least one action.")
        actions.mapNotNullTo(this) { it.validationIssue() }
    }.distinct()

    fun toAutomation(nowMillis: Long = System.currentTimeMillis()): AutomationDefinition {
        val issues = validationIssues(nowMillis)
        require(issues.isEmpty()) { issues.joinToString(" ") }
        return AutomationDefinition(
            name = name.trim(),
            description = readableSummary(),
            enabled = true,
            trigger = TriggerDefinition(trigger.type, triggerParameters()),
            steps = actions.map(V32ActionDraft::toStep),
        )
    }

    fun readableSummary(): String = "${triggerSummary()} → ${actions.size} ${if (actions.size == 1) "action" else "actions"}"

    fun triggerSummary(): String = when (trigger) {
        V32TriggerChoice.ONE_TAP -> "When you tap Run"
        V32TriggerChoice.NOTIFICATION -> buildString {
            append("When ${sourcePackage.trim().ifBlank { "an app" }} sends a notification")
            if (containsText.isNotBlank()) append(" containing “${containsText.trim()}”")
        }
        V32TriggerChoice.SCHEDULE -> "At the chosen time"
        V32TriggerChoice.APP_OPENED -> "When ${sourcePackage.trim().ifBlank { "an app" }} opens"
        V32TriggerChoice.CALENDAR -> "When ${calendarName.trim().ifBlank { "a calendar" }} matches"
        V32TriggerChoice.CYCLONE -> "When Cyclone receives ${remoteKey.trim().ifBlank { "the event" }}"
    }

    private fun triggerParameters(): Map<String, String> = buildMap {
        when (trigger) {
            V32TriggerChoice.ONE_TAP -> Unit
            V32TriggerChoice.NOTIFICATION -> {
                put("package", sourcePackage.trim())
                if (containsText.isNotBlank()) put("text", containsText.trim())
            }
            V32TriggerChoice.SCHEDULE -> {
                put("atMillis", requireNotNull(scheduledAtMillis).toString())
                scheduleRepeatMillis?.takeIf { it > 0 }?.let { put("intervalMs", it.toString()) }
            }
            V32TriggerChoice.APP_OPENED -> put("package", sourcePackage.trim())
            V32TriggerChoice.CALENDAR -> put("calendar", calendarName.trim())
            V32TriggerChoice.CYCLONE -> put("key", remoteKey.trim())
        }
    }
}

fun V32AutomationDraft.toAutomationForDevice(
    notificationAccess: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
): AutomationDefinition {
    val built = toAutomation(nowMillis)
    return if (built.trigger.type == TriggerType.NOTIFICATION && !notificationAccess) built.copy(enabled = false) else built
}

fun AutomationDefinition.v32TriggerSummary(): String = when (trigger.type) {
    TriggerType.MANUAL -> "One tap"
    TriggerType.NOTIFICATION -> "Notification from ${trigger.parameters["package"].orEmpty().ifBlank { "any app" }}"
    TriggerType.SCHEDULE -> "Scheduled time"
    TriggerType.APP_OPENED -> "${trigger.parameters["package"].orEmpty().ifBlank { "App" }} opened"
    TriggerType.CALENDAR_TIME -> "Calendar event"
    TriggerType.CYCLONE_REMOTE -> "Cyclone or Codex"
    TriggerType.WEBSOCKET -> "Connected event"
}

fun StepDefinition.v32ReadableName(): String = name.ifBlank {
    when (type) {
        StepType.PHONE_TOOL -> parameters["tool"].orEmpty().removePrefix("phone.").replace('_', ' ').ifBlank { "Phone action" }
        StepType.DELAY -> "Wait"
        StepType.REQUEST_HUMAN_TAKEOVER -> "Ask for help"
        StepType.RUN_GROUNDED_SKILL -> "Run skill"
        else -> type.name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)
    }
}

/** Human-readable step line that never includes passwords, tokens, keys or typed values. */
fun StepDefinition.v32SafeSummary(): String {
    val tool = parameters["tool"]?.removePrefix("phone.")?.replace('_', ' ')
    val target = selector?.text?.takeIf(String::isNotBlank)
        ?: selector?.contentDescription?.takeIf(String::isNotBlank)
        ?: selector?.resourceId?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        ?: selector?.role?.takeIf(String::isNotBlank)
    val safe = parameters.filterKeys(::v32SafeParameterKey)
        .filterKeys { it != "tool" }
        .entries.take(3)
        .joinToString(" · ") { (key, value) -> "${key.replace('_', ' ')}=$value" }
    return buildString {
        append(tool?.replaceFirstChar(Char::uppercase) ?: type.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase))
        target?.let { append(" → $it") }
        if (safe.isNotBlank()) append(" · $safe")
    }
}

private fun v32SafeParameterKey(key: String): Boolean {
    val normalized = key.lowercase()
    return listOf("password", "secret", "token", "api_key", "apikey", "credential", "value", "text").none { it in normalized }
}
