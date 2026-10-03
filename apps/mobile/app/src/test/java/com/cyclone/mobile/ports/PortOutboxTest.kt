package com.cyclone.mobile.ports

import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 48 run 4: the phone's port outbox, as the PC's Port Hub polls and answers it. */
class PortOutboxTest {
    private val now = AtomicLong(1_000_000L)
    private var n = 0
    private val outbox = PortOutbox(clock = { now.get() }, ids = { "pt_item${++n}" })
    private val run = PortOutbox.Run("mabcdefgh", app = "com.example.shop")

    private fun poll(vararg ack: String, drop: Boolean = false): List<JSONObject> {
        val items = outbox.poll(JSONObject().put("ack", JSONArray(ack.toList())).put("drop", drop)).getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }
    }

    @Test fun withoutAPcARunIsToldAtOnce() {
        assertEquals(PortOutbox.NO_PC, outbox.emit(run, "log.line", JSONObject().put("text", "hi")))
        assertEquals("no_pc", outbox.await(run, "value.in", "a caption", 30).state)
        assertTrue(outbox.pending().isEmpty())
    }

    @Test fun outMessagesWaitUntilThePcAcknowledgesThem() {
        poll()
        assertNull(outbox.emit(run, "run.event", JSONObject().put("stage", "started")))
        assertNull(outbox.emit(run, "log.line", JSONObject().put("text", "Opened the shop")))
        val items = poll()
        assertEquals(listOf("run.event", "log.line"), items.map { it.getString("port") })
        assertEquals("mabcdefgh", items[0].getString("runId"))
        assertEquals("com.example.shop", items[0].getString("app"))
        assertEquals("a lost answer gets the same items again", 2, poll().size)
        assertTrue(poll("pt_item1", "pt_item2").isEmpty())
    }

    @Test fun secretsNeverLeave() {
        poll()
        outbox.emit(run, "account.fields", JSONObject().put("fields", JSONObject()
            .put("firstName", "Sam").put("password", "hunter2").put("cardNumber", "4111").put("pin_code", "1234").put("email", "sam@example.com")))
        outbox.emit(run, "page.text", JSONObject().put("text", "Welcome\nPassword: hunter2\nDone"))
        assertEquals("only private fields: nothing to send", "nothing was left to send once private fields were taken out",
            outbox.emit(run, "account.fields", JSONObject().put("fields", JSONObject().put("password", "x"))))
        val items = poll()
        assertEquals(setOf("firstName", "email"), items[0].getJSONObject("data").getJSONObject("fields").keys().asSequence().toSet())
        assertEquals("Welcome\n[hidden]\nDone", items[1].getJSONObject("data").getString("text"))
        assertEquals("not a port the phone sends", "secret.out is not a port the phone sends on", outbox.emit(run, "secret.out", JSONObject()))
    }

    @Test fun aScreenshotTravelsInChunks() {
        poll()
        val png = ByteArray(PortOutbox.BLOB_CHUNK + 10) { it.toByte() }
        assertNull(outbox.emit(run, "screen.shot", JSONObject().put("pageKey", "signup"), png))
        val item = poll().single()
        assertEquals(png.size, item.getJSONObject("blob").getInt("bytes"))
        val first = outbox.blob(JSONObject().put("id", item.getString("id")).put("offset", 0))
        assertFalse(first.getBoolean("done"))
        val second = outbox.blob(JSONObject().put("id", item.getString("id")).put("offset", PortOutbox.BLOB_CHUNK))
        assertTrue(second.getBoolean("done"))
        val joined = java.util.Base64.getDecoder().decode(first.getString("data")) + java.util.Base64.getDecoder().decode(second.getString("data"))
        assertTrue(joined.contentEquals(png))
    }

    @Test fun aWaitEndsWithThePluginsAnswer() {
        poll()
        var answer: PortOutbox.Answer? = null
        val waiter = thread { answer = outbox.await(run, "value.in", "a caption", 60) }
        var item: JSONObject? = null
        while (item == null) { item = poll().firstOrNull(); Thread.sleep(5) }
        assertEquals("await", item.getString("kind"))
        assertEquals("a caption", item.getJSONObject("match").getString("ask"))
        val handled = outbox.answer(JSONObject().put("id", item.getString("id")).put("state", "delivered").put("plugin", "captions")
            .put("value", JSONObject().put("caption", "Sunset at the pier")))
        assertTrue(handled.getBoolean("handled"))
        waiter.join(5_000)
        assertEquals("delivered", answer!!.state)
        assertEquals("Sunset at the pier", (answer!!.value as JSONObject).getString("caption"))
        assertFalse("a second answer finds nothing", outbox.answer(JSONObject().put("id", item.getString("id")).put("state", "failed")).getBoolean("handled"))
    }

    @Test fun aCodeIsOpenedOnThePhoneAndTheRunLearnsOnlyItsLength() {
        poll()
        var opened: Triple<String, String, JSONObject>? = null
        outbox.openCode = { r, place, sealed -> opened = Triple(r.runId, place, sealed); 6 }
        assertEquals("refused", outbox.await(run, "code.in", "the sign-up code", 60).state)
        var answer: PortOutbox.Answer? = null
        val waiter = thread { answer = outbox.await(run, "code.in", "the sign-up code", 60, place = "package:com.example.shop") }
        var item: JSONObject? = null
        while (item == null) { item = poll().firstOrNull(); Thread.sleep(5) }
        assertEquals("package:com.example.shop", item.getString("place"))
        outbox.answer(JSONObject().put("id", item.getString("id")).put("state", "delivered")
            .put("sealed", JSONObject().put("leaseId", "ls_x").put("enc", "e").put("ct", "c").put("aad", "a")))
        waiter.join(5_000)
        assertEquals("delivered", answer!!.state)
        assertEquals(6, answer!!.codeLength)
        assertNull(answer!!.value)
        assertEquals("mabcdefgh" to "package:com.example.shop", opened!!.first to opened!!.second)
    }

    @Test fun aStoppedRunCancelsItsWait() {
        poll()
        var stop = false
        var answer: PortOutbox.Answer? = null
        val waiter = thread { answer = outbox.await(run, "link.in", "the confirmation link", 60) { stop } }
        var item: JSONObject? = null
        while (item == null) { item = poll().firstOrNull(); Thread.sleep(5) }
        stop = true
        waiter.join(5_000)
        assertEquals("cancelled", answer!!.state)
        val cancel = poll(item.getString("id")).single()
        assertEquals("cancel", cancel.getString("kind"))
        assertEquals(item.getString("id"), cancel.getString("item"))
    }

    @Test fun aWaitThePcNeverPickedUpIsTakenBack() {
        poll()
        var stop = false
        var answer: PortOutbox.Answer? = null
        val waiter = thread { answer = outbox.await(run, "value.in", "x", 60) { stop } }
        while (outbox.pending().isEmpty()) Thread.sleep(5)
        stop = true
        waiter.join(5_000)
        assertEquals("cancelled", answer!!.state)
        assertTrue(outbox.pending().isEmpty())
    }

    @Test fun anItemThePcRefusesIsDroppedAndItsWaitFails() {
        poll()
        var answer: PortOutbox.Answer? = null
        val waiter = thread { answer = outbox.await(run, "value.in", "x", 60) }
        while (outbox.pending().isEmpty()) Thread.sleep(5)
        poll(drop = true)
        waiter.join(5_000)
        assertEquals("refused", answer!!.state)
    }

    @Test fun aPcThatStopsPollingIsNoLongerConnected() {
        poll()
        assertTrue(outbox.connected())
        now.addAndGet(PortOutbox.CONNECTED_MS + 1)
        assertFalse(outbox.connected())
    }
}
