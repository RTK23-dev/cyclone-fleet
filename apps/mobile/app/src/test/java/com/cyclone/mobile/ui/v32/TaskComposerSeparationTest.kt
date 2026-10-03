package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TaskComposerSeparationTest {
    private fun source(path: String) = sequenceOf(File("src/main/java/com/cyclone/mobile/$path"),
        File("apps/mobile/app/src/main/java/com/cyclone/mobile/$path")).first { it.isFile }.readText()

    @Test fun oneComposerDoesNotDoubleAsCurrentTaskStopOrManualModeSwitch() {
        val chat = source("ui/v32/CycloneV39AiChatPage.kt")
        val overlay = source("ui/overlay/OverlayChrome.kt")
        assertFalse(chat.contains("if (session.busy) agent.cancelActiveTask() else submit()"))
        assertFalse(overlay.contains("snapshot.composerText.isBlank()) WorkspaceTasks.command"))
        assertTrue(chat.contains("CyclonePendingRequests()"))
        assertTrue(overlay.contains("CyclonePendingRequests"))
        assertFalse(chat.contains("Text(\"New request\""))
        assertFalse(chat.contains("listOf(\"Chat\", \"Phone task\")"))
        assertTrue(chat.contains("TextButton(onClick = { chatJob?.cancel() })"))
        // The composer does not need a redundant task-mode header.
        assertFalse(overlay.contains("\"New phone task\""))
    }

    @Test fun ordinaryConversationHasNoPhoneActionCapability() {
        val chat = source("ai/CycloneTextChat.kt")
        assertFalse(chat.contains("PhoneToolExecutor"))
        assertFalse(chat.contains("OpenRouterAdaptiveAgent"))
        assertFalse(chat.contains("phone.observe"))
        assertFalse(chat.contains("put(\"tools\""))
        assertTrue(chat.contains("ProviderRequests.executeAsync"))
        assertTrue(source("ai/ProviderRequestLifecycle.kt").contains("invokeOnCancellation { context.cancellation.cancel() }"))
    }

    @Test fun autoRouterRunsBeforePhoneReadinessAndChatDoesNotQueue() {
        val chat = source("ui/v32/CycloneV39AiChatPage.kt")
        assertTrue(chat.indexOf("RequestIntentRouter.route(normalized") < chat.indexOf("WorkspaceTasks.canStartRequest()"))
        val chatDispatch = chat.indexOf("RequestDispatch.CHAT ->")
        val queueDispatch = chat.indexOf("RequestDispatch.QUEUE_PHONE_TASK ->")
        assertTrue(chatDispatch >= 0)
        assertTrue(queueDispatch >= 0)
        val chatBlock = chat.substring(chatDispatch, chat.length)
            .substringBefore("RequestDispatch.START_PHONE_TASK")
        assertFalse(chatBlock.contains("queueRequest"))
    }

    @Test fun queuedRequestsCannotEvictTheCurrentTaskOrBypassGate() {
        val state = source("runtime/background/WorkspaceTaskState.kt")
        assertTrue(state.contains("check(canStartRequest())"))
        assertTrue(state.contains("Layer2Workspaces.gated()"))
        assertTrue(state.contains("pendingRequestId?.let(requests::remove)"))
        assertTrue(source("runtime/background/WorkspaceTaskService.kt").contains("WorkspaceTasks.takeAttachment(task.taskId)"))
    }
}
