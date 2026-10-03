package com.cyclone.mobile.owner

import com.cyclone.mobile.mind.mission.OwnerField
import com.cyclone.mobile.mind.mission.OwnerInbox
import com.cyclone.mobile.mind.mission.OwnerRequestKind
import com.cyclone.mobile.mind.mission.OwnerResponse
import com.cyclone.mobile.runtime.background.TaskInterruption
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceConfirmation
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.task.BackgroundWorkspaceTaskController
import com.cyclone.mobile.task.ClassicForegroundTaskController
import com.cyclone.mobile.task.MindTaskController
import com.cyclone.mobile.task.TaskCommand
import com.cyclone.mobile.task.TaskEngine
import com.cyclone.mobile.task.TaskEngines
import com.cyclone.mobile.ui.v32.OwnerCardCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerMomentsTest {
    private fun task(id: String, foreground: Boolean = true, phase: TaskPhase = TaskPhase.WORKING) = WorkspaceTaskUi(
        taskId = id, sessionId = if (foreground) "default-foreground" else "ws-1", app = "App", packageName = "com.example",
        goal = "goal", phase = phase, displayId = if (foreground) 0 else 7, workspaceId = if (foreground) null else "ws")

    private val inbox = OwnerInbox()
    private val mind = task("mission-m1", phase = TaskPhase.HUMAN)

    @Test fun mindRequestsBecomeMomentsWithTheTwoWaysForward() {
        val values = OwnerMoments.project(mind, inbox.post("m1", OwnerRequestKind.VALUES, "Sign-up needs your name",
            fields = listOf(OwnerField("First name", "name"))))!!
        assertEquals(MomentKind.VALUES, values.kind)
        assertEquals(listOf("Take over", "Fill in"), values.actions.map { it.label })
        assertTrue(values.actions.last().primary && values.actions.last().needsInput)
        assertEquals("First name", values.fields.single().label)
        assertTrue(OwnerMoments.overlayCard(values))

        val question = OwnerMoments.project(mind, inbox.post("m1", OwnerRequestKind.QUESTION, "Which account?", choices = listOf("Work")))!!
        assertEquals(listOf("Work"), question.choices)
        assertTrue(question.actions.any { it.command is TaskCommand.Reply })
        assertTrue(OwnerMoments.overlayCard(question))

        val approval = OwnerMoments.project(mind, inbox.post("m1", OwnerRequestKind.APPROVAL, "Send the message?"))!!
        assertEquals(listOf<TaskCommand>(TaskCommand.Decline, TaskCommand.Approve), approval.actions.map { it.command })
        assertFalse(OwnerMoments.overlayCard(approval))

        val secret = OwnerMoments.project(mind, inbox.post("m1", OwnerRequestKind.SECRET, "Password"))!!
        assertTrue("secrets are never typed into an owner card", secret.actions.none { it.needsInput })

        val control = OwnerMoments.project(mind, inbox.post("m1", OwnerRequestKind.CONTROL, "Solve the check on screen"))!!
        assertEquals(MomentKind.HANDOVER, control.kind)
        assertEquals(TaskCommand.Done, control.actions.single().command)
        assertFalse(OwnerMoments.overlayCard(control))
    }

    @Test fun aRequestFromAnotherMissionIsNotThisTasksMoment() {
        val other = inbox.post("m2", OwnerRequestKind.QUESTION, "Which account?")
        assertEquals(MomentKind.HANDOVER, OwnerMoments.project(mind, other)!!.kind)
        assertNull(OwnerMoments.project(mind.copy(phase = TaskPhase.WORKING), other))
        assertNull(OwnerMoments.project(null, other))
    }

    @Test fun classicAndBackgroundTasksSpeakTheSameLanguage() {
        val secret = OwnerMoments.project(task("bg-1", foreground = false, phase = TaskPhase.REVIEW)
            .copy(interruption = TaskInterruption.needsSecret()), null)!!
        assertEquals(MomentKind.SECRET, secret.kind)
        assertEquals(TaskEngine.BACKGROUND_WORKSPACE, secret.engine)

        val confirm = OwnerMoments.project(task("bg-1", foreground = false, phase = TaskPhase.REVIEW)
            .copy(confirmation = WorkspaceConfirmation("tok", "click", "n1", "f1", "Send")), null)!!
        assertEquals(MomentKind.APPROVAL, confirm.kind)
        assertEquals(TaskCommand.Confirm("tok"), confirm.actions.single { it.primary }.command)

        val gate = OwnerMoments.project(task("foreground-1"), null, gatePending = true)!!
        assertEquals(listOf<TaskCommand>(TaskCommand.Decline, TaskCommand.Approve), gate.actions.map { it.command })
        assertNull("the overlay gate belongs to the classic agent only",
            OwnerMoments.project(task("bg-1", foreground = false), null, gatePending = true))

        val human = OwnerMoments.project(task("foreground-1", phase = TaskPhase.HUMAN)
            .copy(interruption = TaskInterruption("x", "Log in, then tap I'm done", canAutofill = true)), null)!!
        assertEquals(listOf("Autofill", "I'm done"), human.actions.map { it.label })
        assertEquals("Log in, then tap I'm done", human.text)

        val takeOver = OwnerMoments.project(task("foreground-1", phase = TaskPhase.REVIEW)
            .copy(interruption = TaskInterruption("x", "Blocked", canTakeOver = true)), null)!!
        assertEquals(TaskCommand.TakeOver, takeOver.actions.single { it.primary }.command)

        TaskPhase.entries.filter { it in setOf(TaskPhase.DONE, TaskPhase.FAILED, TaskPhase.STOPPED) }.forEach {
            assertNull(OwnerMoments.project(task("foreground-1", phase = it).copy(interruption = TaskInterruption.needsSecret()), null))
        }
    }

    @Test fun closingACheckInNeverLeavesTheTaskHanging() {
        val values = OwnerMoments.project(mind, inbox.post("m1", OwnerRequestKind.VALUES, "x", fields = listOf(OwnerField("Name"))))!!
        assertEquals(TaskCommand.Decline, values.dismissal!!.command)
        val approval = OwnerMoments.project(mind, inbox.post("m1", OwnerRequestKind.APPROVAL, "x"))!!
        assertNull("approvals are answered with their own buttons", approval.dismissal)
        assertEquals("e.g. 12 March 1990", OwnerCardCopy.placeholder(OwnerField("Birth date", "date")))
    }

    /** No moment can show a button that the engine owning its task cannot handle, on any engine or state. */
    @Test fun everyMomentButtonHasAnEngineBehindIt() {
        val controllers = listOf(MindTaskController, ClassicForegroundTaskController, BackgroundWorkspaceTaskController)
        val interruptions = listOf(null, TaskInterruption.needsSecret(),
            TaskInterruption("x", "p", canTakeOver = true, canResumeAfterHuman = true, canAutofill = true),
            TaskInterruption("x", "p", canTakeOver = true, canAutofill = true))
        val confirmations = listOf(null, WorkspaceConfirmation("tok", "click", "n1", "f1", "Send"))
        val bases = listOf(task("mission-m1"), task("foreground-1"), task("bg-1", foreground = false))
        val requests = listOf(null) + OwnerRequestKind.entries.map { inbox.post("m1", it, "x", fields = listOf(OwnerField("Name"))) }
        var seen = 0
        for (base in bases) for (phase in TaskPhase.entries) for (interruption in interruptions) for (confirmation in confirmations)
            for (request in requests) for (gate in listOf(false, true)) {
                val state = base.copy(phase = phase, interruption = interruption, confirmation = confirmation)
                val moment = OwnerMoments.project(state, request, gate) ?: continue
                seen++
                val controller = controllers.single { it.engine == TaskEngines.of(state) }
                (moment.actions + listOfNotNull(moment.dismissal)).forEach {
                    assertTrue("${controller.engine} handles '${it.label}' on ${moment.kind}", controller.supports(it.command))
                }
            }
        assertTrue(seen > 50)
    }

    @Test fun valuesAndTakeOverAreDeliveredOnce() {
        val request = inbox.post("m", OwnerRequestKind.VALUES, "x", fields = listOf(OwnerField("First name")))
        assertTrue(inbox.respond(request.id, OwnerResponse.Values(mapOf("First name" to "Jan"), remember = false)))
        assertFalse(inbox.respond(request.id, OwnerResponse.TakeOver))
        assertEquals(OwnerResponse.Values(mapOf("First name" to "Jan"), false), inbox.poll(request.id))
    }

    @Test fun notificationsAnswerTheMomentFromTheShade() {
        val question = OwnerMoments.project(mind, inbox.post("m1", OwnerRequestKind.QUESTION, "Which account?"))!!
        val q = OwnerMoments.notificationActions(question)
        assertEquals(listOf("Not now", "Take over", "Reply"), q.map { it.label })
        assertTrue(q.last().reply)

        val values = OwnerMoments.notificationActions(OwnerMoments.project(mind,
            inbox.post("m1", OwnerRequestKind.VALUES, "x", fields = listOf(OwnerField("Name"))))!!)
        assertTrue("fields are typed on the card, not in the shade", values.none { it.command is TaskCommand.Fill || it.reply })
        assertEquals(listOf<TaskCommand>(TaskCommand.Stop, TaskCommand.Decline, TaskCommand.TakeOver), values.map { it.command })

        val secret = OwnerMoments.notificationActions(OwnerMoments.project(mind, inbox.post("m1", OwnerRequestKind.SECRET, "Password"))!!)
        assertEquals(listOf<TaskCommand>(TaskCommand.Stop), secret.map { it.command })

        val approval = OwnerMoments.notificationActions(OwnerMoments.project(mind, inbox.post("m1", OwnerRequestKind.APPROVAL, "Send?"))!!)
        assertEquals(listOf("Stop", "Decline", "Approve"), approval.map { it.label })
        assertEquals("only Approve needs the phone unlocked", listOf(false, false, true), approval.map(OwnerMoments::needsUnlock))
        assertTrue(OwnerMoments.needsUnlock(q.last()))
    }
}
