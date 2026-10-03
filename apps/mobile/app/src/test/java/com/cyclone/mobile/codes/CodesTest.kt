package com.cyclone.mobile.codes

import com.cyclone.mobile.codes.AutoCodePolicy.Decision
import com.cyclone.mobile.codes.AutoCodePolicy.Facts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 49: reading a code from a text, deciding when to fill it without asking, and waiting for the right one. */
class CodesTest {
    // ---- CodeExtractor ------------------------------------------------------------------------------------------

    @Test fun realMessageShapesGiveTheirCode() {
        val corpus = mapOf(
            "123456 is your Instagram code. Don't share it." to "123456",
            "<#> 482 913 is your Instagram code. Don't share it. nPQ8+aBcDeF" to "482913",
            "G-731905 is your Google verification code." to "731905",
            "Your WhatsApp code: 381-224\nDon't share this code with others\n4sgLq1p5sV6" to "381224",
            "[TikTok] 7741 is your verification code, valid for 5 minutes. To keep your account safe, never forward this code." to "7741",
            "Use 9182 7364 as Microsoft account security code" to "91827364",
            "Je verificatiecode voor Marktplaats is 552190. Deel deze code met niemand." to "552190",
            "Uw inlogcode is: 0042" to "0042",
            "Your Uber code: 4821. Never share this code." to "4821",
            "Telegram code: 64018\n\nYou can also tap on this link to log in: https://t.me/login/64018" to "64018",
            "Your verification code is AB12CD" to "AB12CD",
            "Bol: je code is 8890. Je bestelling van € 12,50 is onderweg." to "8890",
            "Your code is 123456. Order 2026 total \$45.00" to "123456",
        )
        corpus.forEach { (text, code) -> assertEquals(text, code, CodeExtractor.extract(text)) }
    }

    @Test fun amountsDatesTimesAndPhoneNumbersAreNotCodes() {
        listOf(
            "You paid € 4500 to Albert Heijn",
            "Je pakket komt op 12-10-2026 tussen 14:30 en 16:30",
            "Call us on +31 20 123 4567",
            "Happy 2026 from all of us!",
            "Meeting moved to room 4.12",
            "Your balance is 1234,56 EUR",
        ).forEach { assertNull(it, CodeExtractor.extract(it)) }
    }

    @Test fun twoDifferentCodesAreAmbiguous() {
        assertNull(CodeExtractor.extract("Your code is 111111. Your old code 222222 no longer works."))
    }

    @Test fun aLoneNumberCountsOnlyWhenItIsTheOnlyOne() {
        assertEquals("583920", CodeExtractor.extract("583920"))
        assertNull(CodeExtractor.extract("583920 and 112233"))
    }

    // ---- AutoCodePolicy -----------------------------------------------------------------------------------------

    private val mine = listOf("+31 6 1234 5678")
    private fun decide(facts: Facts) = AutoCodePolicy.decide(facts)

    @Test fun accountSetupWithThisPhonesNumberFillsWithoutAsking() {
        val r = decide(Facts(true, mine, setupNumber = "0612345678", screenText = "Enter the 6-digit code"))
        assertEquals(Decision.AUTO, r.decision)
        assertEquals(Decision.ASK, decide(Facts(true, mine, setupNumber = "+44 7700 900123")).decision)
    }

    @Test fun theCodePageSayingWhereItWentWins() {
        assertEquals(Decision.AUTO, decide(Facts(true, mine, screenText = "We sent a code to +31 6 •••• 5678")).decision)
        assertEquals(Decision.AUTO, decide(Facts(true, mine, screenText = "Enter the code sent to the number ending in 5678")).decision)
        val elsewhere = decide(Facts(true, mine, setupNumber = "0612345678", screenText = "We sent a code to •••• 9999"))
        assertEquals(Decision.ASK, elsewhere.decision)
        assertTrue(elsewhere.why, elsewhere.why.contains("isn't on this phone"))
    }

    @Test fun aTypedNumberOrTheOwnersWordsMakeItObvious() {
        assertEquals(Decision.AUTO, decide(Facts(true, mine, typedNumbers = listOf("612345678"))).decision)
        assertEquals(Decision.AUTO, decide(Facts(true, mine, goal = "Sign up for Vinted with my number")).decision)
        assertEquals(Decision.AUTO, decide(Facts(true, mine, goal = "Maak een Marktplaats account met 06-12345678")).decision)
        assertEquals(Decision.ASK, decide(Facts(true, mine, goal = "Sign up for Vinted")).decision)
    }

    @Test fun neverForLabPrivateAppsOrPayments() {
        assertEquals(Decision.NEVER, decide(Facts(true, mine, lab = true, setupNumber = "0612345678")).decision)
        assertEquals(Decision.NEVER, decide(Facts(true, mine, keptPrivate = true, setupNumber = "0612345678")).decision)
        assertEquals(Decision.NEVER, decide(Facts(true, mine, setupNumber = "0612345678", screenText = "Confirm your payment of € 20 with the code")).decision)
    }

    @Test fun offOrUnknownNumbersAsk() {
        assertEquals(Decision.ASK, decide(Facts(false, mine, setupNumber = "0612345678")).decision)
        assertEquals(Decision.ASK, decide(Facts(true, emptyList(), setupNumber = "0612345678")).decision)
    }

    // ---- CodeCatcher --------------------------------------------------------------------------------------------

    private class Clock(var now: Long = 1_000_000L)

    private fun catcher(clock: Clock, inbox: () -> List<SmsLine>) =
        CodeCatcher({ since -> inbox().filter { it.atMs >= since } }, { clock.now }, { clock.now += it })

    @Test fun theNamedCodeInTheWindowIsCaught() {
        val clock = Clock()
        val texts = mutableListOf(
            SmsLine("Instagram", "111111 is your Instagram code", clock.now - 200_000), // before the window
            SmsLine("+3197010", "Your Bol code is 222222", clock.now - 1_000),
        )
        var polls = 0
        val result = catcher(clock) {
            if (++polls == 3) texts += SmsLine("32665", "333333 is your Instagram code", clock.now)
            texts
        }.await(CodeCatcher.Ask(listOf("Instagram"), clock.now - 90_000))
        assertEquals("333333", (result as CodeCatcher.Result.Caught).code)
        assertTrue(result.named)
    }

    @Test fun anUnnamedCodeCountsOnlyAlone() {
        val clock = Clock()
        val at = clock.now
        val one = catcher(clock) { listOf(SmsLine("12345", "Your code is 445566", at)) }
            .await(CodeCatcher.Ask(listOf("Vinted"), clock.now - 1_000))
        assertEquals("445566", (one as CodeCatcher.Result.Caught).code)
        val two = catcher(clock) { listOf(SmsLine("1", "Your code is 445566", at), SmsLine("2", "Code 778899", at)) }
            .await(CodeCatcher.Ask(listOf("Vinted"), at - 1_000, timeoutMs = 5_000))
        assertTrue((two as CodeCatcher.Result.Missed).reason.contains("more than one code"))
    }

    @Test fun aRefusedCodeIsNeverUsedAgainAndTimeOutsAreHonest() {
        val clock = Clock()
        val texts = listOf(SmsLine("Instagram", "123456 is your Instagram code", clock.now))
        val missed = catcher(clock) { texts }.await(CodeCatcher.Ask(listOf("Instagram"), clock.now - 1_000, timeoutMs = 10_000, refused = setOf("123456")))
        assertEquals("no text with a code arrived in time", (missed as CodeCatcher.Result.Missed).reason)
        val stopped = catcher(clock) { emptyList() }.await(CodeCatcher.Ask(listOf("x"), clock.now), cancelled = { true })
        assertEquals("the run stopped", (stopped as CodeCatcher.Result.Missed).reason)
    }

    @Test fun textsToTheOtherSimDontCount() {
        val clock = Clock()
        val result = catcher(clock) { listOf(SmsLine("Instagram", "123456 is your Instagram code", clock.now, subscriptionId = 2)) }
            .await(CodeCatcher.Ask(listOf("Instagram"), clock.now - 1_000, timeoutMs = 3_000, subscriptionId = 1))
        assertTrue(result is CodeCatcher.Result.Missed)
    }
}
