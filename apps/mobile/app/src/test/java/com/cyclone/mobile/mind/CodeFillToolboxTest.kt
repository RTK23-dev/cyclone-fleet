package com.cyclone.mobile.mind

import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.codes.CodeCatcher
import com.cyclone.mobile.codes.SmsLine
import com.cyclone.mobile.mind.PhoneMindToolboxTest.Control
import com.cyclone.mobile.mind.PhoneMindToolboxTest.FakeEnv
import com.cyclone.mobile.mind.PhoneMindToolboxTest.FakeOwner
import com.cyclone.mobile.mind.PhoneMindToolboxTest.FakeScreen
import com.cyclone.mobile.mind.signup.AccountSetupPlan
import com.cyclone.mobile.mind.signup.AccountSetupProgress
import com.cyclone.mobile.mind.signup.SignupCheck
import com.cyclone.mobile.mind.signup.SignupField
import com.cyclone.mobile.mind.signup.SignupFieldKind
import com.cyclone.mobile.mind.signup.SignupMap
import com.cyclone.mobile.mind.signup.SignupPage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 49: a code sent by text to this phone's number fills itself, inside the real toolbox, with a fake inbox. */
class CodeFillToolboxTest {
    private val device = object : MindDevicePort {
        override fun apps() = listOf(MindApp("com.instagram.android", "Instagram"), MindApp("com.revolut.revolut", "Revolut"))
        override fun now() = "Friday 10:00"
        override fun device() = "Test phone"
        override fun sleep(ms: Long) {}
    }
    private val codePage = FakeScreen("com.instagram.android", listOf(Control("code", "Confirmation code", "edit_text", editable = true), Control("next", "Next")),
        listOf("Enter the 6-digit code we sent to +31 6 •••• 5678"))

    /** This phone: one number, a fake inbox on a clock that moves only when the catcher waits. */
    class FakeCodes(val numbers: List<String> = listOf("+31 6 1234 5678"), var ready: Boolean = true) : MindCodes {
        /** Texts arrive after the mission started, as on a phone. */
        var now = System.currentTimeMillis() + 60_000
        val inbox = mutableListOf<SmsLine>()
        val filled = mutableListOf<String>()
        var accept: (String) -> Boolean = { true }
        var onFill: (String) -> Unit = {}
        override fun ready() = ready
        override fun numbers() = numbers
        override fun subscriptionOf(number: String?) = -1
        override fun catcher() = CodeCatcher({ since -> inbox.filter { it.atMs >= since } }, { now }, { now += it })
        override fun fill(page: AgentPageCard, target: MindRef, code: String): Boolean {
            filled += code
            onFill(code)
            return accept(code)
        }
        fun text(from: String, body: String) { inbox += SmsLine(from, body, now) }
    }

    private fun box(env: FakeEnv, codes: FakeCodes?, owner: FakeOwner = FakeOwner(), goal: String = "Make an Instagram account",
        setup: AccountSetupPlan? = null, progress: MutableList<AccountSetupProgress>? = null) =
        PhoneMindToolbox(env, owner, device, goal, codes = codes, setup = setup, setupProgress = progress?.let { list -> { p -> list += p } })

    private fun MindToolbox.run(name: String, args: String) = execute(MindToolCall("c", name, args), JSONObject(args))
    private fun MindToolbox.fillCode() = run("screen_read", "{}").let { run("vault_fill", """{"ref":"e1","what":"one_time_code","reason":"Sign up"}""") }

    @Test fun aCodeToThisPhoneFillsItselfAndTheModelNeverSeesIt() {
        val codes = FakeCodes().apply { text("Instagram", "123456 is your Instagram code. Don't share it.") }
        val owner = FakeOwner()
        val result = box(FakeEnv(codePage), codes, owner).fillCode()
        assertTrue(result.text, result.ok)
        assertEquals(listOf("123456"), codes.filled)
        assertTrue("no Secrets Card", owner.secretSlots.isEmpty())
        assertFalse(result.text.contains("123456"))
        assertFalse(result.brief.contains("123456"))
        assertTrue(result.text, result.text.contains("from a text on this phone"))
    }

    @Test fun aCodeThatIsNotObviouslyForThisPhoneStillAsks() {
        val elsewhere = FakeScreen("com.instagram.android", codePage.controls, listOf("Enter the code we sent to •••• 9999"))
        val codes = FakeCodes().apply { text("Instagram", "123456 is your Instagram code") }
        val owner = FakeOwner()
        val result = box(FakeEnv(elsewhere), codes, owner).fillCode()
        assertEquals(listOf("otp"), owner.secretSlots)
        assertTrue(codes.filled.isEmpty())
        assertTrue(result.text, result.text.contains("isn't on this phone"))
    }

    @Test fun bankingCodesAreNeverFilledFromTexts() {
        val bank = FakeScreen("com.revolut.revolut", listOf(Control("code", "Verification code", "edit_text", editable = true)),
            listOf("We sent a code to +31 6 •••• 5678"))
        val codes = FakeCodes().apply { text("Revolut", "Revolut code 123456") }
        val owner = FakeOwner()
        box(FakeEnv(bank), codes, owner).fillCode()
        assertEquals(listOf("otp"), owner.secretSlots)
        assertTrue(codes.filled.isEmpty())
    }

    @Test fun whenTheAppFilledItsOwnCodeCycloneTypesNothing() {
        val filledByApp = FakeScreen("com.instagram.android", codePage.controls, codePage.text, values = mapOf("code" to "482913"))
        val codes = FakeCodes()
        val result = box(FakeEnv(filledByApp), codes).fillCode()
        assertTrue(result.text, result.text.contains("The app filled the code itself"))
        assertTrue(codes.filled.isEmpty())
    }

    @Test fun splitBoxesAreFilledOneCharacterEach() {
        val boxes = FakeScreen("com.instagram.android", (1..6).map { Control("d$it", "Code digit $it", "edit_text", editable = true) },
            listOf("Enter the code sent to +31 6 •••• 5678"))
        val codes = FakeCodes().apply { text("Instagram", "Your Instagram code: 654321") }
        val result = box(FakeEnv(boxes), codes).fillCode()
        assertTrue(result.text, result.ok)
        assertEquals(listOf("6", "5", "4", "3", "2", "1"), codes.filled)
    }

    @Test fun aWrongCodeGetsOneResendAndTheNewCode() {
        val wrong = FakeScreen("com.instagram.android", codePage.controls + Control("resend", "Resend code"), listOf("That code isn't valid. Try again."))
        val env = FakeEnv(codePage)
        val codes = FakeCodes().apply { text("Instagram", "111111 is your Instagram code") }
        codes.onFill = { code -> if (code == "111111") env.screen = wrong else env.screen = codePage }
        env.onAct = { tool, params ->
            if (tool == "phone.click" && params.optString("elementId").endsWith(":resend")) {
                codes.now += 2_000
                codes.text("Instagram", "222222 is your Instagram code")
            }
        }
        val result = box(env, codes).fillCode()
        assertTrue(result.text, result.ok)
        assertEquals(listOf("111111", "222222"), codes.filled)
        assertTrue(env.acts.any { it.first == "phone.click" && it.second.optString("elementId").endsWith(":resend") })
    }

    @Test fun noTextMeansOneResendThenTheOwner() {
        val withResend = FakeScreen("com.instagram.android", codePage.controls + Control("resend", "Resend code"), codePage.text)
        val env = FakeEnv(withResend)
        val codes = FakeCodes()
        val owner = FakeOwner()
        val result = box(env, codes, owner).fillCode()
        assertEquals(1, env.acts.count { it.first == "phone.click" })
        assertEquals(listOf("otp"), owner.secretSlots)
        assertTrue(result.text, result.text.contains("No code reached this phone"))
    }

    @Test fun accountSetupWithThisPhonesNumberFillsTheCodePageItself() {
        val map = SignupMap("com.instagram.android", "Instagram", "1.0", 0, listOf(
            SignupPage(1, "Phone", listOf(SignupField("phone", "Mobile number", SignupFieldKind.PHONE, true)), "Next"),
            SignupPage(2, "Confirm", listOf(SignupField("code", "Confirmation code", SignupFieldKind.TEXT, true)), "Next", SignupCheck.SMS_CODE),
        ), "Sign up", true)
        val plan = AccountSetupPlan(map, mapOf("phone" to "06 1234 5678"))
        assertTrue(plan.promptText().contains("Cyclone fills it from the text itself"))
        val plainPage = FakeScreen("com.instagram.android", codePage.controls, listOf("Enter the confirmation code"))
        val codes = FakeCodes().apply { text("32665", "Use 778899 to confirm your Instagram account") }
        val progress = mutableListOf<AccountSetupProgress>()
        val owner = FakeOwner()
        val toolbox = box(FakeEnv(plainPage), codes, owner, setup = plan, progress = progress)
        toolbox.run("screen_read", "{}")
        val result = toolbox.run("setup_page", """{"page":2,"check":"sms_code"}""")
        assertTrue(result.text, result.ok)
        assertEquals(listOf("778899"), codes.filled)
        assertTrue(owner.asked.isEmpty() && owner.secretSlots.isEmpty())
        assertTrue(progress.any { it.state == AccountSetupProgress.CODE && it.note == "Waiting for the code on this phone" })
        assertEquals(AccountSetupProgress.FILLING, progress.last().state)
    }

    @Test fun withoutTheCodesPortEveryCodeAsks() {
        val owner = FakeOwner()
        box(FakeEnv(codePage), null, owner).fillCode()
        assertEquals(listOf("otp"), owner.secretSlots)
    }
}
