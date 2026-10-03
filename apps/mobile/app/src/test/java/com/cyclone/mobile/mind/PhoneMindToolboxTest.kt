package com.cyclone.mobile.mind

import com.cyclone.mobile.agent.contract.AgentActionEnvelope
import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentFailure
import com.cyclone.mobile.agent.contract.AgentFailureClass
import com.cyclone.mobile.agent.contract.AgentFailureLayer
import com.cyclone.mobile.agent.contract.AgentInspectResult
import com.cyclone.mobile.agent.contract.AgentKnowledgeResult
import com.cyclone.mobile.agent.contract.AgentLearningResult
import com.cyclone.mobile.agent.contract.AgentObservationResult
import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.agent.contract.AgentScreenshotResult
import com.cyclone.mobile.agent.contract.AgentSearchResult
import com.cyclone.mobile.agent.contract.AgentSemanticVerification
import com.cyclone.mobile.agent.contract.AgentStateDelta
import com.cyclone.mobile.agent.contract.AgentVerificationStatus
import com.cyclone.mobile.agent.tools.CycloneAgentEnvironmentApi
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMindToolboxTest {
    data class Control(val key: String, val label: String, val role: String = "button", val editable: Boolean = false, val password: Boolean = false)

    class FakeScreen(val packageName: String, val controls: List<Control>, val text: List<String> = emptyList(),
        val values: Map<String, String> = emptyMap(), val treeUseful: Boolean = true, val shortlist: Int = 36,
        /** Set to give the screen a learned-map identity (legacy page key). */
        val pageKey: String? = null, val title: String = "")

    class FakeEnv(var screen: FakeScreen) : CycloneAgentEnvironmentApi {
        var observations = 0
        var visible: String? = null
        val acts = mutableListOf<Pair<String, JSONObject>>()
        var nextFailures = ArrayDeque<AgentFailureClass>()
        var onAct: (String, JSONObject) -> Unit = { _, _ -> }
        var images = 0
        var nextMessage: String? = null
        private var lastAll: List<AgentElementCandidate> = emptyList()
        private var lastId: String? = null

        override fun allControls(): List<AgentElementCandidate> = if (visible != null && visible == lastId) lastAll else emptyList()
        override fun fieldValue(elementId: String): String? =
            if (visible != null && elementId.contains(":$visible:")) screen.values[elementId.substringAfterLast(':')] else null

        fun card(): AgentPageCard {
            observations++
            val id = "obs$observations"
            visible = id
            val all = screen.controls.map { c ->
                AgentElementCandidate("semantic:$id:${c.key}", id, c.label, c.label.lowercase(), c.role, "semantic", 0.0,
                    JSONObject().put("controlKey", c.key).put("editable", c.editable).put("password", c.password)
                        .put("bounds", JSONObject().put("left", 10).put("top", 10).put("right", 200).put("bottom", 80)))
            }
            lastAll = all
            lastId = id
            return AgentPageCard(
                treeUseful = screen.treeUseful,
                observationId = id, generation = observations.toLong(), actionable = true, capturedAtMs = 0,
                packageName = screen.packageName, activity = null, pageKey = "p", structuralKey = "s", contentKey = "c",
                accessibilityFingerprint = "f$observations", pageSummary = JSONObject(),
                pageText = JSONObject().put("lines", JSONArray().also { lines -> screen.text.forEach { lines.put(JSONObject().put("text", it)) } }),
                pageEvidence = JSONObject().put("captureWidth", 1080).put("captureHeight", 2400),
                controls = all.take(screen.shortlist),
                nextHopHints = JSONArray(),
                legacyPage = screen.pageKey?.let { key ->
                    com.cyclone.mobile.applearner.PageContext(key, screen.packageName, "Main", screen.title, key, "c", emptyList(), 1, 0, 0)
                },
            )
        }

        override fun observe(goal: String) = AgentObservationResult(page = card())
        override fun observeWithImage(goal: String) = AgentObservationResult(page = card().also { images++ }, image = JSONObject().put("pngBase64", "QUJD").put("width", 540).put("height", 1200))
        override fun locate(goal: String) = AgentSearchResult(query = goal, goal = goal)
        override fun search(query: String, goal: String): AgentSearchResult {
            val page = card()
            return AgentSearchResult(page = page, observationId = page.observationId, query = query, goal = goal,
                candidates = page.controls.filter { it.label.contains(query, true) })
        }
        override fun inspect(elementId: String) = AgentInspectResult(elementId = elementId)
        override fun screenshot(goal: String) = AgentScreenshotResult(goal)
        override fun history(): List<AgentActionEnvelope> = emptyList()
        override fun invalidateObservation() { visible = null }
        override fun brainRecall(goal: String) = AgentKnowledgeResult(goal, JSONObject().put("fact", "owner prefers Dutch"))
        override fun knownRoutes(goal: String) = AgentKnowledgeResult(goal)

        override fun act(tool: String, params: JSONObject, goal: String): AgentActionEnvelope {
            acts += tool to JSONObject(params.toString())
            if (visible == null) return envelope(tool, AgentFailureClass.STALE_OBSERVATION)
            params.optString("elementId").takeIf { it.isNotBlank() }?.let { id ->
                if (!id.contains(":$visible:")) return envelope(tool, AgentFailureClass.STALE_OBSERVATION)
            }
            visible = null
            val failure = nextFailures.removeFirstOrNull() ?: AgentFailureClass.NONE
            if (failure == AgentFailureClass.NONE) onAct(tool, params)
            return envelope(tool, failure)
        }

        private fun envelope(tool: String, failure: AgentFailureClass) = AgentActionEnvelope(
            tool = tool, goal = "", androidExecutionOk = failure == AgentFailureClass.NONE, executorReportedOk = failure == AgentFailureClass.NONE,
            verification = AgentSemanticVerification(AgentVerificationStatus.PASSED, true, true),
            before = null, after = null, pageChanged = failure == AgentFailureClass.NONE,
            delta = AgentStateDelta(false, false, false, emptyList(), false, ""),
            errorClass = failure, failureLayer = if (failure == AgentFailureClass.NONE) AgentFailureLayer.NONE else AgentFailureLayer.POLICY,
            retryable = false, semanticSuccessClaimed = false, beforeObservationId = null, afterObservationId = null,
            observationGeneration = null, learning = AgentLearningResult(false, ""),
            safeMessage = if (failure == AgentFailureClass.NONE) null else (nextMessage ?: "blocked: $failure"),
        )
    }

    class FakeOwner : MindOwnerPort {
        var approval = MindApproval.APPROVED
        var answer: String? = "blue"
        var secret = MindSecretOutcome.FILLED
        val asked = mutableListOf<String>()
        val secretSlots = mutableListOf<String>()
        var planned: List<MindPlanStep> = emptyList()
        override fun ask(question: String, choices: List<String>, timeoutMs: Long): MindOwnerReply {
            asked += question
            return answer?.let { MindOwnerReply(true, it, 30_000) } ?: MindOwnerReply(false, waitedMs = timeoutMs)
        }
        override fun awaitApproval(action: String, timeoutMs: Long) = MindApprovalReply(approval, 12_000)
        override fun fillSecret(page: AgentPageCard, target: MindRef, slot: String, reason: String, timeoutMs: Long): MindSecretReply {
            secretSlots += slot
            return MindSecretReply(secret, 40_000)
        }
        override fun awaitControl(timeoutMs: Long) = MindOwnerReply(true, waitedMs = 5_000)
        var values: MindValuesReply = MindValuesReply(MindValuesOutcome.DECLINED)
        var filled: List<MindValueField> = emptyList()
        override fun fill(reason: String, fields: List<MindValueField>, timeoutMs: Long): MindValuesReply { filled = fields; return values }
        override fun plan(steps: List<MindPlanStep>) { planned = steps }
    }

    private val device = object : MindDevicePort {
        override fun apps() = listOf(MindApp("com.google.android.deskclock", "Clock"), MindApp("com.android.chrome", "Chrome"),
            MindApp("com.instagram.android", "Instagram"), MindApp("com.google.android.gm", "Gmail"), MindApp("com.google.android.apps.maps", "Maps"),
            MindApp("com.example.mapsplus", "Maps Plus"))
        override fun now() = "Thursday 14:05"
        override fun device() = "Test phone"
        override fun sleep(ms: Long) {}
    }

    private val login = FakeScreen("com.android.chrome", listOf(
        Control("email", "Email", "edit_text", editable = true),
        Control("pass", "Password", "edit_text", editable = true, password = true),
        Control("login", "Log in"),
    ), listOf("Welcome to Facebook", "Log in"))

    private fun call(name: String, args: String = "{}") = MindToolCall("c", name, args)
    private fun MindToolbox.run(name: String, args: String = "{}") = execute(call(name, args), JSONObject(args))

    @Test fun screenRendersRefsTextAndNoIds() {
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        val text = box.run("screen_read").text
        assertTrue(text.contains("Screen: Chrome (com.android.chrome)"))
        assertTrue(text.contains("e1 text field \"Email\""))
        assertTrue(text.contains("e2 password field \"Password\""))
        assertTrue(text.contains("Welcome to Facebook"))
        assertFalse("element ids never reach the model", text.contains("semantic:"))
    }

    @Test fun planesRebindMovesActionsToTheNewPlaneAndTellsTheModelOnce() {
        val first = FakeEnv(login)
        val second = FakeEnv(login)
        val seen = mutableListOf<String>()
        lateinit var box: PhoneMindToolbox
        val planes = object : MindPlanes {
            override fun before(tool: String, arguments: JSONObject): String? {
                seen += tool
                if (tool == "home") return "NOT RUN: no Home screen on a background screen."
                if (tool == "tap") box.rebind(second, "Cyclone now works on a background screen")
                return null
            }
        }
        box = PhoneMindToolbox(first, FakeOwner(), device, "goal", planes = planes)
        box.run("screen_read")
        val refused = box.run("home")
        assertFalse(refused.ok)
        assertTrue(first.acts.none { it.first == "phone.home" })
        val tap = box.run("tap", """{"ref":"e3"}""")
        assertTrue(tap.text.startsWith("(Cyclone now works on a background screen)"))
        assertTrue("the action ran on the new plane", second.acts.isNotEmpty() || !tap.ok)
        assertTrue(first.acts.none { it.first == "phone.click" })
        assertFalse("the note is shown once", box.run("screen_read").text.contains("background screen)"))
        assertEquals(listOf("screen_read", "home", "tap", "screen_read"), seen)
    }

    @Test fun aRefusedTargetNamesItsRealReasonAndTheNextStep() {
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        box.run("screen_read")
        env.nextFailures.addLast(AgentFailureClass.STALE_OBSERVATION)
        env.nextMessage = "Target revalidation: AMBIGUOUS. Inspect a fresh same-scope control."
        val ambiguous = box.run("tap", """{"ref":"e3"}""")
        assertFalse(ambiguous.ok)
        assertTrue(ambiguous.text.contains("focused=true"))
        assertFalse(ambiguous.text.contains("moved or disappeared"))
        env.nextFailures.addLast(AgentFailureClass.STALE_OBSERVATION)
        env.nextMessage = "Target revalidation: DISAPPEARED. Inspect a fresh same-scope control."
        assertTrue(box.run("tap", """{"ref":"e3"}""").text.contains("no longer on the screen"))
    }

    @Test fun typeTextWithFocusedNeedsNoRef() {
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        box.run("screen_read")
        val typed = box.run("type_text", """{"focused":true,"text":"hello"}""")
        assertTrue(typed.ok)
        assertEquals("phone.type", env.acts.last().first)
        assertTrue(env.acts.last().second.getBoolean("focused"))
        assertFalse(env.acts.last().second.has("elementId"))
        assertTrue(typed.text.contains("focused text box"))
    }

    @Test fun fourFailedAttemptsCopyTheDraftAndAskTheOwnerToPaste() {
        val env = FakeEnv(login)
        val owner = FakeOwner().apply { answer = "Done" }
        val copied = mutableListOf<String>()
        val clipboardDevice = object : MindDevicePort by device {
            override fun copy(text: String): Boolean { copied += text; return true }
        }
        val box = PhoneMindToolbox(env, owner, clipboardDevice, "goal")
        box.run("screen_read")
        repeat(4) { env.nextFailures.addLast(AgentFailureClass.EXECUTION_FAILED) }
        val results = (1..4).map { box.run("type_text", """{"ref":"e1","text":"Find better examples"}""") }
        assertTrue("second identical refusal adds the harness hint", results[1].text.contains("(Harness:"))
        assertEquals(listOf("Find better examples"), copied)
        assertTrue(owner.asked.single().contains("It's copied"))
        assertTrue(results[3].ok)
        assertTrue(results[3].text.contains("entered the text by hand"))
    }

    @Test fun aReplyFromTheNotificationNeedsTheOwnersApprovalEveryTime() {
        val env = FakeEnv(login)
        val sent = mutableListOf<Pair<String, String>>()
        val phone = object : MindDevicePort by device {
            override fun notifications() = listOf(
                MindNotification("k1", "com.whatsapp", "Louella", "Are you coming?", System.currentTimeMillis(), listOf("Reply"), replyable = true),
                MindNotification("k2", "com.google.android.gm", "Newsletter", "Deals", System.currentTimeMillis()))
            override fun replyNotification(key: String, text: String): String? { sent += key to text; return null }
        }
        val owner = FakeOwner()
        val box = PhoneMindToolbox(env, owner, phone, "reply to Louella")
        assertTrue(box.run("notifications").text.contains("[can reply]"))
        assertTrue(box.run("reply_notification", """{"id":"n1","text":"On my way"}""").ok)
        assertEquals(listOf("k1" to "On my way"), sent)
        owner.approval = MindApproval.DECLINED
        assertFalse(box.run("reply_notification", """{"id":"n1","text":"Later"}""").ok)
        assertEquals(1, sent.size)
        assertFalse(box.run("reply_notification", """{"id":"n2","text":"Hi"}""").ok)
        assertFalse(box.run("reply_notification", """{"id":"n1","text":"my password is x"}""").ok)
        assertEquals(1, sent.size)
        assertTrue("no screen was touched", env.acts.isEmpty())
    }

    /** Drive (plan 32): the text the owner approves (and Drive reads back) is exactly the text that is sent. */
    @Test fun theApprovedReplyTextIsExactlyTheSentText() {
        val env = FakeEnv(login)
        val sent = mutableListOf<String>()
        val approved = mutableListOf<MindSend>()
        val phone = object : MindDevicePort by device {
            override fun notifications() = listOf(
                MindNotification("k1", "com.whatsapp", "Louella", "I will be home late", System.currentTimeMillis(), listOf("Reply"), replyable = true))
            override fun replyNotification(key: String, text: String): String? { sent += text; return null }
        }
        val owner = object : MindOwnerPort by FakeOwner() {
            var next = MindApprovalReply(MindApproval.APPROVED)
            override fun awaitApproval(action: String, timeoutMs: Long, send: MindSend): MindApprovalReply { approved += send; return next }
        }
        val box = PhoneMindToolbox(env, owner, phone, "reply to Louella")
        box.run("notifications")
        val draft = "Hey baby, that's alright,\nhope to see you soon for the movie."
        assertTrue(box.run("reply_notification", JSONObject().put("id", "n1").put("text", draft).toString()).ok)
        assertEquals(listOf(draft), sent)
        assertEquals(MindSend(draft, "Louella", "com.whatsapp"), approved.single())
        // "Change it to …" sends nothing and tells the Mind what to change.
        owner.next = MindApprovalReply(MindApproval.DECLINED, change = "say see you tonight instead")
        val changed = box.run("reply_notification", JSONObject().put("id", "n1").put("text", "Sure").toString())
        assertFalse(changed.ok)
        assertTrue(changed.text.contains("say see you tonight instead"))
        assertEquals(1, sent.size)
    }

    @Test fun directToolsNeverTouchTheScreenAndAskForAccessOnce() {
        val env = FakeEnv(login)
        val calls = mutableListOf<String>()
        var allowed = false
        val asked = mutableListOf<List<String>>()
        val phone = object : MindDevicePort by device {
            override fun direct(tool: String, params: JSONObject): MindDirect {
                calls += tool
                if (!allowed) return MindDirect(error = "PERMISSION_REQUIRED: Cyclone needs access to your calendar: " +
                    "android.permission.READ_CALENDAR,android.permission.WRITE_CALENDAR")
                return when (tool) {
                    "calendar_add" -> MindDirect(JSONObject().put("added", true).put("verified", true).put("title", params.getString("title"))
                        .put("when", "Sat 3 Oct 19:00–20:00").put("calendar", "Sam's calendar").put("reminderMinutes", 30))
                    "calendar_find" -> MindDirect(JSONObject().put("from", "2026-10-03T00:00").put("to", "2026-10-04T00:00")
                        .put("events", JSONArray().put(JSONObject().put("title", "Dinner with Sam").put("when", "Sat 3 Oct 19:00–20:00")
                            .put("location", "Luigi's").put("calendar", "Sam's calendar"))))
                    "contacts_find" -> MindDirect(JSONObject().put("contacts", JSONArray().put(JSONObject().put("name", "Sam Jones")
                        .put("phones", JSONArray().put("+31 6 1234 5678")).put("emails", JSONArray()))))
                    else -> MindDirect(error = "UNAVAILABLE")
                }
            }
            override fun requestAccess(permissions: List<String>): Boolean { asked += permissions; allowed = true; return true }
        }
        val box = PhoneMindToolbox(env, FakeOwner(), phone, "add dinner with Sam")
        val added = box.run("calendar_add", """{"title":"Dinner with Sam","start":"2026-10-03T19:00","reminder_minutes":30}""")
        assertTrue(added.text, added.ok)
        assertTrue(added.text.contains("Checked: it is in the calendar"))
        assertTrue(added.text.contains("a reminder 30 minutes before"))
        assertNotNull(added.evidence)
        // Android's own dialog was shown once, then the add ran again.
        assertEquals(listOf(listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR")), asked)
        assertEquals(listOf("calendar_add", "calendar_add"), calls)
        assertTrue(box.run("calendar_find", """{"from":"2026-10-03","to":"2026-10-03"}""").text.contains("Dinner with Sam · at Luigi's"))
        assertTrue(box.run("contact_find", """{"query":"Sam"}""").text.contains("Sam Jones · phone +31 6 1234 5678"))
        assertFalse("secrets never go into the calendar",
            box.run("calendar_add", """{"title":"Bank","start":"2026-10-03T19:00","notes":"password: hunter2"}""").ok)
        assertTrue("no screen was touched", env.acts.isEmpty())
        assertEquals(0, env.observations)
    }

    @Test fun declinedAccessIsSaidAndNothingElseHappens() {
        val env = FakeEnv(login)
        val phone = object : MindDevicePort by device {
            override fun direct(tool: String, params: JSONObject) =
                MindDirect(error = "PERMISSION_REQUIRED: Cyclone needs access to your contacts: android.permission.READ_CONTACTS")
            override fun requestAccess(permissions: List<String>) = false
        }
        val result = PhoneMindToolbox(env, FakeOwner(), phone, "call Sam").run("contact_find", """{"query":"Sam"}""")
        assertFalse(result.ok)
        assertTrue(result.text.contains("did not allow access to your contacts"))
        assertTrue(env.acts.isEmpty())
    }

    @Test fun timersAndAlarmsGoDirectAndFallBackToTheClockApp() {
        val env = FakeEnv(login)
        val direct = mutableListOf<String>()
        val phone = object : MindDevicePort by device {
            override fun direct(tool: String, params: JSONObject): MindDirect {
                direct += tool
                return MindDirect(JSONObject().put("requested", true).put("verified", tool == "alarm"))
            }
        }
        val box = PhoneMindToolbox(env, FakeOwner(), phone, "wake me at 6:30")
        val alarm = box.run("set_alarm", """{"hour":6,"minute":30}""")
        assertTrue(alarm.ok)
        assertTrue(alarm.text.contains("Android now lists it as the next alarm"))
        val timer = box.run("set_timer", """{"minutes":5}""")
        assertTrue(timer.ok)
        assertTrue("an unconfirmed timer is not claimed", timer.text.contains("not confirmed"))
        assertEquals(listOf("alarm", "timer"), direct)
        assertTrue("the clock app's screen was not used", env.acts.isEmpty())
        // A phone whose clock has no such contract uses the clock app, as before (actionsWithoutAPriorReadObserveFirst).
    }

    @Test fun refsStayStableAcrossReobservation() {
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        box.run("screen_read")
        val typed = box.run("type_text", """{"ref":"e1","text":"me@example.com"}""")
        assertTrue(typed.ok)
        assertFalse("typing does not end the turn", typed.changedScreen)
        assertTrue(typed.text.contains("e3 button \"Log in\""))
        val tap = box.run("tap", """{"ref":"e3"}""")
        assertTrue(tap.ok)
        assertTrue(tap.changedScreen)
        assertEquals("phone.click", env.acts.last().first)
        assertTrue(env.acts.last().second.getString("elementId").endsWith(":login"))
    }

    @Test fun secretFieldsAreNeverTypedAndGoToTheVault() {
        val env = FakeEnv(login)
        val owner = FakeOwner()
        val box = PhoneMindToolbox(env, owner, device, "goal")
        box.run("screen_read")
        val refused = box.run("type_text", """{"ref":"e2","text":"hunter2"}""")
        assertFalse(refused.ok)
        assertTrue(refused.text.contains("vault_fill"))
        assertTrue(env.acts.isEmpty())
        val filled = box.run("vault_fill", """{"ref":"e2","what":"password","reason":"Sign in to Facebook"}""")
        assertTrue(filled.ok)
        assertEquals(listOf("password"), owner.secretSlots)
        assertEquals(40_000, filled.ownerWaitMs)
    }

    @Test fun gateWaitsForApprovalThenRetriesTheSameControl() {
        val env = FakeEnv(login)
        env.nextFailures.add(AgentFailureClass.GATE_REQUIRED)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        box.run("screen_read")
        val result = box.run("tap", """{"ref":"e3"}""")
        assertTrue(result.ok)
        assertEquals(2, env.acts.size)
        assertTrue(env.acts[1].second.getString("elementId").endsWith(":login"))
        assertEquals(12_000, result.ownerWaitMs)
    }

    /** Drive (plan 32), the chat route: the send approval carries the message box's exact text, and only that is sent. */
    @Test fun aChatSendIsApprovedWithTheBoxsExactTextAndNotSentIfItChanged() {
        val chat = { value: String? -> FakeScreen("com.whatsapp", listOf(Control("msg", "Message", "edit_text", editable = true), Control("send", "Send")),
            values = value?.let { mapOf("msg" to it) } ?: emptyMap()) }
        val env = FakeEnv(chat(null))
        env.onAct = { tool, params -> if (tool == "phone.type") env.screen = chat(params.getString("value")) }
        val approved = mutableListOf<MindSend>()
        var changeBoxTo: String? = null
        val owner = object : MindOwnerPort by FakeOwner() {
            override fun awaitApproval(action: String, timeoutMs: Long) = MindApprovalReply(MindApproval.APPROVED)
            override fun awaitApproval(action: String, timeoutMs: Long, send: MindSend): MindApprovalReply {
                approved += send
                changeBoxTo?.let { env.screen = chat(it) }
                return MindApprovalReply(MindApproval.APPROVED)
            }
        }
        val box = PhoneMindToolbox(env, owner, device, "reply to Louella")
        box.run("screen_read")
        assertTrue(box.run("type_text", """{"ref":"e1","text":"On my way, see you soon"}""").ok)
        env.nextFailures.add(AgentFailureClass.GATE_REQUIRED)
        assertTrue(box.run("tap", """{"ref":"e2"}""").ok)
        assertEquals("On my way, see you soon", approved.single().text)

        // The box changes between the approval and the tap: nothing is sent.
        env.screen = chat("On my way, see you soon")
        box.run("screen_read")
        changeBoxTo = "something else"
        val sendsBefore = env.acts.size
        env.nextFailures.add(AgentFailureClass.GATE_REQUIRED)
        val changed = box.run("tap", """{"ref":"e2"}""")
        assertFalse(changed.ok)
        assertTrue(changed.text.contains("changed after the owner approved"))
        assertEquals(sendsBefore + 1, env.acts.size)
    }

    @Test fun textCycloneDidNotTypeIsNeverReadBackAsTheMessage() {
        val env = FakeEnv(FakeScreen("com.whatsapp", listOf(Control("msg", "Message", "edit_text", editable = true), Control("send", "Send")),
            values = mapOf("msg" to "typed by someone else")))
        val approved = mutableListOf<MindSend>()
        val owner = object : MindOwnerPort by FakeOwner() {
            override fun awaitApproval(action: String, timeoutMs: Long, send: MindSend): MindApprovalReply { approved += send; return MindApprovalReply(MindApproval.APPROVED) }
        }
        val box = PhoneMindToolbox(env, owner, device, "send it")
        box.run("screen_read")
        env.nextFailures.add(AgentFailureClass.GATE_REQUIRED)
        box.run("tap", """{"ref":"e2"}""")
        assertTrue(approved.isEmpty())
    }

    @Test fun declinedApprovalIsReportedAndNotRetried() {
        val env = FakeEnv(login)
        env.nextFailures.add(AgentFailureClass.GATE_REQUIRED)
        val owner = FakeOwner().apply { approval = MindApproval.DECLINED }
        val box = PhoneMindToolbox(env, owner, device, "goal")
        box.run("screen_read")
        val result = box.run("tap", """{"ref":"e3"}""")
        assertFalse(result.ok)
        assertTrue(result.text.contains("declined"))
        assertEquals(1, env.acts.size)
    }

    @Test fun unknownRefIsExplained() {
        val box = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "goal")
        box.run("screen_read")
        val result = box.run("tap", """{"ref":"e9"}""")
        assertFalse(result.ok)
        assertTrue(result.text.contains("not on the current screen"))
    }

    @Test fun actionsWithoutAPriorReadObserveFirst() {
        val env = FakeEnv(login)
        env.onAct = { tool, _ -> if (tool == "phone.set_timer") env.screen = FakeScreen("com.google.android.deskclock", listOf(Control("pause", "Pause")), listOf("4:59")) }
        val box = PhoneMindToolbox(env, FakeOwner(), device, "set a 5 minute timer")
        val result = box.run("set_timer", """{"minutes":5}""")
        assertTrue(result.ok)
        assertEquals(300, env.acts.single().second.getInt("seconds"))
        assertTrue(result.text.contains("Screen: Clock"))
        assertTrue(result.text.contains("4:59"))
    }

    @Test fun openAppResolvesNamesAndExplainsMissingOnes() {
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        assertTrue(box.run("open_app", """{"app":"instagram"}""").ok)
        assertEquals("com.instagram.android", env.acts.last().second.getString("package"))
        val missing = box.run("open_app", """{"app":"TikTok"}""")
        assertFalse(missing.ok)
        assertTrue(missing.text.contains("market://details?id="))
        val ambiguous = box.run("open_app", """{"app":"map"}""")
        assertTrue(ambiguous.text.contains("Several installed apps"))
        assertTrue(box.run("open_app", """{"app":"Maps"}""").ok)
    }

    @Test fun linksAndSettingsAreValidated() {
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        assertTrue(box.run("open_link", """{"url":"facebook.com"}""").ok)
        assertEquals("https://facebook.com", env.acts.last().second.getString("uri"))
        assertFalse(box.run("open_link", """{"url":"intent://evil"}""").ok)
        assertTrue(box.run("open_settings", """{"page":"wifi"}""").ok)
        assertTrue(box.run("open_settings", """{"page":"app_details","app":"Gmail"}""").ok)
        assertEquals("com.google.android.gm", env.acts.last().second.getString("app"))
        assertFalse(box.run("open_settings", """{"page":"root_shell"}""").ok)
    }

    @Test fun finishNeedsEvidenceAndRecordsTheFinalScreen() {
        val box = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "goal")
        val rejected = box.run("task_finish", """{"summary":"done","evidence":""}""")
        assertNull(rejected.ending)
        val accepted = box.run("task_finish", """{"summary":"Timer running","evidence":"4:59 counting down"}""")
        assertEquals(MindEnding.COMPLETED, accepted.ending)
        assertTrue(accepted.evidence!!.contains("final screen"))
    }

    @Test fun ownerQuestionsAndPlans() {
        val owner = FakeOwner()
        val box = PhoneMindToolbox(FakeEnv(login), owner, device, "goal")
        val answer = box.run("owner_ask", """{"question":"Which account?","choices":["work","home"]}""")
        assertTrue(answer.text.contains("blue"))
        assertEquals(30_000, answer.ownerWaitMs)
        owner.answer = null
        assertFalse(box.run("owner_ask", """{"question":"Still there?"}""").ok)
        box.run("plan_update", """{"steps":[{"step":"Open Clock","status":"done"},{"step":"Start timer","status":"doing"}]}""")
        assertEquals(2, owner.planned.size)
        assertEquals("doing", owner.planned[1].status)
    }

    @Test fun findBindsExtraRefsAndRecallWorks() {
        val box = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "goal")
        box.run("screen_read")
        val found = box.run("screen_find", """{"query":"log"}""")
        assertTrue(found.text.contains("e3 button \"Log in\""))
        assertTrue(box.run("recall", """{"topic":"language"}""").text.contains("owner prefers Dutch"))
    }

    @Test fun screenshotIsAttached() {
        val look = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "goal").run("screen_look")
        assertNotNull(look.imageDataUrl)
        assertTrue(look.imageDataUrl!!.startsWith("data:image/png;base64,"))
    }

    @Test fun humanControlWaitsForHandBack() {
        val env = FakeEnv(login)
        env.nextFailures.add(AgentFailureClass.HUMAN_HAS_CONTROL)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        box.run("screen_read")
        val result = box.run("back")
        assertFalse(result.ok)
        assertTrue(result.text.contains("handed it back"))
        assertEquals(5_000, result.ownerWaitMs)
    }

    @Test fun everySpecIsExecutable() {
        val box = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "goal")
        box.specs().forEach { spec ->
            val result = box.execute(call(spec.name), JSONObject())
            assertFalse("${spec.name} is wired", result.text.startsWith("ERROR: Unknown tool"))
        }
        assertEquals(box.specs().size, box.specs().map { it.name }.toSet().size)
    }

    @Test fun afterAFailedActionTheModelStillSeesTheScreen() {
        val env = FakeEnv(login)
        env.nextFailures.add(AgentFailureClass.EXECUTION_FAILED)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        box.run("screen_read")
        val result = box.run("tap", """{"ref":"e3"}""")
        assertFalse(result.ok)
        assertTrue(result.text.startsWith("Failed"))
        assertTrue(result.text.contains("Controls:"))
    }

    @Test fun policyFailuresAreNotRetried() {
        val env = FakeEnv(login)
        env.nextFailures.add(AgentFailureClass.POLICY_DENIED)
        val box = PhoneMindToolbox(env, FakeOwner().apply { approval = MindApproval.NOT_PENDING }, device, "goal")
        box.run("screen_read")
        val result = box.run("tap", """{"ref":"e3"}""")
        assertTrue(result.text.startsWith("Not allowed"))
        assertEquals(1, env.acts.size)
        AgentFailure(AgentFailureClass.NONE, AgentFailureLayer.NONE, false, "")
    }

    @Test fun interceptorGateWaitsForApprovalLikeAPolicyGate() {
        val env = FakeEnv(login)
        env.nextFailures.add(AgentFailureClass.POLICY_DENIED)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        box.run("screen_read")
        val result = box.run("tap", """{"ref":"e3"}""")
        assertTrue(result.ok)
        assertEquals(2, env.acts.size)
    }

    @Test fun tapPointNeedsAScreenshotAndScalesToTheScreen() {
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        assertFalse(box.run("tap_point", """{"x":10,"y":10}""").ok)
        val look = box.run("screen_look")
        assertTrue(look.text.contains("540×1200"))
        assertFalse("outside the screenshot", box.run("tap_point", """{"x":600,"y":10}""").ok)
        assertTrue(box.run("tap_point", """{"x":100,"y":300}""").ok)
        val (tool, params) = env.acts.last()
        assertEquals("phone.tap_point", tool)
        assertEquals(200, params.getInt("x"))
        assertEquals(600, params.getInt("y"))
        assertFalse("points belong to one screenshot", box.run("tap_point", """{"x":100,"y":300}""").ok)
    }

    @Test fun rememberAndForgetAcrossMissions() {
        val memory = MindMemory(java.nio.file.Files.createTempFile("memory", ".json").toFile())
        val first = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "goal", memory = memory, missionId = "m1")
        assertTrue(first.run("remember", """{"fact":"The owner's work email is jan@example.com"}""").ok)
        assertFalse(first.run("remember", """{"fact":"Facebook password: hunter2"}""").ok)
        val second = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "goal", memory = memory, missionId = "m2")
        assertTrue(second.run("recall", """{"topic":"work email"}""").text.contains("jan@example.com"))
        assertTrue(memory.digest().contains("[f1]"))
        assertTrue(second.run("forget", """{"id":"f1"}""").ok)
        assertTrue(memory.all().isEmpty())
    }

    @Test fun aLockedPhoneIsWaitedForNotFailed() {
        var locked = 3
        val lockedDevice = object : MindDevicePort by device {
            override fun blocker(): String? = if (locked-- > 0) "the phone is locked" else null
            override fun sleep(ms: Long) {}
        }
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), lockedDevice, "goal")
        val result = box.run("screen_read")
        assertTrue(result.ok)
        assertTrue(result.text.startsWith("(The phone was the phone is locked"))
        assertTrue(result.ownerWaitMs > 0)
        locked = 0
        assertTrue("memory tools never wait", box.run("note", """{"text":"x"}""").ok)
    }

    @Test fun aPhoneThatStaysLockedEndsTheCallHonestly() {
        val lockedDevice = object : MindDevicePort by device {
            override fun blocker(): String = "the screen is off"
            override fun sleep(ms: Long) {}
        }
        val result = PhoneMindToolbox(FakeEnv(login), FakeOwner(), lockedDevice, "goal", ownerTimeoutMs = 5_000).run("tap", """{"ref":"e1"}""")
        assertFalse(result.ok)
        assertTrue(result.text.contains("still unavailable"))
    }

    @Test fun fieldValuesAreShownButSecretsNever() {
        val screen = FakeScreen("com.android.chrome", login.controls, values = mapOf("email" to "jan@example.com", "pass" to "hunter2"))
        val text = PhoneMindToolbox(FakeEnv(screen), FakeOwner(), device, "goal").run("screen_read").text
        assertTrue(text.contains("e1 text field \"Email\" = \"jan@example.com\""))
        assertTrue(text.contains("e2 password field \"Password\" (hidden)"))
        assertFalse(text.contains("hunter2"))
        val empty = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "goal").run("screen_read").text
        assertTrue(empty.contains("\"Email\" (empty)"))
    }

    @Test fun theWholeScreenIsListedNotJustTheShortlist() {
        val many = FakeScreen("com.android.settings", (1..60).map { Control("k$it", "Setting $it") })
        val text = PhoneMindToolbox(FakeEnv(many), FakeOwner(), device, "goal").run("screen_read").text
        assertTrue(text.contains("e60 button \"Setting 60\""))
    }

    @Test fun screenshotsAreMarkedAndScaled() {
        var seen: List<MindMark> = emptyList()
        val marker = MindImageMarker { _, marks, width, height ->
            seen = marks
            assertEquals(1080, width)
            assertEquals(2400, height)
            MindImage("data:image/jpeg;base64,XYZ", 576, 1280)
        }
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal", marker = marker)
        val look = box.run("screen_look")
        assertEquals("data:image/jpeg;base64,XYZ", look.imageDataUrl)
        assertEquals(listOf("e1", "e2", "e3"), seen.map { it.ref })
        assertTrue(look.text.contains("576×1280"))
        box.run("tap_point", """{"x":288,"y":640}""")
        assertEquals(540, env.acts.last().second.getInt("x"))
        assertEquals(1200, env.acts.last().second.getInt("y"))
    }

    @Test fun screensAccessibilityCannotDescribeComeWithAPicture() {
        val game = FakeScreen("com.example.game", emptyList(), treeUseful = false)
        val env = FakeEnv(game)
        val read = PhoneMindToolbox(env, FakeOwner(), device, "goal").run("screen_read")
        assertNotNull(read.imageDataUrl)
        assertEquals(1, env.images)
        val normal = FakeEnv(login)
        PhoneMindToolbox(normal, FakeOwner(), device, "goal").run("screen_read")
        assertEquals(0, normal.images)
    }

    @Test fun swipesAreGuardedAndGoTheRightWay() {
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        box.run("screen_read")
        assertTrue(box.run("swipe", """{"direction":"left"}""").ok)
        val params = env.acts.last().second
        assertEquals("phone.swipe", env.acts.last().first)
        assertTrue(params.getBoolean("guard"))
        assertTrue("finger moves right to left", params.getInt("x1") > params.getInt("x2"))
        assertEquals(params.getInt("y1"), params.getInt("y2"))
        box.run("swipe", """{"direction":"up","ref":"e3","distance":"short"}""")
        val onRef = env.acts.last().second
        assertTrue(onRef.getInt("y1") > onRef.getInt("y2"))
        assertFalse(box.run("swipe", """{"direction":"sideways"}""").ok)
    }

    @Test fun notificationsHideCodesAndCanBeOpened() {
        val phone = object : MindDevicePort by device {
            override fun notifications() = listOf(
                MindNotification("k1", "com.instagram.android", "Instagram", "482913 is your Instagram code", System.currentTimeMillis()),
                MindNotification("k2", "com.whatsapp", "Sam", "Dinner at 7?", System.currentTimeMillis(), listOf("Reply")),
            )
        }
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), phone, "goal")
        val list = box.run("notifications").text
        assertFalse(list.contains("482913"))
        assertTrue(list.contains("n2 com.whatsapp · Sam: Dinner at 7?"))
        assertTrue(list.contains("[actions: Reply]"))
        assertTrue(box.run("open_notification", """{"id":"n2"}""").ok)
        assertEquals("k2", env.acts.last().second.getString("key"))
        assertFalse(box.run("open_notification", """{"id":"n9"}""").ok)
    }

    @Test fun takeoverWaitsForTheOwnerAndLooksAgain() {
        var asked = ""
        val owner = object : MindOwnerPort by FakeOwner() {
            override fun takeover(instruction: String, timeoutMs: Long): MindOwnerReply { asked = instruction; return MindOwnerReply(true, "solved it", 90_000) }
        }
        val result = PhoneMindToolbox(FakeEnv(login), owner, device, "goal").run("owner_takeover", """{"what":"Solve the CAPTCHA"}""")
        assertEquals("Solve the CAPTCHA", asked)
        assertTrue(result.ok)
        assertTrue(result.text.contains("solved it"))
        assertTrue(result.text.contains("Controls:"))
        assertEquals(90_000, result.ownerWaitMs)
    }

    private val signup = FakeScreen("com.android.chrome", listOf(
        Control("first", "First name", "edit_text", editable = true),
        Control("last", "Surname", "edit_text", editable = true),
        Control("birthday", "Birthday", "spinner"),
        Control("next", "Next"),
    ))

    @Test fun theCheckInCardFillsTextFieldsAndHandsDatesToTheModel() {
        val env = FakeEnv(signup)
        val memory = MindMemory(java.nio.file.Files.createTempFile("memory", ".json").toFile())
        val owner = FakeOwner().apply {
            values = MindValuesReply(MindValuesOutcome.FILLED, mapOf("First name" to "Jan", "Last name" to "Jansen", "Birth date" to "12 march 1990"), remember = true, waitedMs = 25_000)
        }
        val box = PhoneMindToolbox(env, owner, device, "goal", memory = memory, missionId = "m1")
        box.run("screen_read")
        val result = box.run("owner_fill", """{"reason":"Facebook sign-up needs your name and birth date","fields":[
            {"label":"First name","kind":"name","ref":"e1"},{"label":"Last name","kind":"name","ref":"e2"},{"label":"Birth date","kind":"date","ref":"e3"}]}""")
        assertTrue(result.ok)
        assertEquals(listOf("First name", "Last name", "Birth date"), owner.filled.map { it.label })
        val typed = env.acts.filter { it.first == "phone.type" }.map { it.second.getString("value") }
        assertEquals(listOf("Jan", "Jansen"), typed)
        assertTrue(result.text.contains("Birth date = \"12 march 1990\""))
        assertTrue(result.text.contains("Enter Birth date yourself"))
        assertEquals(25_000, result.ownerWaitMs)
        assertEquals(3, memory.all().size)
    }

    @Test fun theCheckInCardRefusesSecretsAndHandlesTakeOverAndDecline() {
        val env = FakeEnv(login)
        val owner = FakeOwner()
        val box = PhoneMindToolbox(env, owner, device, "goal")
        box.run("screen_read")
        assertTrue(box.run("owner_fill", """{"reason":"x","fields":[{"label":"Password","ref":"e2"}]}""").text.contains("vault_fill"))
        assertTrue(box.run("owner_fill", """{"reason":"x","fields":[{"label":"Account","ref":"e2"}]}""").text.contains("password field"))
        val declined = box.run("owner_fill", """{"reason":"x","fields":[{"label":"Email","ref":"e1"}]}""")
        assertFalse(declined.ok)
        assertTrue(declined.text.contains("chose not to"))
        owner.values = MindValuesReply(MindValuesOutcome.TOOK_OVER, waitedMs = 40_000)
        val byHand = box.run("owner_fill", """{"reason":"x","fields":[{"label":"Email","ref":"e1"}]}""")
        assertTrue(byHand.ok)
        assertTrue(byHand.text.contains("filled it in by hand"))
        assertTrue(env.acts.none { it.first == "phone.type" })
    }

    // ---- the map (go_to) ---------------------------------------------------------------------------------------------

    private class MapReader : com.cyclone.mobile.mind.learn.LearnedReader {
        val pkg = "com.android.settings"
        private fun screen(id: String, title: String) = com.cyclone.mobile.applearner.LearnedScreen(id, pkg, id, title, "",
            com.cyclone.mobile.applearner.ScreenRecognition("key-$id", "", emptyList(), null, emptyList()),
            com.cyclone.mobile.applearner.KnowledgeState.UNDERSTOOD, 0.8)
        private fun action(from: String, label: String) = com.cyclone.mobile.applearner.LearnedAction("a-$label", pkg, from, label, label,
            listOf("click"), """{"role":"button"}""", com.cyclone.mobile.applearner.ActionRisk.SAFE)
        override fun screens(packageName: String) = listOf(screen("home", "Settings"), screen("display", "Display"), screen("timeout", "Screen timeout"))
        override fun actions(packageName: String) = listOf(action("home", "Display"), action("display", "Screen timeout"))
        override fun transitions(packageName: String) = listOf(
            com.cyclone.mobile.applearner.LearnedTransition("t1", pkg, "home", "a-Display", "display", successfulCount = 2, observedCount = 2),
            com.cyclone.mobile.applearner.LearnedTransition("t2", pkg, "display", "a-Screen timeout", "timeout"),
        )
    }

    private fun settingsScreens() = mapOf(
        "home" to FakeScreen("com.android.settings", listOf(Control("d", "Display"), Control("n", "Network")), pageKey = "key-home", title = "Settings"),
        "display" to FakeScreen("com.android.settings", listOf(Control("t", "Screen timeout"), Control("b", "Brightness")), pageKey = "key-display", title = "Display"),
        "timeout" to FakeScreen("com.android.settings", listOf(Control("m", "2 minutes")), pageKey = "key-timeout", title = "Screen timeout"),
    )

    @Test fun goToWalksTheLearnedRouteThroughTheNormalActPathAndCountsMapMoves() {
        val screens = settingsScreens()
        val env = FakeEnv(screens.getValue("home"))
        env.onAct = { _, params ->
            when (params.optString("elementId").substringAfterLast(':')) {
                "d" -> env.screen = screens.getValue("display")
                "t" -> env.screen = screens.getValue("timeout")
            }
        }
        val box = PhoneMindToolbox(env, FakeOwner(), device, "set the screen timeout",
            maps = com.cyclone.mobile.mind.map.MindMaps(MapReader()))
        assertTrue(box.specs().any { it.name == "go_to" })

        val first = box.execute(MindToolCall("c1", "screen_read", "{}"), JSONObject())
        assertTrue(first.text, first.text.contains("Map of com.android.settings"))
        assertTrue(first.text, first.text.contains("(you are here)"))
        val again = box.execute(MindToolCall("c2", "screen_read", "{}"), JSONObject())
        assertFalse("the map card is shown once per app", again.text.contains("Map of"))

        val walked = box.execute(MindToolCall("c3", "go_to", "{}"), JSONObject().put("screen", "Screen timeout"))
        assertTrue(walked.text, walked.text.startsWith("Arrived at"))
        assertTrue(walked.text, walked.text.contains("in 2 moves"))
        assertEquals(2, walked.mapMoves)
        assertTrue(walked.ok)
        assertEquals(listOf("phone.click", "phone.click"), env.acts.map { it.first })
        assertTrue(walked.text, walked.text.contains("2 minutes"))
    }

    @Test fun goToStopsWhenTheAppChangedAndIsHiddenWhenTheMapIsOff() {
        val screens = settingsScreens()
        val env = FakeEnv(screens.getValue("home"))
        env.onAct = { _, _ -> env.screen = FakeScreen("com.android.settings", listOf(Control("x", "New page")), pageKey = "key-new", title = "New") }
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal", maps = com.cyclone.mobile.mind.map.MindMaps(MapReader()))
        val stopped = box.execute(MindToolCall("c1", "go_to", "{}"), JSONObject().put("screen", "Screen timeout"))
        assertFalse(stopped.ok)
        assertTrue(stopped.text, stopped.text.startsWith("The map walk stopped after 1 move"))
        assertEquals(1, stopped.mapMoves)

        val unknown = box.execute(MindToolCall("c2", "go_to", "{}"), JSONObject().put("screen", "Bluetooth"))
        assertTrue(unknown.text, unknown.text.startsWith("ERROR:"))

        val off = PhoneMindToolbox(FakeEnv(screens.getValue("home")), FakeOwner(), device, "goal")
        assertFalse(off.specs().any { it.name == "go_to" })
    }

    // ---- the App Manual (alpha.64) ------------------------------------------------------------------------------------

    private class ManualPort(val dict: com.cyclone.mobile.manual.dictionary.AppDictionary) : com.cyclone.mobile.manual.MindManualPort {
        val walks = mutableListOf<Pair<String, Boolean>>()
        override fun view(packageName: String) =
            if (packageName == dict.packageName) com.cyclone.mobile.manual.ManualView.of(dict, "Settings") else null
        override fun walked(packageName: String, abilityId: String, ok: Boolean) { walks += abilityId to ok }
    }

    private fun settingsManual(): com.cyclone.mobile.manual.dictionary.AppDictionary {
        val home = "screen:home:0000000000000001"
        val display = "screen:list:0000000000000002"
        val timeout = "screen:list:0000000000000003"
        val screens = listOf(
            com.cyclone.mobile.manual.dictionary.ScreenCard(home, title = "Settings", items = listOf("Display", "Network"), seen = 3, pageKeys = listOf("key-home")),
            com.cyclone.mobile.manual.dictionary.ScreenCard(display, title = "Display", items = listOf("Screen timeout", "Brightness"), seen = 2, pageKeys = listOf("key-display")),
            com.cyclone.mobile.manual.dictionary.ScreenCard(timeout, title = "Screen timeout", seen = 1, pageKeys = listOf("key-timeout")),
        ).associateBy { it.roomKey }
        val doors = listOf(
            com.cyclone.mobile.manual.dictionary.DoorCard("edge:" + "1".repeat(16), home, display, "Display", "settings", 5),
            com.cyclone.mobile.manual.dictionary.DoorCard("edge:" + "2".repeat(16), display, timeout, "Screen timeout", "menu", 5),
        ).associateBy { it.edgeId }
        return com.cyclone.mobile.manual.dictionary.AppDictionary("com.android.settings", screens = screens, doors = doors)
    }

    @Test fun theManualFindsAbilitiesAndGoToWalksOneThroughTheActPath() {
        val screens = settingsScreens()
        val env = FakeEnv(screens.getValue("home"))
        env.onAct = { _, params ->
            when (params.optString("elementId").substringAfterLast(':')) {
                "d" -> env.screen = screens.getValue("display")
                "t" -> env.screen = screens.getValue("timeout")
            }
        }
        val port = ManualPort(settingsManual())
        val box = PhoneMindToolbox(env, FakeOwner(), device, "set the screen timeout", manual = port)
        assertTrue(box.specs().map { it.name }.containsAll(listOf("go_to", "abilities_find", "how_to_find")))

        val first = box.run("screen_read")
        assertTrue(first.text, first.text.contains("Manual of Settings"))
        assertTrue(first.text, first.text.contains("a1 Open Screen timeout → Settings › Display › Screen timeout"))

        val found = box.run("abilities_find", """{"goal":"change the brightness"}""")
        assertTrue(found.text, found.text.contains("Brightness (on Display)"))

        val walked = box.run("go_to", """{"ability":"a1"}""")
        assertTrue(walked.text, walked.text.startsWith("Walked “Open Screen timeout” from the manual in 2 moves"))
        assertTrue(walked.ok)
        assertEquals(2, walked.mapMoves)
        assertEquals(listOf("phone.click", "phone.click"), env.acts.map { it.first })
        assertEquals(1, port.walks.size)
        assertTrue(port.walks.single().second)

        val unknown = box.run("go_to", """{"ability":"a99"}""")
        assertTrue(unknown.text, unknown.text.startsWith("ERROR:"))
        assertFalse(PhoneMindToolbox(env, FakeOwner(), device, "goal").specs().any { it.name == "abilities_find" })
    }

    @Test fun aManualWalkStopsAtTheFirstSurpriseAndCountsAgainstTheAbility() {
        val screens = settingsScreens()
        val env = FakeEnv(screens.getValue("home"))
        env.onAct = { _, _ -> env.screen = FakeScreen("com.android.settings", listOf(Control("x", "New page")), pageKey = "key-new", title = "New") }
        val port = ManualPort(settingsManual())
        val box = PhoneMindToolbox(env, FakeOwner(), device, "set the screen timeout", manual = port)
        box.run("screen_read")
        val stopped = box.run("go_to", """{"ability":"a1"}""")
        assertFalse(stopped.ok)
        assertTrue(stopped.text, stopped.text.startsWith("The manual walk stopped after 1 move"))
        assertEquals(listOf(false), port.walks.map { it.second })
    }

    // ---- plan 38: plan versions in every run, whatever the settings ------------------------------------------------

    @Test fun aDeclaredDivertBecomesPlanV2WithTheBranchTheOwnerSees() {
        var shownVersion = 0
        var shownLabel: String? = null
        var diverted = ""
        val base = FakeOwner()
        val owner = object : MindOwnerPort by base {
            override fun planVersion(version: Int, label: String?, history: List<List<MindPlanStep>>) { shownVersion = version; shownLabel = label }
            override fun diverted(from: String, to: String, why: String) { diverted = "$from -> $to ($why)" }
        }
        val box = PhoneMindToolbox(FakeEnv(login), owner, device, "Tell Louella when I'll be there")
        box.run("plan_update", """{"steps":[{"step":"Read her address","status":"done"},{"step":"DM her on Instagram","status":"doing"}]}""")
        assertEquals(0, shownVersion)
        val result = box.run("plan_update", """{"steps":[{"step":"Read her address","status":"done"},{"step":"Message her on WhatsApp","status":"doing"}],
            "divert":{"from":"Instagram DM","to":"WhatsApp","why":"Her DMs are closed"}}""")
        assertTrue(result.text.contains("Plan v2"))
        assertEquals(2, shownVersion)
        assertEquals("Changed course", shownLabel)
        assertEquals("Instagram DM -> WhatsApp (Her DMs are closed)", diverted)
        assertTrue(base.planned.any { it.dropped && it.text == "DM her on Instagram" })
        assertTrue(base.planned.any { it.branch && it.note == "Her DMs are closed" })
    }

    @Test fun aSteerThatIsNeverPlannedForGetsOneFinishReminder() {
        val box = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "Set a timer for 5 minutes")
        box.run("plan_update", """{"steps":[{"step":"Set 5 minutes","status":"doing"}]}""")
        assertEquals(2, box.onSteer("Make it 3 minutes instead"))
        val first = box.run("task_finish", """{"summary":"Timer running","evidence":"4:59"}""")
        assertFalse(first.ok)
        assertTrue(first.text.contains("goal v2"))
        box.run("plan_update", """{"steps":[{"step":"Set 3 minutes","status":"done"}]}""")
        assertEquals(1, box.planVersions.history.count { it.trigger == com.cyclone.mobile.mind.divert.PlanVersions.STEER })
    }
}
