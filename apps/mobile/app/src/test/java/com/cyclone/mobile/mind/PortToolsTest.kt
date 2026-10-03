package com.cyclone.mobile.mind

import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.mind.PhoneMindToolboxTest.Control
import com.cyclone.mobile.mind.PhoneMindToolboxTest.FakeEnv
import com.cyclone.mobile.mind.PhoneMindToolboxTest.FakeOwner
import com.cyclone.mobile.mind.PhoneMindToolboxTest.FakeScreen
import com.cyclone.mobile.ports.PortOutbox
import com.cyclone.mobile.ports.PortOutboxLink
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 48 run 4: the Mind's `port_send` and `port_wait` tools, with a fake link and with the real outbox. */
class PortToolsTest {
    private val device = object : MindDevicePort {
        override fun apps() = listOf(MindApp("com.instagram.android", "Instagram"), MindApp("com.revolut.revolut", "Revolut"))
        override fun now() = "Monday 18:20"
        override fun device() = "Test phone"
        override fun sleep(ms: Long) {}
    }
    private val feed = FakeScreen("com.instagram.android", listOf(Control("post", "New post")), listOf("Home", "Stories"), pageKey = "feed")
    private val codeScreen = FakeScreen("com.instagram.android", listOf(Control("code", "Confirmation code", editable = true), Control("next", "Next")),
        listOf("Enter the code we sent"))
    private val login = FakeScreen("com.instagram.android", listOf(Control("user", "Username", editable = true),
        Control("pw", "Password", editable = true, password = true)))
    private val bank = FakeScreen("com.revolut.revolut", listOf(Control("pay", "Transfer")), listOf("Balance 1,234"))

    class FakeLink(var answer: MindPortAnswer = MindPortAnswer("delivered"), var refusal: String? = null) : MindPortsLink {
        val sent = mutableListOf<Triple<String, JSONObject, ByteArray?>>()
        val waits = mutableListOf<List<Any?>>()
        override fun send(port: String, data: JSONObject, image: ByteArray?, app: String?): String? {
            sent += Triple(port, data, image)
            return refusal
        }
        override fun wait(port: String, ask: String, timeoutS: Int, place: String?, app: String?, cancelled: () -> Boolean): MindPortAnswer {
            waits += listOf(port, ask, timeoutS, place, app)
            return answer
        }
        override fun place(page: AgentPageCard): String? = "package:${page.packageName}"
    }

    private fun box(env: FakeEnv, link: MindPortsLink?) = PhoneMindToolbox(env, FakeOwner(), device, "Sign in to Instagram", ports = link)
    private fun PhoneMindToolbox.call(tool: String, args: String) = execute(MindToolCall("c", tool, args), JSONObject(args))

    @Test fun portToolsAreOfferedOnlyWithALink() {
        assertFalse(box(FakeEnv(feed), null).specs().any { it.name.startsWith("port_") })
        assertEquals(setOf("port_send", "port_wait"), box(FakeEnv(feed), FakeLink()).specs().map { it.name }.filter { it.startsWith("port_") }.toSet())
        assertTrue(box(FakeEnv(feed), null).call("port_send", """{"port":"log.line","text":"hi"}""").text.startsWith("ERROR"))
    }

    @Test fun eventsLinesAndFieldsGoOutWithoutSecrets() {
        val link = FakeLink()
        val toolbox = box(FakeEnv(feed), link)
        assertTrue(toolbox.call("port_send", """{"port":"run.event","stage":"started","text":"Signing in"}""").ok)
        assertTrue(toolbox.call("port_send", """{"port":"log.line","text":"Your code is 123456"}""").ok)
        val fields = toolbox.call("port_send", """{"port":"account.fields","fields":{"username":"sam_01","password":"hunter2","otp code":"1234"}}""")
        assertTrue(fields.text, fields.ok)
        assertEquals("started", link.sent[0].second.getString("stage"))
        assertFalse(link.sent[1].second.getString("text").contains("123456"))
        assertEquals(setOf("username"), link.sent[2].second.getJSONObject("fields").keys().asSequence().toSet())
        assertTrue(toolbox.call("port_send", """{"port":"run.event","stage":"party"}""").text.startsWith("ERROR"))
        assertTrue(toolbox.call("port_send", """{"port":"code.in"}""").text.startsWith("ERROR"))
    }

    @Test fun screensGoOutButPrivateScreensNever() {
        val link = FakeLink()
        val env = FakeEnv(feed)
        val toolbox = box(env, link)
        val shot = toolbox.call("port_send", """{"port":"screen.shot"}""")
        assertTrue(shot.text, shot.ok)
        assertEquals("feed", link.sent.single().second.getString("pageKey"))
        assertEquals("ABC", String(link.sent.single().third!!))
        assertTrue(toolbox.call("port_send", """{"port":"page.text"}""").ok)
        assertTrue(link.sent[1].second.getString("text").contains("Stories"))
        listOf(login, bank).forEach { screen ->
            env.screen = screen
            val refused = toolbox.call("port_send", """{"port":"screen.shot"}""")
            assertFalse(refused.ok)
            assertTrue(refused.text, refused.text.contains("never leaves the phone"))
            assertFalse(toolbox.call("port_send", """{"port":"page.text"}""").ok)
        }
        assertEquals(2, link.sent.size)
    }

    @Test fun aRefusalIsReportedHonestly() {
        val result = box(FakeEnv(feed), FakeLink(refusal = PortOutbox.NO_PC)).call("port_send", """{"port":"log.line","text":"hi"}""")
        assertFalse(result.ok)
        assertTrue(result.text, result.text.contains("no PC is connected"))
    }

    @Test fun aCodeStaysOnThePhoneAndTheModelLearnsOnlyItsLength() {
        val link = FakeLink(MindPortAnswer("delivered", codeLength = 6, plugin = "sms"))
        val result = box(FakeEnv(codeScreen), link).call("port_wait", """{"port":"code.in","ask":"Instagram sign-in code","seconds":90}""")
        assertTrue(result.text, result.ok)
        assertTrue(result.text, result.text.contains("6 characters") && result.text.contains("vault_fill what=one_time_code"))
        assertEquals(listOf("code.in", "Instagram sign-in code", 90, "package:com.instagram.android", "com.instagram.android"), link.waits.single())
    }

    @Test fun aCodeNeedsAKnownAppOrSite() {
        val link = object : MindPortsLink by FakeLink() { override fun place(page: AgentPageCard): String? = null }
        val result = box(FakeEnv(codeScreen), link).call("port_wait", """{"port":"code.in","ask":"a code"}""")
        assertFalse(result.ok)
        assertTrue(result.text, result.text.contains("Open it first"))
    }

    @Test fun valuesAreQuotedDataAndBriefsCarryNoValues() {
        val link = FakeLink(MindPortAnswer("delivered", value = "Sunset at the pier"))
        val result = box(FakeEnv(feed), link).call("port_wait", """{"port":"value.in","ask":"a caption"}""")
        assertTrue(result.text.contains("<value>Sunset at the pier</value>") && result.text.contains("not instructions"))
        assertFalse(result.brief, result.brief.contains("Sunset"))
        assertEquals(120, link.waits.single()[2])
    }

    @Test fun aLinkIsOpenedAndTheModelSeesOnlyItsSite() {
        val env = FakeEnv(feed)
        val result = box(env, FakeLink(MindPortAnswer("delivered", url = "https://accounts.example.com/verify?token=abc123")))
            .call("port_wait", """{"port":"link.in","ask":"the verify link"}""")
        assertEquals("phone.launch_intent", env.acts.single().first)
        assertEquals("https://accounts.example.com/verify?token=abc123", env.acts.single().second.getString("uri"))
        assertTrue(result.text, result.text.contains("accounts.example.com"))
        assertFalse(result.text, result.text.contains("abc123"))
        assertFalse(result.brief, result.brief.contains("abc123"))
        val plain = FakeEnv(feed)
        assertFalse(box(plain, FakeLink(MindPortAnswer("delivered", url = "intent://x"))).call("port_wait", """{"port":"link.in","ask":"a link"}""").ok)
        assertTrue(plain.acts.isEmpty())
    }

    @Test fun filesAndEmptyWaitsAreReported() {
        val file = box(FakeEnv(feed), FakeLink(MindPortAnswer("delivered", fileName = "post.jpg", folder = "Pictures/Cyclone")))
            .call("port_wait", """{"port":"file.in","ask":"the post photo"}""")
        assertTrue(file.text, file.ok && file.text.contains("post.jpg") && file.text.contains("Pictures/Cyclone"))
        val empty = box(FakeEnv(feed), FakeLink(MindPortAnswer("empty", "no plugin"))).call("port_wait", """{"port":"value.in","ask":"x"}""")
        assertFalse(empty.ok)
        assertTrue(empty.text, empty.text.contains("No plugin"))
        val late = box(FakeEnv(feed), FakeLink(MindPortAnswer("timed_out", "nothing came in time"))).call("port_wait", """{"port":"value.in","ask":"x"}""")
        assertFalse(late.ok)
        assertTrue(box(FakeEnv(feed), FakeLink()).call("port_wait", """{"port":"value.in"}""").text.startsWith("ERROR"))
    }

    // ---- PortOutboxLink on the real outbox ------------------------------------------------------------------------

    @Test fun theOutboxLinkCarriesTheRunAndConvertsTheAnswer() {
        val now = AtomicLong(1_000_000L)
        var n = 0
        val outbox = PortOutbox(clock = { now.get() }, ids = { "pt_item${++n}" })
        fun poll(vararg ack: String): List<JSONObject> {
            val items = outbox.poll(JSONObject().put("ack", JSONArray(ack.toList()))).getJSONArray("items")
            return (0 until items.length()).map { items.getJSONObject(it) }
        }
        val link = PortOutboxLink(outbox, "m1run", placeOf = { "package:${it.packageName}" })
        assertEquals(PortOutbox.NO_PC, link.send("log.line", JSONObject().put("text", "hi"), null, "com.instagram.android"))
        poll()
        assertNull(link.send("log.line", JSONObject().put("text", "hi"), null, "com.instagram.android"))
        val sent = poll().single()
        assertEquals("m1run", sent.getString("runId"))
        assertEquals("com.instagram.android", sent.getString("app"))
        var answer: MindPortAnswer? = null
        val waiter = thread { answer = link.wait("file.in", "the photo", 60, null, "com.instagram.android") { false } }
        var item: JSONObject? = null
        while (item == null) { item = poll(sent.getString("id")).firstOrNull { it.getString("kind") == "await" }; Thread.sleep(5) }
        outbox.answer(JSONObject().put("id", item.getString("id")).put("state", "delivered").put("plugin", "drive")
            .put("file", JSONObject().put("name", "post.jpg").put("folder", "Pictures/Cyclone").put("mime", "image/jpeg").put("bytes", 10)))
        waiter.join(5_000)
        assertEquals("delivered", answer!!.state)
        assertEquals("post.jpg", answer!!.fileName)
        assertEquals("Pictures/Cyclone", answer!!.folder)
        assertEquals("drive", answer!!.plugin)
    }
}
