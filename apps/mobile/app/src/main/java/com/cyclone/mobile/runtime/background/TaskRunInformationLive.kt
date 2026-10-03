package com.cyclone.mobile.runtime.background

import com.cyclone.mobile.ai.AgentRunDiagnosticV39
import com.cyclone.mobile.ai.AgentTraceRuntime

/** Android-backed metrics for the Ask card. JVM tests use [TaskRunInformationProjector] directly. */
object TaskRunInformationLive {
    fun load(task: WorkspaceTaskUi): TaskRunInformation {
        val base = TaskRunInformationProjector.fromTask(task)
        val sessionId = task.traceSessionId?.takeIf { it.isNotBlank() } ?: return base
        val ready = runCatching { AgentTraceRuntime.store }.getOrNull() ?: return base
        val session = runCatching { ready.session(sessionId) }.getOrNull() ?: return base
        val events = runCatching { ready.events(session.id) }.getOrNull().orEmpty()
        val metrics = AgentRunDiagnosticV39.metrics(events)
        val usage = parseUsage(events.mapNotNull { it.detail })
        return TaskRunInformationProjector.combine(
            task = task,
            modelName = session.model,
            startedAtMs = session.startedAt,
            endedAtMs = session.endedAt,
            modelRequests = session.decisions.takeIf { it > 0 } ?: metrics.modelContextSnapshots.takeIf { it > 0 },
            toolActions = metrics.toolCalls.takeIf { it > 0 } ?: metrics.executorInvocations.takeIf { it > 0 },
            verifiedActions = metrics.verifiedActions.takeIf { it > 0 },
            tokensInput = usage.first,
            tokensOutput = usage.second,
        )
    }

    internal fun parseUsage(details: List<String>): Pair<Long?, Long?> {
        var input: Long? = null
        var output: Long? = null
        details.forEach { detail ->
            Regex("prompt_tokens[=:]\\s*(\\d+)").find(detail)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?.let { input = (input ?: 0L) + it }
            Regex("completion_tokens[=:]\\s*(\\d+)").find(detail)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?.let { output = (output ?: 0L) + it }
        }
        return input to output
    }
}
