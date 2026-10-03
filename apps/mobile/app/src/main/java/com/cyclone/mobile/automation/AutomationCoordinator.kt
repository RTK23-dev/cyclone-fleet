package com.cyclone.mobile.automation

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AutomationEventRouter(
    private val store: AutomationStore,
    private val runner: AutomationRunner,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) {
    fun emit(event: TriggerEvent) {
        store.listAutomations()
            .asSequence()
            .filter { it.enabled && triggerMatches(it, event) }
            .forEach { automation -> executor.submit { runner.run(automation, event) } }
    }

    fun runManual(automationId: String) {
        val automation = store.getAutomation(automationId) ?: return
        executor.submit { runner.run(automation, TriggerEvent(TriggerType.MANUAL, mapOf("automationId" to automationId))) }
    }

    fun resume(runId: String) {
        val checkpoint = store.getCheckpoint(runId) ?: return
        val automation = store.getAutomation(checkpoint.automationId) ?: return
        executor.submit {
            runner.run(
                automation,
                TriggerEvent(TriggerType.CYCLONE_REMOTE, mapOf("resumeRunId" to runId)),
                checkpoint
            )
        }
    }

    fun shutdown() = executor.shutdownNow()

    private fun triggerMatches(automation: AutomationDefinition, event: TriggerEvent): Boolean {
        val trigger = automation.trigger
        if (trigger.type != event.type) return false
        val expected = trigger.parameters
        return when (event.type) {
            TriggerType.MANUAL -> event.payload["automationId"]?.let { it == automation.id } ?: true
            TriggerType.NOTIFICATION -> matchesContains(expected["package"], event.payload["package"]) &&
                matchesContains(expected["title"], event.payload["title"]) &&
                matchesContains(expected["text"], event.payload["text"])
            TriggerType.SCHEDULE -> event.payload["automationId"] == automation.id
            TriggerType.APP_OPENED -> matchesContains(expected["package"], event.payload["package"])
            TriggerType.CYCLONE_REMOTE -> expected["key"]?.let { it == event.payload["key"] } ?: (event.payload["automationId"] == automation.id)
            TriggerType.WEBSOCKET -> expected["eventType"]?.let { it == event.payload["eventType"] } ?: true
            TriggerType.CALENDAR_TIME -> {
                matchesContains(expected["calendar"], event.payload["calendar"]) && matchesContains(expected["tag"], event.payload["tag"])
            }
        }
    }

    private fun matchesContains(expected: String?, actual: String?): Boolean = expected.isNullOrBlank() || actual.orEmpty().contains(expected, ignoreCase = true)
}

class AutomationRecorder {
    @Volatile private var recording = false
    private val steps = mutableListOf<StepDefinition>()
    private var lastPackage: String? = null

    @Synchronized fun start() { steps.clear(); lastPackage = null; recording = true }
    @Synchronized fun cancel() { recording = false; steps.clear(); lastPackage = null }
    fun isRecording(): Boolean = recording
    @Synchronized fun snapshot(): List<StepDefinition> = steps.toList()

    @Synchronized fun stop(name: String): AutomationDefinition {
        recording = false
        return AutomationDefinition(
            name = name,
            description = "Recorded on-device and normalized into reusable selectors where available.",
            trigger = TriggerDefinition(TriggerType.MANUAL),
            steps = steps.toList()
        )
    }

    @Synchronized fun recordAppOpened(packageName: String) {
        if (!recording || packageName == lastPackage) return
        lastPackage = packageName
        append(StepDefinition(name = "Open $packageName", type = StepType.PHONE_TOOL, parameters = mapOf("tool" to "phone.open_app", "package" to packageName)))
    }

    @Synchronized fun recordClick(selector: Selector) {
        if (!recording) return
        append(StepDefinition(name = "Click element", type = StepType.PHONE_TOOL, parameters = mapOf("tool" to "phone.click"), selector = selector))
    }

    @Synchronized fun recordText(selector: Selector, valuePlaceholder: String = "${'$'}{input}") {
        if (!recording) return
        append(StepDefinition(name = "Enter text", type = StepType.PHONE_TOOL, parameters = mapOf("tool" to "phone.type", "text" to valuePlaceholder), selector = selector))
    }

    @Synchronized fun recordScroll(direction: String = "forward") {
        if (!recording) return
        append(StepDefinition(name = "Scroll $direction", type = StepType.PHONE_TOOL, parameters = mapOf("tool" to "phone.scroll", "direction" to direction)))
    }

    @Synchronized fun recordBack() {
        if (recording) append(StepDefinition(name = "Back", type = StepType.PHONE_TOOL, parameters = mapOf("tool" to "phone.back")))
    }

    @Synchronized fun recordHome() {
        if (recording) append(StepDefinition(name = "Home", type = StepType.PHONE_TOOL, parameters = mapOf("tool" to "phone.home")))
    }

    @Synchronized fun removeStep(index: Int) { if (index in steps.indices) steps.removeAt(index) }
    @Synchronized fun moveStep(from: Int, to: Int) {
        if (from !in steps.indices || to !in steps.indices || from == to) return
        val item = steps.removeAt(from)
        steps.add(to, item)
    }

    private fun append(step: StepDefinition) {
        val previous = steps.lastOrNull()
        if (previous?.type == step.type && previous.parameters == step.parameters && previous.selector == step.selector) return
        steps.add(step)
    }
}
