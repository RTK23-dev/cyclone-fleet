package com.cyclone.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTypeEngineTest {
    private val taskValue = "task-one"
    private val secretAttempt = "x"

    @Test
    fun locateClickRelocateAuthorizedTypeFailsWhenSelectorDropsObservationId() {
        val before = phoneTaskScreen(observationId = "obs-before", focused = false, rawNodeId = "raw-task-a")
        val located = before.catalog.elements.getValue("semantic:obs-before:task_input")
        assertTrue(located.editable)
        assertEquals("raw-task-a", located.rawNodeId)

        // Click then IME/keyboard shifts bounds so the next observation mints a new scoped ID.
        val afterClick = phoneTaskScreen(observationId = "obs-after", focused = false, rawNodeId = "raw-task-b")
        val relocated = afterClick.catalog.elements.getValue("semantic:obs-after:task_input")
        assertNotEquals(located.elementId, relocated.elementId)

        val legacySelector = ElementSelector.fromJson(JSONObject().put("id", relocated.elementId))
        assertTrue(
            "id-only selector stays empty; 3.8.4 hard-errors instead of clicking the top-left node",
            legacySelector.isEmpty(),
        )
        try {
            SelectorEngine.resolve(afterClick.snapshot, legacySelector, 8)
            org.junit.Assert.fail("empty selector must be rejected")
        } catch (error: EmptySelectorException) {
            assertTrue(error.message!!.contains("empty selector"))
        }

        val host = FakeLiveHost.from(afterClick, initialText = "")
        // Legacy setText path: empty selector / first node. The task field stays empty.
        assertEquals("", host.textOf("raw-task-b"))
        val unauthorized = PhoneTypeEngine.decide(
            JSONObject()
                .put("selector", JSONObject().put("id", relocated.elementId))
                .put("value", taskValue),
            afterClick.catalog,
        )
        assertTrue(unauthorized is PhoneTypeEngine.Decision.Reject)
        val deny = (unauthorized as PhoneTypeEngine.Decision.Reject).deny
        assertEquals(PhoneToolErrorCode.POLICY_DENIED, deny.code)
        assertFalse(deny.message.contains(taskValue))
        assertEquals("", host.textOf("raw-task-b"))
    }

    @Test
    fun authorizedTypeOnCurrentEditableSucceedsAndVerifiesAfterState() {
        val screen = phoneTaskScreen(observationId = "obs-now", focused = true, rawNodeId = "raw-task-now")
        val params = authorizedType(screen.taskElementId, taskValue)
        val decision = PhoneTypeEngine.decide(params, screen.catalog)
        val plan = (decision as PhoneTypeEngine.Decision.Execute).plan
        assertEquals(screen.taskElementId, plan.elementId)
        assertFalse(plan.needsFocus)

        val host = FakeLiveHost.from(screen, initialText = "")
        val result = PhoneTypeEngine.perform(plan, taskValue, host)
        assertTrue(result.ok)
        assertTrue(result.setTextPerformed)
        assertTrue(result.afterStateVerified)
        assertEquals(taskValue.length, result.charCount)
        assertEquals(PhoneTypeEngine.digest(taskValue), result.textDigest)
        assertEquals(taskValue, host.textOf("raw-task-now"))
        assertFalse(PhoneTypeEngine.reportContainsPlaintext(result.toPayload().toString(), taskValue))
    }

    @Test
    fun unfocusedEditableRecoversWithFocusThenSetText() {
        val screen = phoneTaskScreen(observationId = "obs-focus", focused = false, rawNodeId = "raw-unfocused")
        val plan = (PhoneTypeEngine.decide(authorizedType(screen.taskElementId, taskValue), screen.catalog)
            as PhoneTypeEngine.Decision.Execute).plan
        assertTrue(plan.needsFocus)

        val host = FakeLiveHost.from(screen, initialText = "", startFocused = false)
        val result = PhoneTypeEngine.perform(plan, taskValue, host)
        assertTrue(result.ok)
        assertTrue(result.focusRecovered)
        assertTrue(host.focused("raw-unfocused"))
        assertEquals(taskValue, host.textOf("raw-unfocused"))
        assertTrue(result.afterStateVerified)
    }

    @Test
    fun staleObservationIdIsDeniedAndFieldUnchanged() {
        val current = phoneTaskScreen(observationId = "obs-current", focused = true, rawNodeId = "raw-current")
        val staleId = "semantic:obs-previous:task_input"
        val host = FakeLiveHost.from(current, initialText = "")
        val decision = PhoneTypeEngine.decide(authorizedType(staleId, taskValue), current.catalog)
        val deny = (decision as PhoneTypeEngine.Decision.Reject).deny
        assertEquals(PhoneToolErrorCode.STALE_ELEMENT, deny.code)
        assertEquals("", host.textOf("raw-current"))
        assertFalse(deny.message.contains(taskValue))
    }

    @Test
    fun missingCurrentElementIdIsDenied() {
        val current = phoneTaskScreen(observationId = "obs-current", focused = true, rawNodeId = "raw-current")
        val decision = PhoneTypeEngine.decide(authorizedType("semantic:obs-current:missing", taskValue), current.catalog)
        assertEquals(PhoneToolErrorCode.STALE_ELEMENT, (decision as PhoneTypeEngine.Decision.Reject).deny.code)
    }

    @Test
    fun nonEditableTargetIsDenied() {
        val screen = phoneTaskScreen(observationId = "obs-btn", focused = false, rawNodeId = "raw-btn", editable = false, role = "button")
        val decision = PhoneTypeEngine.decide(authorizedType(screen.taskElementId, taskValue), screen.catalog)
        assertEquals(PhoneToolErrorCode.INVALID_REQUEST, (decision as PhoneTypeEngine.Decision.Reject).deny.code)
    }

    @Test
    fun passwordFieldIsPolicyDenied() {
        assertSensitiveDenied(
            resourceId = "com.example:id/password",
            contentDescription = "Password",
            role = "textbox",
        )
    }

    @Test
    fun passwordFlagIsPolicyDeniedEvenWhenLabelsAreNeutral() {
        val screen = phoneTaskScreen(
            observationId = "obs-password-flag",
            focused = true,
            rawNodeId = "raw-password-flag",
            resourceId = "com.example:id/input",
            contentDescription = "Input",
            role = "textbox",
            password = true,
        )
        val decision = PhoneTypeEngine.decide(
            authorizedType(screen.taskElementId, secretAttempt),
            screen.catalog,
        )
        assertEquals(
            PhoneToolErrorCode.POLICY_DENIED,
            (decision as PhoneTypeEngine.Decision.Reject).deny.code,
        )
    }

    @Test
    fun otpFieldIsPolicyDenied() {
        assertSensitiveDenied(
            resourceId = "com.example:id/otp_code",
            contentDescription = "One-time code",
            role = "textbox",
        )
    }

    @Test
    fun paymentFieldIsPolicyDenied() {
        assertSensitiveDenied(
            resourceId = "com.example:id/card_number",
            contentDescription = "Card number",
            role = "textbox",
        )
    }

    @Test
    fun missingUserAuthorizedIsPolicyDenied() {
        val screen = phoneTaskScreen(observationId = "obs-auth", focused = true, rawNodeId = "raw-auth")
        val decision = PhoneTypeEngine.decide(
            JSONObject()
                .put("elementId", screen.taskElementId)
                .put("value", taskValue),
            screen.catalog,
        )
        assertEquals(PhoneToolErrorCode.POLICY_DENIED, (decision as PhoneTypeEngine.Decision.Reject).deny.code)
    }

    @Test
    fun userAuthorizedFalseIsPolicyDenied() {
        val screen = phoneTaskScreen(observationId = "obs-auth2", focused = true, rawNodeId = "raw-auth2")
        val decision = PhoneTypeEngine.decide(
            JSONObject()
                .put("elementId", screen.taskElementId)
                .put("value", taskValue)
                .put("user_authorized", false),
            screen.catalog,
        )
        assertEquals(PhoneToolErrorCode.POLICY_DENIED, (decision as PhoneTypeEngine.Decision.Reject).deny.code)
    }

    @Test
    fun textFuzzyBoundsAndCoordinateSelectorsAreRejected() {
        val screen = phoneTaskScreen(observationId = "obs-sel", focused = true, rawNodeId = "raw-sel")
        listOf(
            JSONObject().put("text", "Task"),
            JSONObject().put("fuzzyText", "task"),
            JSONObject().put("x", 40).put("y", 80),
            JSONObject().put("bounds", JSONObject().put("left", 0)),
        ).forEach { selector ->
            val decision = PhoneTypeEngine.decide(
                JSONObject()
                    .put("selector", selector.put("id", screen.taskElementId))
                    .put("value", taskValue)
                    .put("user_authorized", true),
                screen.catalog,
            )
            assertEquals(
                selector.toString(),
                PhoneToolErrorCode.INVALID_REQUEST,
                (decision as PhoneTypeEngine.Decision.Reject).deny.code,
            )
        }
    }

    @Test
    fun whenSetTextDoesNothingTheLadderPastesAndProvesTheText() {
        val screen = phoneTaskScreen(observationId = "obs-paste", focused = true, rawNodeId = "raw-paste")
        val plan = (PhoneTypeEngine.decide(authorizedType(screen.taskElementId, taskValue), screen.catalog)
            as PhoneTypeEngine.Decision.Execute).plan
        val host = FakeLiveHost.from(screen, initialText = "", applySetText = false, reportSetText = true, pasteWorks = true)
        val result = PhoneTypeEngine.perform(plan, taskValue, host)
        assertTrue(result.ok)
        assertEquals("paste", result.method)
        assertTrue(result.textVerified)
        assertEquals(taskValue, host.textOf("raw-paste"))
        assertEquals("ACTION_PASTE", result.toPayload().getString("action"))
        assertFalse(result.toPayload().toString().contains(taskValue))
    }

    @Test
    fun setTextThatWorksIsProvenWithoutPasting() {
        val screen = phoneTaskScreen(observationId = "obs-set", focused = true, rawNodeId = "raw-set")
        val plan = (PhoneTypeEngine.decide(authorizedType(screen.taskElementId, taskValue), screen.catalog)
            as PhoneTypeEngine.Decision.Execute).plan
        val host = FakeLiveHost.from(screen, initialText = "", pasteWorks = true)
        val result = PhoneTypeEngine.perform(plan, taskValue, host)
        assertTrue(result.ok && result.textVerified)
        assertEquals("set_text", result.method)
        assertEquals(0, host.pastes)
    }

    @Test
    fun aFieldThatShowsTheTextAFrameLateIsReadAgainNotPastedOver() {
        val screen = phoneTaskScreen(observationId = "obs-late", focused = true, rawNodeId = "raw-late")
        val plan = (PhoneTypeEngine.decide(authorizedType(screen.taskElementId, taskValue), screen.catalog)
            as PhoneTypeEngine.Decision.Execute).plan
        val host = FakeLiveHost.from(screen, initialText = "", pasteWorks = true, lateSetText = true)
        val result = PhoneTypeEngine.perform(plan, taskValue, host)
        assertTrue(result.ok && result.textVerified)
        assertEquals("set_text", result.method)
        assertEquals(1, host.settles)
        assertEquals(0, host.pastes)
    }

    @Test
    fun aFieldThatNeverShowsTheTextIsReportedUnverified() {
        val screen = phoneTaskScreen(observationId = "obs-none", focused = true, rawNodeId = "raw-none")
        val plan = (PhoneTypeEngine.decide(authorizedType(screen.taskElementId, taskValue), screen.catalog)
            as PhoneTypeEngine.Decision.Execute).plan
        val host = FakeLiveHost.from(screen, initialText = "", applySetText = false, reportSetText = true)
        val result = PhoneTypeEngine.perform(plan, taskValue, host)
        assertFalse(result.textVerified)
        assertFalse(result.toPayload().getBoolean("textVerified"))
        assertEquals(1, host.pastes)
    }

    @Test
    fun longDraftsArePastedFirst() {
        val long = "word ".repeat(1_000)
        val screen = phoneTaskScreen(observationId = "obs-long", focused = true, rawNodeId = "raw-long")
        val plan = (PhoneTypeEngine.decide(authorizedType(screen.taskElementId, long), screen.catalog)
            as PhoneTypeEngine.Decision.Execute).plan
        val host = FakeLiveHost.from(screen, initialText = "", pasteWorks = true)
        val result = PhoneTypeEngine.perform(plan, long, host)
        assertTrue(result.ok && result.textVerified)
        assertEquals("paste", result.method)
        assertEquals(0, host.setTexts)
    }

    @Test
    fun focusedTypingPicksTheOneFocusedTextBoxAndRefusesSecrets() {
        val params = JSONObject().put("focused", true).put("value", taskValue).put("user_authorized", true)
        val focused = phoneTaskScreen(observationId = "obs-f", focused = true, rawNodeId = "raw-f")
        val plan = (PhoneTypeEngine.decide(params, focused.catalog) as PhoneTypeEngine.Decision.Execute).plan
        assertEquals("raw-f", plan.rawNodeId)
        val none = phoneTaskScreen(observationId = "obs-n", focused = false, rawNodeId = "raw-n")
        assertTrue(PhoneTypeEngine.decide(params, none.catalog) is PhoneTypeEngine.Decision.Reject)
        val secret = phoneTaskScreen(observationId = "obs-s", focused = true, rawNodeId = "raw-s", password = true)
        assertEquals(PhoneToolErrorCode.POLICY_DENIED,
            (PhoneTypeEngine.decide(params, secret.catalog) as PhoneTypeEngine.Decision.Reject).deny.code)
        assertTrue(PhoneTypeEngine.decide(JSONObject(params.toString()).put("user_authorized", false), focused.catalog)
            is PhoneTypeEngine.Decision.Reject)
    }

    @Test
    fun overlayComposerTypePassesWhenSetTextTrueAndFieldStaysFocused() {
        val screen = phoneTaskScreen(observationId = "obs-empty", focused = true, rawNodeId = "raw-empty")
        val plan = (PhoneTypeEngine.decide(authorizedType(screen.taskElementId, taskValue), screen.catalog)
            as PhoneTypeEngine.Decision.Execute).plan
        val host = FakeLiveHost.from(screen, initialText = "", applySetText = false, reportSetText = true)
        val result = PhoneTypeEngine.perform(plan, taskValue, host)
        assertTrue(result.ok)
        assertTrue(result.setTextPerformed)
        assertTrue(result.afterStateVerified)
        assertTrue(host.focused("raw-empty"))
        assertFalse(result.toPayload().toString().contains(taskValue))
    }

    @Test
    fun redactedSecretVerificationRequiresExactInProcessMatchAndExportsNoDigest() {
        val screen = phoneTaskScreen(observationId = "obs-secret", focused = true, rawNodeId = "raw-secret")
        val plan = PhoneTypeEngine.ExecutePlan(
            elementId = screen.taskElementId,
            rawNodeId = "raw-secret",
            path = screen.snapshot.nodes.first { it.id == "raw-secret" }.path,
            needsFocus = false,
            valueLength = taskValue.length,
            valueDigest = PhoneTypeEngine.digest(taskValue),
        )
        val good = FakeLiveHost.from(screen, initialText = "")
        val verified = PhoneTypeEngine.perform(plan, taskValue, good, redactObservedText = true)
        assertTrue(verified.ok)
        assertTrue(verified.afterStateVerified)
        assertEquals("<redacted>", verified.textDigest)
        assertEquals(0, verified.charCount)
        assertFalse(verified.toPayload().toString().contains(taskValue))
        assertFalse(verified.toPayload().toString().contains(PhoneTypeEngine.digest(taskValue)))

        val rejected = FakeLiveHost.from(screen, initialText = "", applySetText = false, reportSetText = true)
        val failed = PhoneTypeEngine.perform(plan, taskValue, rejected, redactObservedText = true)
        assertFalse(failed.ok)
        assertFalse(failed.afterStateVerified)
    }

    @Test
    fun redactedPasswordVerificationAcceptsMaskedLengthChangeButNotSameLengthNoOp() {
        val screen = phoneTaskScreen(
            observationId = "obs-masked-secret",
            focused = true,
            rawNodeId = "raw-masked-secret",
            resourceId = "com.example:id/password",
            contentDescription = "Password",
            password = true,
        )
        val path = screen.snapshot.nodes.first { it.id == "raw-masked-secret" }.path
        val plan = PhoneTypeEngine.ExecutePlan(
            elementId = screen.taskElementId,
            rawNodeId = "raw-masked-secret",
            path = path,
            needsFocus = false,
            valueLength = taskValue.length,
            valueDigest = PhoneTypeEngine.digest(taskValue),
        )

        val masked = FakeLiveHost.from(screen, initialText = "", maskSetText = true)
        val filled = PhoneTypeEngine.perform(plan, taskValue, masked, redactObservedText = true)
        assertTrue(filled.ok)
        assertTrue(filled.afterStateVerified)

        val sameLengthNoOp = FakeLiveHost.from(
            screen,
            initialText = "•".repeat(taskValue.length),
            applySetText = false,
            reportSetText = true,
            maskSetText = true,
        )
        val failed = PhoneTypeEngine.perform(plan, taskValue, sameLengthNoOp, redactObservedText = true)
        assertFalse(failed.ok)
        assertFalse(failed.afterStateVerified)
    }

    @Test
    fun redactedParamsAndReportsNeverContainTypedPlaintext() {
        val screen = phoneTaskScreen(observationId = "obs-redact", focused = true, rawNodeId = "raw-redact")
        val params = authorizedType(screen.taskElementId, taskValue)
        val redacted = PhoneTypeEngine.redactedParams(params)
        assertEquals("<redacted>", redacted.getString("value"))
        assertFalse(redacted.toString().contains(taskValue))
        assertEquals(screen.taskElementId, redacted.getString("elementId"))
        assertTrue(redacted.getBoolean("user_authorized"))

        val signature = PhoneTypeEngine.duplicateSignature("phone.type", params)
        assertFalse(signature.contains(taskValue))
        assertTrue(signature.contains(PhoneTypeEngine.digest(taskValue)))
    }

    @Test
    fun gatewayPrivacyRedactsTypeValue() {
        val params = JSONObject()
            .put("elementId", "semantic:obs:task_input")
            .put("value", taskValue)
            .put("user_authorized", true)
        val redacted = com.cyclone.mobile.gateway.GatewayPrivacy.redactActionParams("phone.type", params)
        assertEquals("<redacted>", redacted.getString("value"))
        assertFalse(redacted.toString().contains(taskValue))
    }

    private fun assertSensitiveDenied(resourceId: String, contentDescription: String, role: String) {
        val screen = phoneTaskScreen(
            observationId = "obs-sensitive",
            focused = true,
            rawNodeId = "raw-sensitive",
            resourceId = resourceId,
            contentDescription = contentDescription,
            role = role,
        )
        val decision = PhoneTypeEngine.decide(authorizedType(screen.taskElementId, secretAttempt), screen.catalog)
        val deny = (decision as PhoneTypeEngine.Decision.Reject).deny
        assertEquals(PhoneToolErrorCode.POLICY_DENIED, deny.code)
        assertFalse(deny.message.contains(secretAttempt))
        assertFalse(PhoneTypeEngine.redactedParams(authorizedType(screen.taskElementId, secretAttempt)).toString().contains(secretAttempt))
    }

    private fun authorizedType(elementId: String, value: String): JSONObject = JSONObject()
        .put("elementId", elementId)
        .put("value", value)
        .put("user_authorized", true)

    private fun phoneTaskScreen(
        observationId: String,
        focused: Boolean,
        rawNodeId: String,
        editable: Boolean = true,
        role: String = "textbox",
        resourceId: String = "com.cyclone.mobile:id/task_input",
        contentDescription: String = "Task",
        password: Boolean = false,
    ): PhoneTaskScreen {
        val chrome = node(
            id = "raw-chrome",
            path = "0/0",
            text = "IDLE",
            role = "text",
            editable = false,
            focused = false,
            bounds = UiBounds(0, 0, 200, 40),
        )
        val task = node(
            id = rawNodeId,
            path = "0/1",
            text = "",
            role = role,
            editable = editable,
            focused = focused,
            resourceId = resourceId,
            contentDescription = contentDescription,
            className = if (editable) "android.widget.EditText" else "android.widget.Button",
            password = password,
            actions = if (editable) listOf("ACTION_SET_TEXT", "ACTION_FOCUS") else listOf("ACTION_CLICK"),
            bounds = UiBounds(16, 80, 360, 128),
        )
        val snapshot = UiSnapshot(
            packageName = "com.cyclone.mobile",
            className = "OverlayHost",
            screenWidth = 1080,
            screenHeight = 2400,
            timestampMs = 1L,
            fingerprint = "fp-$observationId",
            controller = "agent",
            windows = emptyList(),
            nodes = listOf(chrome, task),
        )
        val taskElementId = "semantic:$observationId:task_input"
        val evidence = JSONObject()
            .put("elementId", taskElementId)
            .put("observationId", observationId)
            .put("source", "semantic")
            .put("rawNodeId", rawNodeId)
            .put("path", task.path)
            .put("role", role)
            .put("class", task.className)
            .put("resourceId", resourceId)
            .put("contentDescription", contentDescription)
            .put("editable", editable)
            .put("focused", focused)
            .put("focusable", true)
            .put("enabled", true)
            .put("androidActions", JSONArray(task.actions))
        val catalog = PhoneTypeEngine.catalog(
            observationId = observationId,
            evidenceElements = listOf(
                PhoneTypeEngine.ObservationElementInput(taskElementId, "semantic", role, evidence),
            ),
            snapshot = snapshot,
        )
        return PhoneTaskScreen(taskElementId, snapshot, catalog)
    }

    private fun node(
        id: String,
        path: String,
        text: String,
        role: String,
        editable: Boolean,
        focused: Boolean,
        resourceId: String = "",
        contentDescription: String = "",
        className: String = "android.view.View",
        actions: List<String> = emptyList(),
        bounds: UiBounds = UiBounds(0, 0, 10, 10),
        password: Boolean = false,
    ) = UiNodeSnapshot(
        id = id,
        path = path,
        parentId = null,
        childIds = emptyList(),
        depth = path.count { it == '/' },
        windowId = 1,
        className = className,
        role = role,
        text = text,
        contentDescription = contentDescription,
        resourceId = resourceId,
        bounds = bounds,
        clickable = !editable,
        longClickable = false,
        editable = editable,
        scrollable = false,
        enabled = true,
        selected = false,
        checked = false,
        checkable = false,
        focused = focused,
        focusable = true,
        visibleToUser = true,
        actions = actions,
        password = password,
    )

    private data class PhoneTaskScreen(
        val taskElementId: String,
        val snapshot: UiSnapshot,
        val catalog: PhoneTypeEngine.Catalog,
    )

    private class FakeLiveHost(
        private val nodes: MutableMap<String, FakeNode>,
        private val pathToRaw: Map<String, String>,
        private val applySetText: Boolean,
        private val reportSetText: Boolean,
        private val maskSetText: Boolean,
        private val pasteWorks: Boolean = false,
        private val lateSetText: Boolean = false,
    ) : PhoneTypeEngine.LiveHost {
        var pastes = 0
        var setTexts = 0
        var settles = 0
        private var pending: Pair<FakeNode, String>? = null

        override fun settle() {
            settles++
            pending?.let { (node, text) -> node.text = text }
            pending = null
        }

        override fun readText(handle: Any): CharSequence? = (handle as? FakeNode)?.takeUnless { it.password }?.text

        override fun paste(handle: Any, value: CharSequence): Boolean {
            val node = handle as? FakeNode ?: return false
            pastes++
            if (pasteWorks) node.text = value.toString()
            return pasteWorks
        }

        override fun resolve(plan: PhoneTypeEngine.ExecutePlan): Any? {
            val raw = pathToRaw[plan.path] ?: plan.rawNodeId
            return nodes[raw]
        }

        override fun view(handle: Any, redactText: Boolean): PhoneTypeEngine.LiveView? {
            val node = handle as? FakeNode ?: return null
            return PhoneTypeEngine.LiveView(
                rawNodeId = node.rawId,
                path = node.path,
                editable = node.editable,
                focused = node.focused,
                enabled = node.enabled,
                textLength = node.text.length,
                textDigest = if (redactText) "<redacted>" else PhoneTypeEngine.digest(node.text),
                actions = node.actions,
                password = node.password,
            )
        }

        override fun focus(handle: Any): Boolean {
            val node = handle as FakeNode
            if (!node.enabled) return false
            node.focused = true
            return true
        }

        override fun click(handle: Any): Boolean {
            val node = handle as FakeNode
            node.focused = true
            return true
        }

        override fun setText(handle: Any, value: CharSequence): Boolean {
            setTexts++
            val node = handle as FakeNode
            if (!reportSetText && !applySetText) return false
            if (applySetText && lateSetText) {
                pending = node to value.toString()
            } else if (applySetText) {
                node.text = if (maskSetText && node.password) "•".repeat(value.length) else value.toString()
            }
            return reportSetText
        }

        override fun matchesText(handle: Any, value: CharSequence): Boolean {
            val node = handle as? FakeNode ?: return false
            if (node.text.length != value.length) return false
            return node.text.indices.all { index -> node.text[index] == value[index] }
        }

        override fun refresh(handle: Any): Any? = handle as? FakeNode

        fun textOf(rawId: String): String = nodes.getValue(rawId).text
        fun focused(rawId: String): Boolean = nodes.getValue(rawId).focused

        companion object {
            fun from(
                screen: PhoneTaskScreen,
                initialText: String,
                startFocused: Boolean? = null,
                applySetText: Boolean = true,
                reportSetText: Boolean = true,
                maskSetText: Boolean = false,
                pasteWorks: Boolean = false,
                lateSetText: Boolean = false,
            ): FakeLiveHost {
                val nodes = screen.snapshot.nodes.associate { node ->
                    node.id to FakeNode(
                        rawId = node.id,
                        path = node.path,
                        editable = node.editable,
                        focused = startFocused ?: node.focused,
                        enabled = node.enabled,
                        text = if (node.editable) initialText else node.text,
                        actions = node.actions,
                        password = node.password,
                    )
                }.toMutableMap()
                return FakeLiveHost(
                    nodes = nodes,
                    pathToRaw = screen.snapshot.nodes.associate { it.path to it.id },
                    applySetText = applySetText,
                    reportSetText = reportSetText,
                    maskSetText = maskSetText,
                    pasteWorks = pasteWorks,
                    lateSetText = lateSetText,
                )
            }
        }
    }

    private class FakeNode(
        val rawId: String,
        val path: String,
        val editable: Boolean,
        var focused: Boolean,
        val enabled: Boolean,
        var text: String,
        val actions: List<String>,
        val password: Boolean,
    )
}
