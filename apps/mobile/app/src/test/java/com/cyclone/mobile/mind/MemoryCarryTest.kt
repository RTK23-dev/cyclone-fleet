package com.cyclone.mobile.mind

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Plan 40 P2: people memory shared across profiles, labelled by the profile it came from. */
class MemoryCarryTest {
    private var now = 1_000L
    private fun memory() = MindMemory(Files.createTempFile("mind", ".json").toFile(), { now })

    /** Carries [from]'s memories (it is profile [fromId], named [fromLabel]) into [to] (profile [toId]). */
    private fun carry(from: MindMemory, fromId: String, fromLabel: String, to: MindMemory, toId: String): MemoryCarry.Result {
        val wire = from.export(fromId, fromLabel).toJson().toString()
        return to.absorb(MemoryCarry.Parcel.fromJson(JSONObject(wire)), toId)
    }

    @Test fun peopleFromAnotherProfileArriveLabelledByThatProfile() {
        val work = memory()
        val main = memory()
        work.remember(MindMemory.Candidate("Louella", person = "Louella", relation = "colleague", app = "Slack", handle = "lou"))
        main.remember(MindMemory.Candidate("Louella", person = "Louella", relation = "girlfriend"))
        val result = carry(work, "Cyclone_aaaaaaaaaaaaaaaa", "Work", main, "main")
        assertEquals(1, result.added)
        val cards = main.all().filter { it.kind == MindMemory.PERSON }
        assertEquals(2, cards.size)
        val fromWork = cards.single { it.profile != null }
        assertEquals("Cyclone_aaaaaaaaaaaaaaaa", fromWork.profile)
        assertEquals("Work", fromWork.profileLabel)
        assertEquals("f1", fromWork.origin)
        assertEquals("colleague", fromWork.relation)
        // Local ids stay unique, so the Mind can still say which one it means.
        assertEquals(cards.size, cards.map { it.id }.toSet().size)
        assertTrue(main.digest().contains("(from Work)"))
        // What this profile learns next about Louella goes on its own card, never on Work's.
        main.remember(MindMemory.Candidate("Louella", person = "Louella", app = "Instagram", handle = "lo.06"))
        assertTrue(main.all().single { it.profile != null }.handles.keys == setOf("Slack"))
        assertEquals("lo.06", main.all().single { it.profile == null && it.kind == MindMemory.PERSON }.handles["Instagram"])
    }

    @Test fun aRoundTripDoesNotDuplicateAndOwnMemoriesComeHomeAsOwn() {
        val work = memory()
        val main = memory()
        main.remember("Owner prefers Dutch replies")
        carry(main, "main", "Profile A", work, "Cyclone_aaaaaaaaaaaaaaaa")
        carry(work, "Cyclone_aaaaaaaaaaaaaaaa", "Work", main, "main")
        carry(main, "main", "Profile A", work, "Cyclone_aaaaaaaaaaaaaaaa")
        assertEquals(1, main.all().size)
        assertNull(main.all().single().profile)
        assertEquals(1, work.all().size)
        assertEquals("main", work.all().single().profile)
    }

    @Test fun newerWordsWinAndForgettingTravels() {
        val work = memory()
        val main = memory()
        main.remember("Owner prefers Dutch replies")
        carry(main, "main", "Profile A", work, "Cyclone_aaaaaaaaaaaaaaaa")
        now = 5_000
        val id = work.all().single().id
        assertTrue(work.remember(MindMemory.Candidate("Owner prefers English replies at work", replaces = id)) is MindMemory.Saved.Updated)
        val back = carry(work, "Cyclone_aaaaaaaaaaaaaaaa", "Work", main, "main")
        assertEquals(1, back.updated)
        assertEquals("Owner prefers English replies at work", main.all().single().text)
        assertNull(main.all().single().profile)

        now = 9_000
        assertTrue(main.forget(main.all().single().id))
        val removed = carry(main, "main", "Profile A", work, "Cyclone_aaaaaaaaaaaaaaaa")
        assertEquals(1, removed.removed)
        assertTrue(work.all().isEmpty())
        // And it doesn't come back the other way.
        carry(work, "Cyclone_aaaaaaaaaaaaaaaa", "Work", main, "main")
        assertTrue(main.all().isEmpty())
    }

    @Test fun secretsNeverTravel() {
        val facts = listOf(MindFact("f1", "wachtwoord is Zomer2024", 1, 1))
        assertTrue(MemoryCarry.export(facts, emptyList(), "main", "A", 10).facts.isEmpty())
        val parcel = MemoryCarry.Parcel(listOf(MindFact("f9", "the verification code is 123456", 1, 1, profile = "Cyclone_aaaaaaaaaaaaaaaa")), emptyList())
        assertTrue(MemoryCarry.absorb(emptyList(), emptyList(), parcel, "main", 10).facts.isEmpty())
    }

    @Test fun missionLinksStayHomeAndUnlabelledMemoriesAreRefused() {
        val parcel = MemoryCarry.export(listOf(MindFact("f1", "Owner likes window seats", 1, 1, missionId = "m-1")), emptyList(), "main", "A", 10)
        assertNull(parcel.facts.single().missionId)
        val unlabelled = MemoryCarry.Parcel(listOf(MindFact("f1", "Owner likes aisle seats", 1, 1)), emptyList())
        assertTrue(MemoryCarry.absorb(emptyList(), emptyList(), unlabelled, "main", 10).facts.isEmpty())
    }

    @Test fun forgottenRecordsAreTidy() {
        val day = 24L * 60 * 60_000
        val gone = listOf(ForgottenFact(null, "f1", 1), ForgottenFact(null, "f1", 5 * day), ForgottenFact("x", "f2", 200 * day))
        val tidy = MemoryCarry.tidy(gone, 60 * day)
        assertEquals(listOf(ForgottenFact("x", "f2", 200 * day), ForgottenFact(null, "f1", 5 * day)), tidy)
        assertFalse(MemoryCarry.tidy(gone, 200 * day).any { it.id == "f1" })
    }

    @Test fun groupsThisProfileFirstThenOthersByName() {
        val facts = listOf(
            MindFact("f1", "a", 1, 1, profile = "p2", profileLabel = "Work"),
            MindFact("f2", "b", 1, 1),
            MindFact("f3", "c", 1, 1, profile = "p1", profileLabel = "Clients"),
        )
        assertEquals(listOf(null, "p1", "p2"), MemoryCarry.byProfile(facts).map { it.first })
    }

    @Test fun olderMemoryFilesStillRead() {
        val legacy = JSONObject("""{"id":"f1","text":"Owner likes tea","created":5,"used":6}""")
        val fact = MindFact.fromJson(legacy)
        assertNull(fact.profile)
        assertEquals(5, fact.changed)
        assertEquals(fact, MindFact.fromJson(fact.toJson()))
    }
}
