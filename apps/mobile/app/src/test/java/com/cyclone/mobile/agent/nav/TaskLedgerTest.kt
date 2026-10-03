package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.brain.graphv2.AtlasPersona
import org.junit.Assert.*
import org.junit.Test

class TaskLedgerTest {
    private val place = "package:com.google.android.gm"
    private val room = "screen:account:aaaaaaaaaaaaaaaa"

    @Test fun carriesFreshIdentityAcrossAppsButMasksExports() {
        val ledger = TaskLedger(100)
        assertTrue(ledger.record("signed-in-email", "jane@gmail.com", place, room, AtlasPersona.LIVE, 120))
        assertEquals("jane@gmail.com", ledger.get("signed-in-email")!!.value)
        assertTrue(ledger.modelContext().toString().contains("jane@gmail.com"))
        assertFalse(ledger.maskedTrace().toString().contains("jane@gmail.com"))
        assertTrue(ledger.maskedTrace().toString().contains("j***@gmail.com"))
        assertFalse(ledger.entries().toString().contains("jane@gmail.com"))
    }

    @Test fun rejectsDummyStaleSecretAndPaymentValues() {
        val ledger = TaskLedger(100)
        assertFalse(ledger.record("signed-in-email", "dummy@gmail.com", place, room, AtlasPersona.MAPPING, 120))
        assertFalse(ledger.record("signed-in-email", "old@gmail.com", place, room, AtlasPersona.LIVE, 99))
        for ((key, value) in listOf("password" to "hunter2", "otp" to "123456",
            "found-username" to "token=secret123", "found-username" to "4111 1111 1111 1111",
            "typed-text" to "anything")) {
            assertFalse(ledger.record(key, value, place, room, AtlasPersona.LIVE, 120))
        }
        assertTrue(ledger.entries().isEmpty())
    }

    @Test fun inboxSenderCannotBecomeCurrentIdentity() {
        assertTrue(LiveTaskFacts.signedInEmails(MultiAppScenarioTest.page(
            "com.google.android.gm", "Inbox", "stranger@gmail.com", "Compose")).isEmpty())
        assertEquals(listOf("jane@gmail.com"), LiveTaskFacts.signedInEmails(MultiAppScenarioTest.page(
            "com.google.android.gm", "Google account", "Manage your Google Account", "jane@gmail.com")))
        assertTrue(LiveTaskFacts.signedInEmails(MultiAppScenarioTest.page(
            "com.android.chrome", "Google account", "Manage your Google Account", "jane@gmail.com")).isEmpty())
    }

    @Test fun runsHaveIndependentLedgersAndAmbiguousAccountsRemainAmbiguous() {
        val first = TaskLedger(0)
        first.record("signed-in-email", "jane@gmail.com", place, room, AtlasPersona.LIVE, 10)
        assertNull(TaskLedger(0).get("signed-in-email"))
        assertEquals(2, LiveTaskFacts.signedInEmails(MultiAppScenarioTest.page(
            "com.google.android.gm", "Accounts", "Manage your Google Account", "jane@gmail.com", "other@gmail.com")).size)
    }
}
