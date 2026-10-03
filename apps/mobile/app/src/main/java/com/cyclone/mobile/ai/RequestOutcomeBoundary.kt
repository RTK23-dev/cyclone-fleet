package com.cyclone.mobile.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Every entrypoint retains its report identity through validation, cancellation and cleanup. */
internal object RequestOutcomeBoundary {
    suspend fun run(id: String, model: String, finish: (QuickAgentResult) -> Unit,
        block: suspend () -> QuickAgentResult): QuickAgentResult {
        val result = try {
            currentCoroutineContext().ensureActive()
            block().also { currentCoroutineContext().ensureActive() }
        } catch (cancelled: CancellationException) {
            finish(QuickAgentResult(false, "Request cancelled by Stop or task shutdown.", 0, model,
                taskId = id, classification = "CANCELLED"))
            throw cancelled
        } catch (error: Exception) {
            // Exception messages can contain credentials, prompts or provider payloads.
            QuickAgentResult(false, "Request failed during startup or execution (${error.javaClass.simpleName}). Open Outcomes for the report.",
                0, model, taskId = id, classification = "HARD_BLOCKER")
        }
        val identified = result.copy(taskId = id)
        if (identified.classification != "HUMAN_OR_GATE") finish(identified)
        return identified
    }
}
