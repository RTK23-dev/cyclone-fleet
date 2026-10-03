package com.cyclone.mobile.applearner

import com.cyclone.mobile.automation.StepType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLearnerCoreTest {
    @Test
    fun dynamicOrderInstancesNormalizeToSameScreenType() {
        val a = ScreenSemanticizer.fromSnapshot("com.shop", snapshot("Order #918281", "order_title", "Track package"))
        val b = ScreenSemanticizer.fromSnapshot("com.shop", snapshot("Order #392918", "order_title", "Track package"))
        assertTrue(ScreenSemanticizer.similarity(a.recognition, b.recognition) >= 0.80)
        assertEquals(ScreenSemanticizer.normalizeDynamicText("Order #918281"), ScreenSemanticizer.normalizeDynamicText("Order #392918"))
    }

    @Test
    fun safePolicyBlocksConsequentialAndAuthenticationActions() {
        assertEquals(ActionRisk.CONSEQUENTIAL, ActionSafetyPolicy.classify("Place order"))
        assertEquals(ActionRisk.CONSEQUENTIAL, ActionSafetyPolicy.classify("Delete account"))
        assertEquals(ActionRisk.AUTHENTICATION, ActionSafetyPolicy.classify("Enter 2FA code"))
        assertEquals(ActionRisk.SAFE, ActionSafetyPolicy.classify("Open invoices"))
    }

    @Test
    fun semanticizerExtractsAdvertisedActionsAndDoesNotAnchorSensitiveFieldValues() {
        val snapshot = JSONObject()
            .put("package", "com.example")
            .put("class", "com.example.AccountActivity")
            .put("nodes", JSONArray()
                .put(node("Account", "title", clickable = false))
                .put(node("hunter2", "password_field", clickable = false, editable = true))
                .put(node("Invoices", "invoices_button", clickable = true).put("actions", JSONArray().put("ACTION_CLICK"))))
        val candidate = ScreenSemanticizer.fromSnapshot("com.example", snapshot)
        assertTrue(candidate.actions.any { "ACTION_CLICK" in it.androidActions })
        assertFalse(candidate.recognition.stableAnchors.any { it.contains("hunter2") })
    }

    @Test
    fun graphFindsGoalRouteAndCompilesDisabledAutomation() {
        val home = screen("home", "Home", 0.9)
        val orders = screen("orders", "Orders", 0.9)
        val invoice = screen("invoice", "Invoice", 0.9)
        val a1 = action(home.id, "orders", "Orders", "orders_button", 0.88)
        val a2 = action(orders.id, "invoice", "Invoice", "invoice_button", 0.88)
        val t1 = LearnedTransition(packageName = "com.shop", fromScreenId = home.id, actionId = a1.id, toScreenId = orders.id, knowledgeState = KnowledgeState.VERIFIED, confidence = 0.9)
        val t2 = LearnedTransition(packageName = "com.shop", fromScreenId = orders.id, actionId = a2.id, toScreenId = invoice.id, knowledgeState = KnowledgeState.VERIFIED, confidence = 0.9)
        val graph = AppGraphSnapshot(
            app = LearnedApp("com.shop", "Shop", knowledgeState = KnowledgeState.UNDERSTOOD, confidence = 0.9),
            screens = listOf(home, orders, invoice),
            actions = listOf(a1, a2),
            transitions = listOf(t1, t2),
        )
        val path = AppGraphRetriever.findBestPath(graph, "download invoice")
        assertNotNull(path)
        assertEquals("Invoice", path!!.end.title)
        val automation = GraphAutomationCompiler.compile(graph, path, "Download invoice")
        assertFalse(automation.enabled)
        assertEquals("phone.open_app", automation.steps.first().parameters["tool"])
        assertTrue(automation.steps.any { it.type == StepType.PHONE_TOOL && it.parameters["tool"] == "phone.click" })
    }

    @Test
    fun retrievalReturnsOnlyGoalRelevantSubset() {
        val home = screen("home", "Home", 0.8)
        val battery = screen("battery", "Battery", 0.9)
        val privacy = screen("privacy", "Privacy", 0.9)
        val graph = AppGraphSnapshot(
            LearnedApp("com.android.settings", "Settings", confidence = 0.8),
            listOf(home, battery, privacy),
            emptyList(), emptyList(),
        )
        val retrieval = AppGraphRetriever.retrieve(graph, "open battery settings", maxItems = 1)
        assertEquals("Battery", retrieval.getJSONArray("screens").getJSONObject(0).getString("title"))
    }

    @Test
    fun verifiedSafeTransitionBecomesSkillCandidate() {
        val home = screen("home", "Home", 0.9)
        val orders = screen("orders", "Orders", 0.9)
        val action = action(home.id, "open_orders", "Orders", "orders_button", 0.92).copy(knowledgeState = KnowledgeState.VERIFIED)
        val transition = LearnedTransition(packageName = "com.shop", fromScreenId = home.id, actionId = action.id, toScreenId = orders.id, knowledgeState = KnowledgeState.VERIFIED, confidence = 0.91)
        val graph = AppGraphSnapshot(LearnedApp("com.shop", "Shop", confidence = 0.9), listOf(home, orders), listOf(action), listOf(transition))
        val candidates = SkillCandidateGenerator.candidates(graph)
        assertTrue(candidates.any { it.name == "open_orders" && it.state == KnowledgeState.VERIFIED })
    }

    private fun snapshot(title: String, resource: String, actionLabel: String): JSONObject = JSONObject()
        .put("package", "com.shop")
        .put("class", "com.shop.OrderActivity")
        .put("nodes", JSONArray()
            .put(node(title, resource, clickable = false))
            .put(node(actionLabel, "track_button", clickable = true).put("actions", JSONArray().put("ACTION_CLICK"))))

    private fun node(text: String, resource: String, clickable: Boolean, editable: Boolean = false): JSONObject = JSONObject()
        .put("id", resource)
        .put("path", "0/1")
        .put("text", text)
        .put("contentDescription", "")
        .put("resourceId", "com.example:id/$resource")
        .put("role", if (editable) "textbox" else if (clickable) "button" else "text")
        .put("class", if (editable) "android.widget.EditText" else "android.widget.TextView")
        .put("clickable", clickable)
        .put("longClickable", false)
        .put("editable", editable)
        .put("scrollable", false)
        .put("visibleToUser", true)
        .put("enabled", true)
        .put("depth", 2)
        .put("bounds", JSONObject().put("left", 0).put("top", 80).put("right", 500).put("bottom", 140))
        .put("actions", JSONArray())

    private fun screen(identity: String, title: String, confidence: Double) = LearnedScreen(
        id = identity,
        packageName = "com.shop",
        identity = identity,
        title = title,
        purpose = "Shows $title",
        recognition = ScreenRecognition(identity, "struct-$identity", listOf("text:${title.lowercase()}"), "Activity", listOf(title)),
        knowledgeState = KnowledgeState.VERIFIED,
        confidence = confidence,
    )

    private fun action(screenId: String, name: String, label: String, resource: String, confidence: Double) = LearnedAction(
        packageName = "com.shop",
        screenId = screenId,
        semanticName = name,
        label = label,
        androidActions = listOf("ACTION_CLICK"),
        selectorJson = JSONObject().put("resourceId", "com.shop:id/$resource").put("clickable", true).toString(),
        risk = ActionRisk.SAFE,
        knowledgeState = KnowledgeState.VERIFIED,
        confidence = confidence,
    )
}
