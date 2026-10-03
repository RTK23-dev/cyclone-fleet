package com.cyclone.mobile.mind

import com.cyclone.mobile.mind.PhoneMindToolboxTest.Control
import com.cyclone.mobile.mind.PhoneMindToolboxTest.FakeEnv
import com.cyclone.mobile.mind.PhoneMindToolboxTest.FakeOwner
import com.cyclone.mobile.mind.PhoneMindToolboxTest.FakeScreen
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Plan 37 W3 (alpha.67): memory that stays tidy and never misses "remember this". */
class MemoryV2Test {
    private fun memory(limit: Int = 300, sealer: MemorySealer? = null) =
        MindMemory(Files.createTempFile("mind", ".json").toFile(), limit = limit, sealer = sealer)

    @Test
    fun rememberRequestsAreNoticedInEnglishAndDutchButNotQuestionsOrReminders() {
        assertNotNull(RememberIntent.detect("Send Lou my ETA. Remember that her Instagram is lo.06."))
        assertEquals("Remember that her Instagram is lo.06.", RememberIntent.detect("Send Lou my ETA. Remember that her Instagram is lo.06."))
        assertNotNull(RememberIntent.detect("From now on post from @mybrand"))
        assertNotNull(RememberIntent.detect("don't forget I take the bike"))
        assertNotNull(RememberIntent.detect("Onthoud dat Louella mijn vriendin is"))
        assertNotNull(RememberIntent.detect("next time use the bike tab"))
        assertNull(RememberIntent.detect("Do you remember what I asked yesterday?"))
        assertNull(RememberIntent.detect("Remind me at 7 to call mom"))
        assertNull(RememberIntent.detect("Set a timer for 5 minutes"))
        assertNull(RememberIntent.detect(null))
    }

    @Test
    fun onePersonIsOneCardAndNewDetailsMergeIntoIt() {
        val m = memory()
        val first = m.remember(MindMemory.Candidate("", person = "Louella", relation = "girlfriend", app = "Instagram", handle = "lo.06"))
        assertTrue(first is MindMemory.Saved.Stored)
        m.remember(MindMemory.Candidate("", person = "louella", app = "WhatsApp", handle = "Louella ❤️"))
        val moved = m.remember(MindMemory.Candidate("", person = "Louella", app = "instagram", handle = "lo.07"))
        val card = m.all().single()
        assertEquals("Louella — girlfriend; WhatsApp: Louella ❤️; instagram: lo.07", card.text)
        assertEquals("the old handle is kept in history", true, card.history.any { it.contains("lo.06") })
        assertTrue((moved as MindMemory.Saved.Updated).previous!!.contains("lo.06"))
        assertEquals(listOf(card.id), m.peopleIn("send my girlfriend the ETA").map { it.id })
        assertEquals(listOf(card.id), m.peopleIn("message lo.07 on Instagram").map { it.id })
        assertTrue(m.peopleIn("call my boss").isEmpty())
    }

    @Test
    fun duplicatesChangeNothingNearDuplicatesUpdateAndRelatedOnesAreShown() {
        val m = memory()
        m.remember(MindMemory.Candidate("The owner likes replies in Dutch", kind = MindMemory.PREFERENCE))
        val same = m.remember(MindMemory.Candidate("the owner likes replies in dutch", kind = MindMemory.PREFERENCE))
        assertNull((same as MindMemory.Saved.Updated).previous)
        val newer = m.remember(MindMemory.Candidate("The owner likes short replies in Dutch", kind = MindMemory.PREFERENCE))
        assertEquals("The owner likes replies in Dutch", (newer as MindMemory.Saved.Updated).previous)
        assertEquals(1, m.all().size)
        val related = m.remember(MindMemory.Candidate("The owner likes long replies in English on Mondays", kind = MindMemory.PREFERENCE))
        assertTrue(related is MindMemory.Saved.Stored)
        assertEquals(1, (related as MindMemory.Saved.Stored).similar.size)
        val replaced = m.remember(MindMemory.Candidate("Replies: Dutch, short, no emoji", replaces = related.similar.single().id))
        assertEquals("The owner likes short replies in Dutch", (replaced as MindMemory.Saved.Updated).previous)
        assertEquals(2, m.all().size)
    }

    @Test
    fun appNotesAreFiledUnderTheirApp() {
        val m = memory()
        m.remember(MindMemory.Candidate("post from @mybrand, not the personal account", app = "Instagram"))
        val fact = m.all().single()
        assertEquals(MindMemory.APP, fact.kind)
        assertEquals("Instagram: post from @mybrand, not the personal account", fact.text)
    }

    @Test
    fun oneOffThingsAndSecretsAreRefusedButWhatTheOwnerAskedIsKept() {
        val m = memory()
        assertTrue(m.remember("The ETA today is 22 minutes") is MindMemory.Saved.Refused)
        assertTrue(m.remember(MindMemory.Candidate("I work from home today and every Friday", source = MindMemory.OWNER)) is MindMemory.Saved.Stored)
        assertTrue(m.remember(MindMemory.Candidate("", person = "Bank", app = "Mail", handle = "password: hunter2")) is MindMemory.Saved.Refused)
        assertTrue(m.remember("wachtwoord is Zomer2024") is MindMemory.Saved.Refused)
        assertEquals(1, m.all().size)
    }

    @Test
    fun whatTheOwnerAskedIsTheLastToGoWhenMemoryIsFull() {
        var now = 0L
        val m = MindMemory(Files.createTempFile("mind", ".json").toFile(), { now }, limit = 2)
        now = 1; m.remember(MindMemory.Candidate("Keep this one about bikes", source = MindMemory.OWNER))
        now = 2; m.remember("learned fact about clocks")
        now = 3; m.remember("learned fact about maps")
        assertEquals(setOf("Keep this one about bikes", "learned fact about maps"), m.all().map { it.text }.toSet())
    }

    @Test
    fun theDigestPutsThePeopleOfTheGoalFirstGroupedLikeAProfile() {
        val m = memory()
        m.remember(MindMemory.Candidate("The owner prefers the bike", kind = MindMemory.PREFERENCE, source = MindMemory.OWNER))
        m.remember(MindMemory.Candidate("", person = "Louella", relation = "girlfriend", app = "Instagram", handle = "lo.06"))
        m.remember("Gmail account is the work one")
        val digest = m.digest("Tell my girlfriend I'm on my way")
        assertTrue(digest, digest.startsWith("People the owner told you about:\n- [f2] Louella — girlfriend; Instagram: lo.06"))
        assertTrue(digest.contains("The owner's preferences:\n- [f1] The owner prefers the bike (the owner asked)"))
        assertTrue(digest.contains("Other things you kept:"))
        assertEquals("", memory().digest("anything"))
    }

    @Test
    fun memoryIsSealedAtRestAndAnOlderPlainFileIsReadThenSealed() {
        val xor = object : MemorySealer {
            override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
            override fun open(sealed: ByteArray) = seal(sealed)
        }
        val file = Files.createTempFile("mind", ".json").toFile()
        MindMemory(file).remember("Older fact from alpha.66 about bikes")
        assertTrue(file.readText().startsWith("{"))
        val sealed = MindMemory(file, sealer = xor)
        assertEquals(1, sealed.all().size)
        sealed.remember("A new fact about maps")
        assertTrue(file.readText().startsWith("CYM1:"))
        assertFalse(file.readText().contains("bikes"))
        assertEquals(2, MindMemory(file, sealer = xor).all().size)
        assertTrue("without the key nothing is readable", MindMemory(file).all().isEmpty())
    }

    // ---- the toolbox --------------------------------------------------------------------------------------------------

    private val device = object : MindDevicePort {
        override fun apps() = listOf(MindApp("com.instagram.android", "Instagram"))
        override fun now() = "Monday 18:20"
        override fun device() = "Test phone"
        override fun sleep(ms: Long) {}
    }
    private val chat = FakeScreen("com.instagram.android", listOf(Control("box", "Message", "edit_text", editable = true)), listOf("lo.06"))

    private class Owner : MindOwnerPort by FakeOwner() {
        val memories = mutableListOf<String>()
        override fun memoryUpdated(text: String) { memories += text }
    }

    private fun MindToolbox.run(name: String, args: String = "{}") = execute(MindToolCall("c", name, args), JSONObject(args))

    @Test
    fun whenTheOwnerAsksToRememberTheMissionCannotFinishWithoutSavingIt() {
        val owner = Owner()
        val m = memory()
        val box = PhoneMindToolbox(FakeEnv(chat), owner, device, "Send Lou my ETA. Remember that her Instagram is lo.06.", memory = m, missionId = "m1")
        val early = box.run("task_finish", """{"summary":"Sent","evidence":"chat"}""")
        assertFalse(early.ok)
        assertTrue(early.text.contains("Remember that her Instagram is lo.06."))
        val saved = box.run("remember", """{"fact":"","person":"Lou","relation":"girlfriend","app":"Instagram","handle":"lo.06"}""")
        assertTrue(saved.text, saved.text.startsWith("Memory updated: [f1] Lou — girlfriend; Instagram: lo.06"))
        assertEquals(listOf("Lou — girlfriend; Instagram: lo.06"), owner.memories)
        assertEquals(MindMemory.OWNER, m.all().single().source)
        assertTrue(box.run("task_finish", """{"summary":"Sent","evidence":"chat"}""").ok)
    }

    @Test
    fun theReminderComesOnceAndARequestMidMissionCounts() {
        val box = PhoneMindToolbox(FakeEnv(chat), Owner(), device, "Open Instagram", memory = memory())
        box.onOwnerMessage("Oh and from now on always post from @mybrand")
        assertFalse(box.run("task_finish", """{"summary":"Open","evidence":"feed"}""").ok)
        assertTrue("never a trap", box.run("task_finish", """{"summary":"Open","evidence":"feed"}""").ok)
    }

    @Test
    fun peopleAreKeptOnlyWhenTheOwnerToldCycloneAboutThem() {
        val m = memory()
        val box = PhoneMindToolbox(FakeEnv(chat), Owner(), device, "Reply to Louella", memory = m)
        assertFalse(box.run("remember", """{"fact":"works at the bakery","person":"Sam"}""").ok)
        assertTrue(box.run("remember", """{"fact":"likes short replies","person":"Louella"}""").ok)
        assertEquals(MindMemory.LEARNED, m.all().single().source)
    }

    @Test
    fun aSimilarMemoryIsShownSoTheModelCanReplaceIt() {
        val m = memory()
        m.remember(MindMemory.Candidate("The owner takes the bike to work on weekdays", kind = MindMemory.PREFERENCE))
        val box = PhoneMindToolbox(FakeEnv(chat), Owner(), device, "Plan my route", memory = m)
        val result = box.run("remember", """{"fact":"The owner takes the car to work when it rains","kind":"preference"}""")
        assertTrue(result.text, result.text.contains("Similar memories: [f1]"))
        assertTrue(result.text.contains("replaces=<id>"))
    }

    @Test
    fun aRequestSentDuringTheMissionIsNamedForTheModel() {
        val script = mutableListOf(listOf("screen_read" to "{}"), listOf("remember" to "{\"fact\":\"The owner cycles to work\"}"),
            listOf("task_finish" to "{\"summary\":\"ok\",\"evidence\":\"x\"}"))
        val model = object : MindModel {
            override val id = "m"; override val label = "m"; override val vision = false
            override fun complete(request: MindModelRequest): MindModelReply =
                MindModelReply("", script.removeAt(0).map { (n, a) -> MindToolCall("c${script.size}", n, a) })
        }
        val messages = mutableListOf("Remember that I cycle to work")
        val m = memory()
        val box = PhoneMindToolbox(FakeEnv(chat), Owner(), device, "Open Instagram", memory = m)
        val conversation = MindConversation(listOf(MindMessage.System("sys"), MindMessage.User("Open Instagram")))
        val outcome = MindLoop(model, null, box, ownerMessages = { messages.toList().also { messages.clear() } }).run(conversation)
        assertEquals(MindStatus.COMPLETED, outcome.status)
        assertTrue(conversation.all().any { it is MindMessage.User && it.text.startsWith("Harness note: the owner asked you to remember") })
        assertEquals(MindMemory.OWNER, m.all().single().source)
    }
}
