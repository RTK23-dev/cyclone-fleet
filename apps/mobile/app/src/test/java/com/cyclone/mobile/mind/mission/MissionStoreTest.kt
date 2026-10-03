package com.cyclone.mobile.mind.mission

import com.cyclone.mobile.mind.MindCheckpoint
import com.cyclone.mobile.mind.MindConversation
import com.cyclone.mobile.mind.MindMessage
import com.cyclone.mobile.mind.MindPlanStep
import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.MindUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.concurrent.thread

class MissionStoreTest {
    private fun store() = MissionStore(Files.createTempDirectory("missions").toFile(), keep = 3)

    private fun mission(id: String, status: MissionStatus = MissionStatus.RUNNING, at: Long = 1_000) =
        Mission(id, "set a timer", status, at, at, "model/a", "Model A", plan = listOf(MindPlanStep("Open Clock", "done")))

    @Test fun missionRoundTrip() {
        val store = store()
        val original = mission("mission-1").withEvent(MissionEvent(2_000, "Opened Clock"))
            .copy(usage = MindUsage(1200, 40, 0.01), summary = "Timer running", waitingFor = "Which timer?")
        store.save(original)
        assertEquals(original, store.load("mission-1"))
    }

    @Test fun journalRoundTripIsRedacted() {
        val store = store()
        val conversation = MindConversation(listOf(
            MindMessage.System("sys"),
            MindMessage.User("log in, my password: hunter2"),
            MindMessage.Assistant("typing", listOf(MindToolCall("c1", "type_text", "{\"ref\":\"e1\",\"text\":\"4111 1111 1111 1111\"}")),
                JSONArray().put("reasoning")),
            MindMessage.Tool("c1", "type_text", "Your code is 482913", "code 482913"),
            MindMessage.User("shot", "data:image/png;base64,AAAA", MindMessage.User.Origin.SCREEN),
        ))
        store.saveJournal("mission-2", MindCheckpoint(4, 60_000, MindUsage(10, 5, 0.0), true, "model/a", conversation))
        val journal = store.loadJournal("mission-2")!!
        val text = journal.conversation.toJournal().toString()
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("4111"))
        assertFalse(text.contains("482913"))
        assertFalse(text.contains("reasoning\""))
        assertFalse(text.contains("base64"))
        assertEquals(4, journal.turn)
        assertEquals(60_000, journal.workingMs)
        val call = (journal.conversation.all()[2] as MindMessage.Assistant).toolCalls.single()
        assertNotNull("arguments stay valid JSON", call.argumentsJson())
    }

    @Test fun recoverMarksLiveMissionsInterruptedAndPrunes() {
        val store = store()
        store.save(mission("mission-live", MissionStatus.RUNNING, 10))
        store.save(mission("mission-wait", MissionStatus.WAITING, 9))
        (1..4).forEach { store.save(mission("mission-old$it", MissionStatus.COMPLETED, it.toLong())) }
        val after = store.recover(now = 100)
        assertEquals(MissionStatus.INTERRUPTED, store.load("mission-live")!!.status)
        assertEquals(MissionStatus.INTERRUPTED, store.load("mission-wait")!!.status)
        assertEquals(3, after.size)
        assertTrue(MissionStatus.INTERRUPTED.resumable)
    }

    @Test fun idsAreValidated() {
        val store = store()
        assertTrue(runCatching { store.load("../../etc/passwd") }.isFailure)
        assertNull(store.load("missing-mission"))
    }

    @Test fun unknownStatusBecomesInterrupted() {
        val json = mission("mission-3").toJson().put("status", "EXPLODED")
        assertEquals(MissionStatus.INTERRUPTED, Mission.fromJson(json).status)
    }
}

class MindRedactionTest {
    @Test fun notificationTextHidesCodesInAnyOrder() {
        assertEquals("[hidden] is your Instagram code", MindRedaction.scrubText("482913 is your Instagram code"))
        assertEquals("Dinner at 7? Table 1234", MindRedaction.scrubText("Dinner at 7? Table 1234"))
    }

    @Test fun masksSecretsButKeepsOrdinaryText() {
        assertEquals("password: [hidden]", MindRedaction.scrub("password: hunter2"))
        assertEquals("card [number hidden]", MindRedaction.scrub("card 4111-1111-1111-1111"))
        assertTrue(MindRedaction.scrub("your verification code is 123456").endsWith("[hidden]"))
        assertEquals("[key hidden]", MindRedaction.scrub("sk-or-v1-abcdefghijklmnop"))
        assertEquals("[account hidden]", MindRedaction.scrub("NL91ABNA0417164300"))
        assertEquals("Set a timer for 5 minutes at 14:05", MindRedaction.scrub("Set a timer for 5 minutes at 14:05"))
        assertEquals("{\"seconds\":300}", MindRedaction.scrub("{\"seconds\":300}"))
    }
}

class OwnerInboxTest {
    @Test fun answerWakesTheMission() {
        val inbox = OwnerInbox()
        val request = inbox.post("m", OwnerRequestKind.QUESTION, "Which account?", listOf("work", "home"))
        assertEquals(request, inbox.pending.value)
        thread { Thread.sleep(50); assertTrue(inbox.respond(request.id, OwnerResponse.Answer("work"))) }
        val wait = inbox.await(request, 5_000) { false }
        assertEquals(OwnerResponse.Answer("work"), wait.response)
        assertNull("answered requests are cleared", inbox.pending.value)
        assertFalse("late answers are ignored", inbox.respond(request.id, OwnerResponse.Answer("home")))
    }

    @Test fun timeoutAndCancel() {
        var now = 0L
        val inbox = OwnerInbox { now.also { now += 1_000 } }
        val request = inbox.post("m", OwnerRequestKind.QUESTION, "?")
        val timedOut = inbox.await(request, 5_000) { false }
        assertNull(timedOut.response)
        assertFalse(timedOut.cancelled)

        val second = inbox.post("m", OwnerRequestKind.QUESTION, "?")
        assertTrue(inbox.await(second, 60_000) { true }.cancelled)
    }

    @Test fun withdrawAllReleasesAWaitingMission() {
        val inbox = OwnerInbox()
        val request = inbox.post("m", OwnerRequestKind.APPROVAL, "tap Send")
        thread { Thread.sleep(50); inbox.withdrawAll() }
        val wait = inbox.await(request, 5_000) { false }
        assertTrue(wait.cancelled)
    }

    @Test fun pollKeepsTheRequestOpen() {
        val inbox = OwnerInbox()
        val request = inbox.post("m", OwnerRequestKind.APPROVAL, "tap Send")
        assertNull(inbox.poll(request.id))
        inbox.respond(request.id, OwnerResponse.Approve)
        assertEquals(OwnerResponse.Approve, inbox.poll(request.id))
        assertEquals(request, inbox.pending.value)
        JSONObject()
    }
}
