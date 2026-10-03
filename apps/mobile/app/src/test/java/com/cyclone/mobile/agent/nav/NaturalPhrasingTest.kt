package com.cyclone.mobile.agent.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How people actually phrase multi-step asks (no "then" required). Each row: the sentence, the expected clauses as
 * capability@place (place "*" = not asserted, "-" = none). The first row is the alpha.22 sentence that failed on the
 * phone: one clause bound to Chrome, sign-up dropped.
 */
class NaturalPhrasingTest {
    private val GM = "package:com.google.android.gm"

    private val table: List<Pair<String, List<String>>> = listOf(
        "check my current logged in gmail and make a Facebook account with that Gmail on chrome Facebook.com" to
            listOf("FIND_SIGNED_IN_IDENTITY@$GM", "CREATE_ACCOUNT@chrome:https://facebook.com"),
        "open Gmail and tell me which Gmail I am logged in with" to listOf("FIND_SIGNED_IN_IDENTITY@$GM"),
        "open Gmail and check which Gmail I am logged in with, then open Chrome and go to instagram.com sign-up with that email" to
            listOf("FIND_SIGNED_IN_IDENTITY@$GM", "CREATE_ACCOUNT@chrome:https://instagram.com"),
        "which gmail am I logged into and then sign up for instagram with it" to
            listOf("FIND_SIGNED_IN_IDENTITY@$GM", "CREATE_ACCOUNT@package:com.instagram.android"),
        "find out which email is signed in on gmail and register on reddit.com with that email" to
            listOf("FIND_SIGNED_IN_IDENTITY@$GM", "CREATE_ACCOUNT@chrome:https://reddit.com"),
        "look up which google account is logged in on gmail and create an instagram account with that email on instagram.com" to
            listOf("FIND_SIGNED_IN_IDENTITY@$GM", "CREATE_ACCOUNT@chrome:https://instagram.com"),
        "open gmail en kijk met welk account ik ben ingelogd, daarna maak een facebook account aan met dat account" to
            listOf("FIND_SIGNED_IN_IDENTITY@$GM", "CREATE_ACCOUNT@package:com.facebook.katana"),
        "open the clock app and set a timer for 5 minutes" to listOf("SET_TIMER@-"),
        "open clock and set an alarm for 5 minutes" to listOf("SET_ALARM@-"),
        "set an alarm for 7 am and then open whatsapp" to listOf("SET_ALARM@-", "OPEN_PLACE@package:com.whatsapp"),
        "set a timer for 10 minutes and then open youtube" to listOf("SET_TIMER@-", "OPEN_PLACE@package:com.google.android.youtube"),
        "wekker om 6.45" to listOf("SET_ALARM@-"),
        "zet een timer van 5 minuten" to listOf("SET_TIMER@-"),
        "find the DM of Louella on Facebook" to listOf("OPEN_DM@package:com.facebook.katana"),
        "open instagram and find the chat with Tom" to listOf("OPEN_DM@package:com.instagram.android"),
        "open Settings, then Wi-Fi, then tell me the connected network name" to listOf(
            "OPEN_PLACE@package:com.android.settings", "OPEN_WIFI@package:com.android.settings", "READ_NETWORK@package:com.android.settings"),
        "check my gmail inbox and open the settings app" to listOf("USER_GOAL@$GM", "OPEN_PLACE@package:com.android.settings"),
        "open youtube" to listOf("OPEN_PLACE@package:com.google.android.youtube"),
        "open chrome and go to nu.nl" to listOf("OPEN_PLACE@chrome:https://nu.nl"),
        "open chrome, go to nu.nl" to listOf("*@chrome:https://nu.nl"),
        "open whatsapp and send a message to Anna" to listOf("USER_GOAL@package:com.whatsapp"),
        "open facebook in chrome" to listOf("*@chrome:https://facebook.com"),
        "go to reddit.com and search for android" to listOf("USER_GOAL@chrome:https://reddit.com"),
        "open settings and turn on dark theme" to listOf("USER_GOAL@package:com.android.settings"),
        "search for pizza on youtube" to listOf("USER_GOAL@package:com.google.android.youtube"),
        "open youtube and play lofi music" to listOf("USER_GOAL@package:com.google.android.youtube"),
        "open maps and navigate to Amsterdam" to listOf("USER_GOAL@package:com.google.android.apps.maps"),
        "check which gmail is signed in and tell me" to listOf("FIND_SIGNED_IN_IDENTITY@$GM"),
        "take a photo and send it to Anna on whatsapp" to listOf("*@*", "USER_GOAL@package:com.whatsapp"),
        "log in to reddit.com" to listOf("*@chrome:https://reddit.com"),
    )

    @Test fun everySentenceCompilesToItsClauses() {
        val failures = table.mapNotNull { (goal, expected) ->
            val actual = ClauseCompiler.compile(goal) { true }.map { "${it.capability}@${it.place ?: "-"}" }
            val ok = actual.size == expected.size && actual.zip(expected).all { (a, e) ->
                val (ec, ep) = e.split('@', limit = 2)
                val (ac, ap) = a.split('@', limit = 2)
                (ec == "*" || ec == ac) && (ep == "*" || ep == ap)
            }
            if (ok) null else "$goal\n   expected $expected\n   actual   $actual"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("the table covers at least 30 phrasings", table.size >= 30)
    }

    @Test fun reusedFactsAreMarkedAndNeverBecomeADestination() {
        val clauses = ClauseCompiler.compile(table[0].first) { true }
        assertEquals("signed-in-email", clauses[1].consumes)
        assertEquals(null, clauses[0].consumes)
        // "with that Gmail" must not bind the sign-up clause to the Gmail app.
        assertFalse(clauses[1].place!!.contains("com.google.android.gm"))
    }

    @Test fun mergedClausesKeepTheOwnersWords() {
        val nl = ClauseCompiler.compile(table[6].first) { true }
        assertEquals("open gmail en kijk met welk account ik ben ingelogd", nl[0].text)
    }

    @Test fun plainOpenGoalsStillSkipClauseRuns() {
        listOf("open youtube", "open chrome and go to nu.nl", "open whatsapp and send a message to Anna").forEach {
            assertFalse(it, ClauseCompiler.needsClauseRun(ClauseCompiler.compile(it) { true }))
        }
    }
}
