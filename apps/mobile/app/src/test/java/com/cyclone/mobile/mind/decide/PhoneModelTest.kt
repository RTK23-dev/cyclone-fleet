package com.cyclone.mobile.mind.decide

import com.cyclone.mobile.mind.modes.BoxAnswer
import com.cyclone.mobile.mind.modes.BoxReply
import com.cyclone.mobile.mind.modes.GrammarWorld
import com.cyclone.mobile.mind.modes.InstantIntent
import com.cyclone.mobile.mind.modes.LocalFacts
import com.cyclone.mobile.mind.modes.Mode
import com.cyclone.mobile.mind.modes.ModeRouter
import com.cyclone.mobile.mind.modes.PhoneDecider
import com.cyclone.mobile.mind.modes.Speed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneModelTest {
    private val apps = listOf("Spotify" to "com.spotify.music", "Telegram" to "org.telegram.messenger", "WhatsApp" to "com.whatsapp",
        "Maps" to "com.google.android.apps.maps")
    private val world = GrammarWorld(labels = listOf("Pokémon GO", "Settings"), apps = apps)
    private val candidates = world.labels + apps.map { it.first }
    private val model = PhoneModel.train(emptyList(), 0.8)

    @Test
    fun `the built-in model reads everyday phrasing, picks targets only from the phone, and sends the rest up`() {
        fun g(text: String) = model.guess(text, candidates)
        assertEquals(Guess("instant", "open_app", "Telegram", g("pull up telegram").confidence), g("pull up telegram"))
        assertEquals("flashlight_on", g("put the flashlight on").intent)
        assertEquals("scroll_up", g("go up a bit").intent)
        assertEquals("volume_up", g("make it louder").intent)
        assertEquals("media_next", g("skip this song").intent)
        assertEquals("tap", g("tap pokemon go").intent)
        assertEquals("Pokémon GO", g("tap pokemon go").target)
        assertEquals("mind", g("text mom i'm late").mode)
        assertEquals("mind", g("what's the weather tomorrow").mode)
        assertEquals("flash", g("open settings and turn on wifi").mode)
        assertEquals("ignore", g("yeah that's fine").mode)
        // An app that isn't on the phone is never invented.
        val missing = g("open instagram")
        assertNull(missing.target)
        assertFalse(missing.mode == "instant" && missing.confidence >= Earning.SURE)
    }

    @Test
    fun `JEV teaches it new phrasing, but its own decisions and failures never do`() {
        fun lesson(text: String, decider: Decider, intent: String, outcome: Outcome) =
            Lesson(1, text, emptyList(), decider, Guess("instant", intent, null, 0.95), 400, null, outcome)
        val taught = (1..6).map { lesson("crank it", Decider.DECISIONS, "volume_up", Outcome.VERIFIED) }
        assertEquals("volume_up", PhoneModel.train(taught, 0.8).guess("crank it", candidates).intent)
        assertFalse(lesson("x", Decider.PHONE, "volume_up", Outcome.VERIFIED).teaches(0.8))
        assertFalse(lesson("x", Decider.DECISIONS, "volume_up", Outcome.FAILED).teaches(0.8))
        assertFalse(lesson("x", Decider.DECISIONS, "volume_up", Outcome.VERIFIED).copy(decision = Guess("instant", "volume_up", null, 0.5)).teaches(0.8))
        assertTrue(lesson("x", Decider.GRAMMAR, "volume_up", Outcome.VERIFIED).teaches(0.8))
        assertTrue(Lesson(1, "x", emptyList(), Decider.DECISIONS, Guess("mind", "none", null, 0.9), 400, null, Outcome.HANDED).teaches(0.8))
    }

    @Test
    fun `lessons are bounded, round-trip, and a secret is never kept`() {
        val l = Lesson(5, "open spotify", listOf("Spotify", "code 123456"), Decider.DECISIONS, Guess("instant", "open_app", "Spotify", 0.9), 420,
            Guess("instant", "open_app", "Spotify", 0.97), Outcome.VERIFIED, "JEV")
        val secret = { s: String -> s.contains("123456") }
        val kept = Lessons.keepable(l, secret)!!
        assertEquals(listOf("Spotify"), kept.candidates)
        assertNull(Lessons.keepable(l.copy(request = "my code is 123456"), secret))
        assertEquals(listOf(kept), Lessons.decode(Lessons.encode(listOf(kept))))
        assertTrue(Lessons.decode("garbage\n{}").isEmpty())
        assertEquals(Lessons.MAX_KEPT, Lessons.decode(Lessons.encode(List(Lessons.MAX_KEPT + 5) { kept })).size)
    }

    private fun agreeing(intent: String, n: Int, agree: Boolean = true) = List(n) {
        Lesson(it.toLong(), "turn it up", emptyList(), Decider.DECISIONS, Guess("instant", intent, null, 0.95), 400,
            Guess("instant", if (agree) intent else "volume_down", null, 0.97), Outcome.VERIFIED)
    }

    @Test
    fun `an action is earned after 50 sure agreements at 98 percent, and lost again after its own failures`() {
        assertEquals(emptySet<String>(), Earning.earned(agreeing("volume_up", 49), 0.8))
        assertEquals(setOf("volume_up"), Earning.earned(agreeing("volume_up", 50), 0.8))
        assertEquals(emptySet<String>(), Earning.earned(agreeing("volume_up", 49) + agreeing("volume_up", 2, agree = false), 0.8))
        val own = List(2) { Lesson(0, "louder", emptyList(), Decider.PHONE, Guess("instant", "volume_up", null, 0.97), 3, null, Outcome.FAILED) }
        assertEquals(emptySet<String>(), Earning.earned(agreeing("volume_up", 60) + own, 0.8))
    }

    @Test
    fun `the numbers say who decided, how fast, and what the phone model earned`() {
        val lessons = agreeing("volume_up", 50) + Lesson(0, "swipe up", emptyList(), Decider.GRAMMAR, Guess("instant", "swipe_up", null, 1.0), 2, null, Outcome.VERIFIED)
        val stats = DecisionStats.summary(lessons, 0.8, "JEV (TypeSafe)", "earned", 100)
        assertEquals(51, stats.getInt("lessons"))
        assertEquals(50, stats.getJSONObject("byDecider").getInt("decisions"))
        assertEquals(400L, stats.getJSONObject("decisionsMs").getLong("p50"))
        assertEquals("volume_up", stats.getJSONArray("earned").getString(0))
        assertEquals(1.0, stats.getDouble("shadowAgreement"), 0.0)
        assertFalse("no request text in the numbers", stats.toString().contains("turn it up"))
    }

    private fun decider(earned: Set<String>, mayAct: Boolean = true, audit: Boolean = false) = PhoneDecider(model, earned, mayAct, audit)
    private val facts = LocalFacts()
    private val jev = com.cyclone.mobile.mind.modes.DecisionBox {
        BoxReply(mapOf("route" to BoxAnswer("instant", 0.95), "intent" to BoxAnswer("volume_up", 0.95), "target" to BoxAnswer("none", 0.9)), ms = 420)
    }
    private val noCall = com.cyclone.mobile.mind.modes.DecisionBox { error("JEV must not be asked") }

    @Test
    fun `an earned action is decided on the phone, offline too, and every fifth is still audited by JEV`() {
        val phone = ModeRouter.route("make it louder", world, facts, Speed.AUTO, noCall, 0.8, decider(setOf("volume_up")))
        assertEquals(Mode.INSTANT, phone.mode)
        assertEquals(Decider.PHONE, phone.by)
        assertEquals(InstantIntent.VOLUME, phone.command?.intent)
        val offline = ModeRouter.route("make it louder", world, facts, Speed.AUTO, null, 0.8, decider(setOf("volume_up"), audit = true))
        assertEquals(Decider.PHONE, offline.by)
        // An audit of an easy-to-undo action no longer makes the owner wait: the phone acts and JEV answers in the
        // background (CycloneModes.audit). Learn only still asks JEV first.
        assertEquals(Decider.PHONE, ModeRouter.route("make it louder", world, facts, Speed.AUTO, jev, 0.8, decider(setOf("volume_up"), audit = true)).by)
        val learning = ModeRouter.route("make it louder", world, facts, Speed.AUTO, jev, 0.8, decider(setOf("volume_up"), mayAct = false))
        assertEquals(Decider.DECISIONS, learning.by)
        assertNotNull(learning.shadow)
        assertEquals(420L, learning.decideMs)
    }

    @Test
    fun `an unearned action, Learn only, the rules and the grammar all come first or go to JEV`() {
        // Unearned: an easy-to-undo action the phone model is sure of is done at once (alpha 91)...
        assertEquals(Decider.PHONE, ModeRouter.route("make it louder", world, facts, Speed.AUTO, noCall, 0.8, decider(emptySet())).by)
        // ...but not one with a time or condition in it, which the phone model can't read.
        assertEquals(Decider.DECISIONS, ModeRouter.route("make it louder in five minutes", world, facts, Speed.AUTO, jev, 0.8, decider(emptySet())).by)
        assertEquals(Decider.DECISIONS, ModeRouter.route("make it louder", world, facts, Speed.AUTO, jev, 0.8, decider(setOf("volume_up"), mayAct = false)).by)
        assertEquals(Decider.RULES, ModeRouter.route("text mom i'm late", world, facts, Speed.AUTO, noCall, 0.8, decider(setOf("volume_up"))).by)
        assertEquals(Decider.GRAMMAR, ModeRouter.route("volume up", world, facts, Speed.AUTO, noCall, 0.8, decider(emptySet())).by)
        assertEquals(Decider.SETTING, ModeRouter.route("make it louder", world, facts, Speed.COMMANDS, noCall, 0.8, decider(setOf("volume_up"))).by)
        // Offline, an easy-to-undo action is still done on the phone; anything else goes to the Mind as before.
        assertEquals(Decider.PHONE, ModeRouter.route("make it louder", world, facts, Speed.AUTO, null, 0.8, decider(emptySet())).by)
        val offline = ModeRouter.route("find a good pizza place nearby", world, facts, Speed.AUTO, null, 0.8, decider(emptySet()))
        assertEquals(Mode.MIND, offline.mode)
        assertEquals(Decider.FALLBACK, offline.by)
    }

    @Test
    fun `Auto is the default speed and the phone model uses what it earned`() {
        assertEquals(Speed.AUTO, Speed.of(null))
        assertEquals(PhoneModelUse.EARNED, PhoneModelUse.of(null))
    }

    @Test
    fun `chatter is not a task, a missing app is said, and only easy-to-undo actions skip JEV`() {
        val many = GrammarWorld(labels = emptyList(), apps = apps + listOf("Chrome" to "c", "Calculator" to "k", "YouTube" to "y", "Keep Notes" to "n"))
        for (chatter in listOf("ok", "thanks", "yeah that's fine", "dank je wel", "Thank you!", "laat maar")) {
            assertEquals(chatter, Mode.IGNORE, ModeRouter.route(chatter, many, facts, Speed.AUTO, noCall, 0.8, decider(emptySet())).mode)
        }
        val snapchat = ModeRouter.route("open snapchat", many, facts, Speed.AUTO, noCall, 0.8, decider(emptySet()))
        assertEquals(Mode.ANSWER, snapchat.mode)
        assertEquals("I don't see Snapchat on this phone.", snapchat.answer)
        // Never claimed for things that aren't apps, for a near name, or when the app list is too short to trust.
        assertNull(ModeRouter.missingApp("open wifi settings", many))
        assertNull(ModeRouter.missingApp("open google keep", many))
        assertNull(ModeRouter.missingApp("open snapchat", GrammarWorld(apps = apps.take(2))))
        for (phrase in listOf("pull up telegram", "kill the flashlight", "turn the sound down a notch", "take me to the home screen")) {
            val r = ModeRouter.route(phrase, many, facts, Speed.AUTO, noCall, 0.8, decider(emptySet()))
            assertEquals(phrase, Decider.PHONE, r.by)
            assertEquals(phrase, Mode.INSTANT, r.mode)
        }
        assertFalse("taps are never done unearned", "tap" in ModeRouter.REVERSIBLE)
        assertFalse(ModeRouter.plainNow("turn the flashlight on at 9"))
        assertEquals(Decider.DECISIONS, ModeRouter.route("make it louder", many, facts, Speed.AUTO, jev, 0.8,
            decider(emptySet(), mayAct = false)).by)
    }
}
