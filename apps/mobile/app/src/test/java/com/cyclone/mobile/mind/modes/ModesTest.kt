package com.cyclone.mobile.mind.modes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 42: the decision box, Instant's grammar, the router and Instant mode, with the owner's own examples. */
class ModesTest {
    private val home = GrammarWorld(
        labels = listOf("Pokémon GO", "Instagram", "Camera", "Photos", "Clock"),
        apps = listOf("Pokémon GO" to "com.nianticlabs.pokemongo", "Instagram" to "com.instagram.android", "Camera" to "com.google.android.GoogleCamera",
            "WhatsApp" to "com.whatsapp", "Settings" to "com.android.settings", "Maps" to "com.google.android.apps.maps"),
    )

    // ---- the grammar -------------------------------------------------------------------------------------------------

    private fun match(text: String, world: GrammarWorld = home) = (InstantGrammar.parse(text, world) as GrammarResult.Match).command

    @Test fun theOwnersExamplesAreInstantCommands() {
        assertEquals(InstantCommand(InstantIntent.SWIPE, "swipe up", direction = "up"), match("swipe up"))
        assertEquals("up", match("Swipe up!").direction)
        match("click Pokemon Go").let { assertEquals(InstantIntent.TAP, it.intent); assertEquals("Pokémon GO", it.target) }
        assertEquals(InstantIntent.SELFIE, match("take a picture of me").intent)
        assertEquals(InstantIntent.PHOTO, match("take a picture").intent)
        assertEquals(InstantIntent.CAMERA, match("open my camera").intent)
        match("call my mom").let { assertEquals(InstantIntent.CALL, it.intent); assertEquals("mom", it.target) }
    }

    @Test fun moreCommandsInEnglishAndDutch() {
        assertEquals("down", match("scroll down a bit").direction)
        assertEquals("left", match("veeg naar links").direction)
        assertEquals(InstantIntent.BACK, match("go back").intent)
        assertEquals(InstantIntent.BACK, match("ga terug").intent)
        assertEquals(InstantIntent.HOME, match("go home").intent)
        match("open Instagram").let { assertEquals(InstantIntent.OPEN_APP, it.intent); assertEquals("com.instagram.android", it.target) }
        assertEquals("com.nianticlabs.pokemongo", match("open pokemongo").target)
        assertEquals("on", match("turn on the flashlight").direction)
        assertEquals("off", match("zaklamp uit").direction)
        assertEquals("up", match("volume up").direction)
        assertEquals("next", match("next song").direction)
        assertEquals(300, match("set a timer for 5 minutes").seconds)
        match("wake me up at 7 30").let { assertEquals(7, it.hour); assertEquals(30, it.minute) }
        assertEquals(InstantIntent.SELFIE, match("maak een foto van mij").intent)
        assertEquals("mam", match("bel mam").target)
        assertEquals(InstantIntent.CALL, match("can you please call dad").intent)
    }

    @Test fun sentencesAndUnknownTargetsAreNotCommands() {
        listOf("open the camera and take a picture", "message Lou that I'm late", "call", "call me", "open Netflix",
            "tap the purple thing", "what's the weather in Amsterdam tomorrow afternoon for my trip").forEach {
            assertTrue(it, InstantGrammar.parse(it, home) !is GrammarResult.Match)
        }
    }

    @Test fun twoCloseMatchesAreAmbiguousNotAGuess() {
        val world = GrammarWorld(labels = listOf("Photos", "Photo Editor"))
        val result = InstantGrammar.parse("tap photo", world)
        assertTrue(result.toString(), result is GrammarResult.Ambiguous)
        assertEquals(setOf("Photos", "Photo Editor"), (result as GrammarResult.Ambiguous).candidates.toSet())
    }

    @Test fun prefixesLetThePhoneGetReady() {
        assertEquals(InstantIntent.CAMERA, InstantGrammar.prefix("open my cam"))
        assertEquals(InstantIntent.CALL, InstantGrammar.prefix("call m"))
        assertEquals(InstantIntent.PHOTO, InstantGrammar.prefix("take a pic"))
        assertNull(InstantGrammar.prefix("what is"))
        assertTrue(InstantGrammar.complete("swipe up"))
        assertFalse(InstantGrammar.complete("swipe"))
    }

    @Test fun similarityIsStrict() {
        assertTrue(InstantGrammar.similarity("pokemon go", "Pokémon GO") >= 0.97)
        assertTrue(InstantGrammar.similarity("insta", "Instagram") < InstantGrammar.MATCH)
        assertTrue(InstantGrammar.similarity("instagarm", "Instagram") >= InstantGrammar.MATCH)
        assertTrue(InstantGrammar.similarity("maps", "Photos") < InstantGrammar.MATCH)
    }

    // ---- the router --------------------------------------------------------------------------------------------------

    private val facts = LocalFacts(time = "22:40", date = "Tuesday 29 September", batteryPercent = 64, charging = false)

    @Test fun clearCommandsGoInstantAndLocalQuestionsAreAnswered() {
        val swipe = ModeRouter.route("swipe up", home, facts, Speed.COMMANDS, null, 0.9)
        assertEquals(Mode.INSTANT, swipe.mode)
        assertEquals("It's 22:40.", ModeRouter.route("what time is it", home, facts, Speed.COMMANDS, null, 0.9).answer)
        assertEquals("64%.", ModeRouter.route("how much battery", home, facts, Speed.COMMANDS, null, 0.9).answer)
    }

    @Test fun writingMoneyDeletingSeveralAppsAndRealQuestionsGoToTheMind() {
        listOf("message Lou I'm late", "call mom and tell her I'm late", "pay the bill", "delete my last photo",
            "open Instagram and WhatsApp", "why is my phone slow").forEach {
            assertEquals(it, Mode.MIND, ModeRouter.route(it, home, facts, Speed.AUTO, { error("no box for $it") }, 0.9).mode)
        }
        assertEquals(Mode.MIND, ModeRouter.route("swipe up", home, facts, Speed.MIND, null, 0.9).mode)
        assertEquals(Mode.MIND, ModeRouter.route("turn on dark mode", home, facts, Speed.COMMANDS, null, 0.9).mode)
    }

    @Test fun inAutoBoardZeroDecidesAndUnsureGoesUp() {
        var asked = 0
        val box = DecisionBox { asked++; BoxReply(mapOf("route" to BoxAnswer("instant", 0.95), "intent" to BoxAnswer("open_app", 0.95),
            "target" to BoxAnswer("Maps", 0.93))) }
        val route = ModeRouter.route("show me the map app", home, facts, Speed.AUTO, box, 0.9)
        assertEquals(1, asked)
        assertEquals(Mode.INSTANT, route.mode)
        assertEquals("com.google.android.apps.maps", route.command!!.target)
        val unsure = DecisionBox { BoxReply(mapOf("route" to BoxAnswer("instant", 0.6))) }
        assertEquals(Mode.FLASH, ModeRouter.route("show me the map app", home, facts, Speed.AUTO, unsure, 0.9).mode)
        assertEquals(Mode.FLASH, ModeRouter.route("show me the map app", home, facts, Speed.AUTO, { null }, 0.9).mode)
        // A target that isn't really there can't be tapped, however sure the box is.
        val made = DecisionBox { BoxReply(mapOf("route" to BoxAnswer("instant", 0.99), "intent" to BoxAnswer("tap", 0.99), "target" to BoxAnswer("Netflix", 0.99))) }
        assertEquals(Mode.FLASH, ModeRouter.fromBoard(made.ask(ModeRouter.board0("tap netflix", home)), "tap netflix", home, 0.9).mode)
        assertEquals(Mode.FLASH, ModeRouter.route("go to settings then wifi", home, facts, Speed.AUTO, box, 0.9).mode)
    }

    @Test fun theBatonTellsTheNextModeWhatWasDone() {
        val note = RunBaton("call mom", listOf(Mode.INSTANT), listOf("looked up mom"), "more than one contact matches", listOf("Mam", "Mom Work")).note()
        assertTrue(note, note.contains("started in instant mode") && note.contains("Mam, Mom Work") && note.contains("don't redo"))
    }

    // ---- the decision box --------------------------------------------------------------------------------------------

    @Test fun theBoxAsksSeveralTypedQuestionsInOneCallAndDropsInventedAnswers() {
        val request = ModeRouter.board0("show me the map app", home)
        val chat = BoxWire.chatBody("google/x", request)
        val schema = chat.getJSONObject("response_format").getJSONObject("json_schema").getJSONObject("schema").getJSONObject("properties")
        assertTrue(schema.has("route") && schema.has("intent") && schema.has("target") && schema.has("target_confidence"))
        val reply = BoxWire.parseChat("""{"choices":[{"message":{"content":"{\"route\":\"instant\",\"route_confidence\":0.9,\"intent\":\"teleport\",\"intent_confidence\":0.9,\"target\":\"Maps\",\"target_confidence\":0.8}"}}]}""", request)!!
        assertEquals("instant", reply.choice("route"))
        assertNull(reply.choice("intent"))
        assertEquals("Maps", reply.sure("target", 0.8))
        assertNull(reply.sure("target", 0.9))
        val decisions = BoxWire.parseDecisions("""{"answers":{"route":{"choice":"flash","confidence":0.7},"intent":"none"}}""", request)!!
        assertEquals("flash", decisions.choice("route"))
        assertEquals(0.7, decisions.confidence("route"), 0.001)
        assertNull(BoxWire.parseDecisions("""{"answers":{"route":"teleport"}}""", request))
        assertEquals(3, BoxWire.decisionsBody("jev", request).getJSONObject("questions").length())
        assertFalse(BoxWire.clean("Card 4111 1111 1111 1111\nHello").contains("4111"))
    }

    // ---- Instant mode ------------------------------------------------------------------------------------------------

    private class Phone(var screen: InstantScreen = InstantScreen("Launcher", listOf("Pokémon GO", "Camera"))) : InstantHands {
        val did = mutableListOf<String>()
        var contacts: List<InstantContact> = emptyList()
        var letCall = true
        var afterCamera = InstantScreen("Camera", listOf("Switch camera", "Shutter", "Gallery"))
        var afterDial = InstantScreen("Phone", listOf("Call", "Add contact"))
        var screenshots = 0
        override fun look(withImage: Boolean) = screen.also { if (withImage) screenshots++ }
        override fun gesture(intent: InstantIntent, direction: String?): InstantMove { did += "${intent.name.lowercase()} $direction"; return InstantMove(true, true, "") }
        override fun tapLabel(label: String): InstantMove { did += "tap $label"; return InstantMove(true, true, "") }
        override fun openApp(packageName: String): InstantMove { did += "open $packageName"; return InstantMove(true, true, "") }
        override fun camera(front: Boolean): InstantMove { did += "camera front=$front"; screen = afterCamera; return InstantMove(true, true, "") }
        override fun contacts(name: String) = contacts
        override fun dial(number: String): InstantMove { did += "dial $number"; screen = afterDial; return InstantMove(true, true, "") }
        override fun confirmWindow(text: String, ms: Long): Boolean { did += "window $text"; return letCall }
        override fun timer(seconds: Int): InstantMove { did += "timer $seconds"; return InstantMove(true, false, "") }
        override fun alarm(hour: Int, minute: Int): InstantMove { did += "alarm $hour:$minute"; return InstantMove(true, false, "") }
        override fun flashlight(on: Boolean): InstantMove { did += "torch $on"; return InstantMove(true, false, "") }
        override fun volume(up: Boolean): InstantMove { did += "volume $up"; return InstantMove(true, false, "") }
        override fun media(action: String): InstantMove { did += "media $action"; return InstantMove(true, false, "") }
        override fun stopped() = false
    }

    @Test fun swipeUpAndClickPokemonGoHappenInOneMove() {
        val phone = Phone()
        assertTrue(InstantRun.run(match("swipe up"), phone).done)
        assertTrue(InstantRun.run(match("click Pokemon Go"), phone).done)
        assertEquals(listOf("swipe up", "tap Pokémon GO"), phone.did)
    }

    @Test fun aPictureOfMeOpensTheFrontCameraAndPressesTheShutter() {
        val phone = Phone()
        val outcome = InstantRun.run(match("take a picture of me"), phone)
        assertTrue(outcome.lines.toString(), outcome.done)
        assertEquals(listOf("camera front=true", "tap Shutter"), phone.did)
        assertEquals(2, outcome.moves)
    }

    @Test fun anIconOnlyShutterIsPickedByOneDecisionBoxOrPromotes() {
        val phone = Phone().apply { afterCamera = InstantScreen("Camera", listOf("Mode", "Button 3", "Gallery")) }
        val box = DecisionBox { BoxReply(mapOf("next" to BoxAnswer("Button 3", 0.95))) }
        assertTrue(InstantRun.run(match("take a picture"), phone, box).done)
        assertEquals("tap Button 3", phone.did.last())
        val lost = InstantRun.run(match("take a picture"), Phone().apply { afterCamera = InstantScreen("Camera", listOf("Mode", "Button 3")) })
        assertEquals(Mode.FLASH, lost.promotion!!.to)
    }

    @Test fun callMyMomCallsTheOneMatchAfterTheCancelWindow() {
        val phone = Phone().apply { contacts = listOf(InstantContact("Mam", listOf("+31612345678"))) }
        val outcome = InstantRun.run(match("call my mom"), phone)
        assertTrue(outcome.done)
        assertEquals(listOf("window Calling Mam", "dial +31612345678", "tap Call"), phone.did)
    }

    @Test fun twoMomsPromoteToFlashWithTheCandidatesAndNothingIsDialled() {
        val phone = Phone().apply { contacts = listOf(InstantContact("Mam", listOf("1")), InstantContact("Mom Work", listOf("2"))) }
        val outcome = InstantRun.run(match("call my mom"), phone)
        assertEquals(Mode.FLASH, outcome.promotion!!.to)
        assertEquals(listOf("Mam", "Mom Work"), outcome.promotion!!.candidates)
        assertTrue(phone.did.isEmpty())
        assertTrue(outcome.baton("call my mom")!!.note().contains("Mam, Mom Work"))
    }

    @Test fun cancellingTheCallIsNotAFailure() {
        val phone = Phone().apply { contacts = listOf(InstantContact("Mam", listOf("1"))); letCall = false }
        val outcome = InstantRun.run(match("call my mom"), phone)
        assertTrue(outcome.cancelled && outcome.done)
        assertEquals(listOf("window Calling Mam"), phone.did)
    }

    @Test fun irreversibleOrSensitiveTapsGoToTheMind() {
        val chat = Phone(InstantScreen("WhatsApp", listOf("Send", "Message")))
        val send = InstantRun.run(InstantCommand(InstantIntent.TAP, "tap send", target = "Send"), chat)
        assertEquals(Mode.MIND, send.promotion!!.to)
        assertTrue(chat.did.isEmpty())
        val login = Phone(InstantScreen("Bank", listOf("Log in"), sensitive = true))
        assertEquals(Mode.MIND, InstantRun.run(InstantCommand(InstantIntent.TAP, "tap log in", target = "Log in"), login).promotion!!.to)
        val gone = Phone(InstantScreen("Launcher", listOf("Camera")))
        assertEquals(Mode.FLASH, InstantRun.run(match("click Pokemon Go"), gone).promotion!!.to)
    }

    @Test fun directActionsNeedNoScreen() {
        val phone = Phone()
        listOf("set a timer for 5 minutes", "turn on the flashlight", "volume down", "pause").forEach { assertTrue(it, InstantRun.run(match(it), phone).done) }
        assertEquals(listOf("timer 300", "torch true", "volume false", "media play_pause"), phone.did)
    }

    @Test fun quickCommandsForLiveVoice() {
        listOf("swipe up", "open my camera", "take a picture of me", "call my mom", "click Pokemon Go", "open Instagram", "volume up")
            .forEach { assertTrue(it, InstantGrammar.quick(it)) }
        listOf("open Instagram and message Louella", "what's the weather tomorrow", "tell Louella I'm late", "")
            .forEach { assertFalse(it, InstantGrammar.quick(it)) }
    }

    @Test fun instantCopyIsShort() {
        assertEquals("Opening the camera", InstantCopy.working(match("open my camera")))
        assertEquals("Took the photo.", InstantCopy.done(InstantOutcome(true, listOf("opened the front camera", "took the photo"), 2)))
        assertEquals("Done.", InstantCopy.done(InstantOutcome(true, emptyList(), 0)))
    }

    // ---- alpha.78 --------------------------------------------------------------------------------------------------

    @Test fun aWordSaidTwiceByTheSpeechToTextStillOpensTheApp() {
        assertEquals("com.whatsapp", match("open open WhatsApp").target)
        assertEquals("down", match("swipe swipe down").direction)
    }

    @Test fun onlyATapNeedsTheScreenReadBeforeRouting() {
        assertTrue(InstantGrammar.needsScreen("tap Pokémon GO"))
        assertTrue(InstantGrammar.needsScreen("click on Settings"))
        assertFalse(InstantGrammar.needsScreen("swipe down"))
        assertFalse(InstantGrammar.needsScreen("open Telegram"))
    }

    @Test fun aBoxThatCantSeeNeverCostsAScreenshot() {
        val phone = Phone().apply { afterCamera = InstantScreen("Camera", listOf("Mode", "Button 3", "Gallery")) }
        val blind = DecisionBox { BoxReply(mapOf("next" to BoxAnswer("Button 3", 0.95))) }
        assertTrue(InstantRun.run(match("take a picture"), phone, blind).done)
        assertEquals(0, phone.screenshots)
        val seeing = object : DecisionBox {
            override fun ask(request: BoxRequest) = BoxReply(mapOf("next" to BoxAnswer("Button 3", 0.95)))
            override val sees = true
        }
        val phone2 = Phone().apply { afterCamera = InstantScreen("Camera", listOf("Mode", "Button 3", "Gallery")) }
        assertTrue(InstantRun.run(match("take a picture"), phone2, seeing).done)
        assertEquals(1, phone2.screenshots)
    }
}
