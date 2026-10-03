package com.cyclone.mobile.gateway

import com.cyclone.mobile.automation.AutomationStore
import com.cyclone.mobile.automation.skill.SkillCompiler
import com.cyclone.mobile.automation.skill.SkillDraftSink
import com.cyclone.mobile.automation.skill.SkillStatusMarker
import com.cyclone.mobile.policy.GatePolicy
import com.cyclone.mobile.policy.InMemoryPolicyGovernor
import com.cyclone.mobile.policy.PolicyClock
import com.cyclone.mobile.policy.PolicyPrincipal
import com.cyclone.mobile.policy.PrincipalKind
import com.cyclone.mobile.policy.PrincipalRef
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SkillGatewayOpsTest {
    private val agent = PolicyPrincipal(PrincipalRef("cyclone.agent", PrincipalKind.AI_AGENT))

    @Test
    fun compileWritesDisabledDraftForTwoVerifiedStepsWithAfterState() {
        val store = AutomationStore.inMemory()
        val ops = ops(store)
        val result = ops.compile(compileArgs(verified = true, includeAfterState = true))

        assertTrue(result.optBoolean("ok"))
        assertTrue(result.optBoolean("written"))
        assertEquals("draft", result.getString("status"))
        assertFalse(result.optBoolean("enabled"))
        assertEquals("AutomationStore", result.getString("storeClass"))
        assertEquals(SkillCompiler.COMPILE_FUNCTION, result.getString("compiler"))
        val skill = result.getJSONObject("skill")
        assertEquals(store.listAutomations().single().id, skill.getString("id"))
        assertEquals("AutomationStore", skill.getString("storeClass"))
        assertEquals("draft", skill.getString("status"))
        assertFalse(skill.optBoolean("enabled"))

        val saved = store.listAutomations().single()
        assertFalse(saved.enabled)
        assertTrue(saved.description.contains(SkillCompiler.DESCRIPTION_MARKER))
        assertTrue(saved.description.contains("status=draft"))
        assertEquals(2, saved.steps.size)
        assertEquals("AutomationStore", store.javaClass.simpleName)
    }

    @Test
    fun unverifiedStepsDoNotWrite() {
        val store = AutomationStore.inMemory()
        val result = ops(store).compile(compileArgs(verified = false, includeAfterState = true))

        assertFalse(result.optBoolean("ok"))
        assertFalse(result.optBoolean("written"))
        assertEquals("UNVERIFIED_STEPS", result.getString("errorClass"))
        assertTrue(store.listAutomations().isEmpty())
        assertTrue(result.isNull("skill") || result.optJSONObject("skill") == null)
    }

    @Test
    fun secretParamKeysAreStripped() {
        val store = AutomationStore.inMemory()
        val args = compileArgs(verified = true, includeAfterState = true)
        args.put(
            "params",
            JSONObject()
                .put("account", "work")
                .put("password", "hunter2")
                .put("otp", "123456")
                .put("api_key", "sk-secret"),
        )
        val steps = args.getJSONArray("steps")
        steps.getJSONObject(0).put("params", JSONObject().put("password", "hunter2").put("ssid", "HomeNet"))

        val result = ops(store).compile(args)
        assertTrue(result.optBoolean("ok"))
        val saved = store.listAutomations().single()
        val blob = JSONObject()
            .put("skill", result.optJSONObject("skill") ?: JSONObject())
            .put("steps", JSONArray().also { array -> saved.steps.forEach { step -> array.put(JSONObject(step.parameters)) } })
            .toString()
        assertFalse(blob.contains("hunter2"))
        assertFalse(blob.contains("123456"))
        assertFalse(blob.contains("sk-secret"))
        assertFalse(saved.steps.any { it.parameters.containsKey("password") })
        assertFalse(saved.steps.any { it.parameters.values.any { value -> value.contains("hunter2") } })
    }

    @Test
    fun draftRunWithoutDryRunIsDeniedAndDryRunDoesNotMutate() {
        val store = AutomationStore.inMemory()
        var executed = 0
        val executor = SkillStepExecutor { _, _, _ ->
            executed += 1
            SkillStepOutcome(ok = true, pageChanged = true)
        }
        val ops = ops(store, executor)
        val compiled = ops.compile(compileArgs(verified = true, includeAfterState = true))
        val skillId = compiled.getJSONObject("skill").getString("id")
        val versionBefore = store.getAutomation(skillId)!!.version
        val descriptionBefore = store.getAutomation(skillId)!!.description

        val live = ops.run(JSONObject().put("skillId", skillId).put("dryRun", false).put("source", "PC_CODEX"))
        assertFalse(live.optBoolean("ok"))
        assertEquals("DRAFT_RUN_DENIED", live.getString("errorClass"))
        assertTrue(live.optBoolean("denied"))
        assertFalse(live.optBoolean("mutated"))
        assertEquals(0, executed)

        val dry = ops.run(JSONObject().put("skillId", skillId).put("dryRun", true).put("source", "PC_CODEX"))
        assertTrue(dry.optBoolean("ok"))
        assertTrue(dry.optBoolean("dryRun"))
        assertFalse(dry.optBoolean("mutated"))
        assertEquals(0, executed)
        assertEquals(versionBefore, store.getAutomation(skillId)!!.version)
        assertEquals(descriptionBefore, store.getAutomation(skillId)!!.description)
        assertFalse(store.getAutomation(skillId)!!.enabled)
        val steps = dry.getJSONArray("steps")
        assertEquals(2, steps.length())
        assertTrue(steps.getJSONObject(0).optBoolean("ok"))
        assertTrue(steps.getJSONObject(0).optBoolean("dryRun"))
        assertTrue(steps.getJSONObject(0).isNull("errorClass") || steps.getJSONObject(0).optString("errorClass").isBlank())
    }

    @Test
    fun verifiedRunReturnsPerStepActEnvelopes() {
        val store = AutomationStore.inMemory()
        val skillId = seedVerified(store)
        var executed = 0
        val executor = SkillStepExecutor { commandId, tool, _ ->
            executed += 1
            val afterKey = "settings.page-$executed"
            SkillStepOutcome(
                ok = true,
                pageChanged = true,
                generation = "obs-$commandId",
                before = JSONObject().put("pageKey", "settings.home").put("package", "com.android.settings"),
                after = JSONObject()
                    .put("pageKey", afterKey)
                    .put("package", "com.android.settings")
                    .put("pageCard", JSONObject().put("pageKey", afterKey).put("package", "com.android.settings")),
                delta = JSONObject().put("pageKeyChanged", true),
            )
        }
        val result = ops(store, executor).run(
            JSONObject().put("skillId", skillId).put("dryRun", false).put("source", "PC_CODEX"),
        )

        assertTrue(result.optBoolean("ok"))
        assertEquals("verified", result.getString("status"))
        assertEquals(2, executed)
        val steps = result.getJSONArray("steps")
        assertEquals(2, steps.length())
        for (index in 0 until steps.length()) {
            val envelope = steps.getJSONObject(index)
            assertTrue(envelope.optBoolean("ok"))
            assertTrue(envelope.optBoolean("pageChanged"))
            assertNotNull(envelope.optJSONObject("before"))
            val after = envelope.getJSONObject("after")
            assertNotNull(after.getJSONObject("pageCard"))
            assertNotNull(envelope.optJSONObject("delta"))
            assertTrue(envelope.isNull("errorClass") || envelope.optString("errorClass").isBlank())
            assertTrue(envelope.optString("generation").isNotBlank())
            assertEquals(index, envelope.getInt("stepIndex"))
        }
    }

    @Test
    fun matchReturnsSkipModelOnlyForVerifiedGoalAndPageKey() {
        val store = AutomationStore.inMemory()
        val ops = ops(store)
        ops.compile(compileArgs(verified = true, includeAfterState = true, goal = "Open battery settings", pageKey = "settings.home"))

        val draftMatch = ops.match(JSONObject().put("goal", "Open battery settings").put("pageKey", "settings.home"))
        assertTrue(draftMatch.optBoolean("ok"))
        assertFalse(draftMatch.optBoolean("matched"))
        assertFalse(draftMatch.optBoolean("skipModel"))
        assertTrue(draftMatch.isNull("skill"))

        val verifiedId = seedVerified(store, compileArgs(verified = true, includeAfterState = true, goal = "Open battery settings", pageKey = "settings.home"))
        val verified = ops.match(JSONObject().put("goal", "Open battery settings").put("pageKey", "settings.home"))
        assertTrue(verified.optBoolean("matched"))
        assertTrue(verified.optBoolean("skipModel"))
        val skill = verified.getJSONObject("skill")
        assertEquals(verifiedId, skill.getString("id"))
        assertEquals("verified", skill.getString("status"))
        assertTrue(skill.optBoolean("skipModel"))
        assertEquals("settings.home", skill.getString("pageKey"))

        val miss = ops.match(JSONObject().put("goal", "Unrelated goal").put("pageKey", "settings.home"))
        assertFalse(miss.optBoolean("matched"))
        assertFalse(miss.optBoolean("skipModel"))
        assertTrue(miss.isNull("skill"))
    }

    @Test
    fun gateDenyOnPaySendDeleteGrantIgnoresPcAutoApprove() {
        val store = AutomationStore.inMemory()
        val ops = ops(store)
        val cases = listOf(
            "Pay now" to "pay",
            "Send message" to "send",
            "Delete conversation" to "delete",
            "Grant access" to "grant",
        )
        val verifiedRequest = ops(AutomationStore.inMemory()).compile(
            compileArgs(verified = true, includeAfterState = true).put("status", "verified"),
        )
        assertFalse(verifiedRequest.optBoolean("ok"))
        assertEquals("PC_VERIFIED_DENIED", verifiedRequest.getString("errorClass"))
        assertTrue(verifiedRequest.isNull("skill") || verifiedRequest.optJSONObject("skill") == null)

        cases.forEach { (label, expectedClass) ->
            val isolated = AutomationStore.inMemory()
            val result = ops(isolated).compile(
                compileArgs(verified = true, includeAfterState = true, goal = "Sensitive $label").also { args ->
                    args.put("autoApprove", true)
                    args.put("status", "verified")
                    args.put(
                        "pcEnvelope",
                        JSONObject().put("autoApprove", true).put("requestedCapsuleStatus", "verified"),
                    )
                    args.getJSONArray("steps").put(gatedStep(label))
                },
            )
            assertFalse("expected GATE deny for $label", result.optBoolean("ok"))
            assertEquals("GATE_DENIED", result.getString("errorClass"))
            assertEquals(expectedClass, result.getString("gateClass"))
            assertTrue(result.optBoolean("ignoredPcAutoApprove"))
            assertFalse(result.optBoolean("mutationAllowed"))
            assertTrue(isolated.listAutomations().isEmpty())
        }

        var executed = 0
        val executor = SkillStepExecutor { _, _, _ ->
            executed += 1
            SkillStepOutcome(ok = true, pageChanged = false)
        }
        val verifiedId = seedVerified(store)
        val existing = store.getAutomation(verifiedId)!!
        val payStep = existing.steps.last().copy(name = "Then Pay now")
        store.saveAutomation(existing.copy(steps = existing.steps.dropLast(1) + payStep))
        val run = ops(store, executor).run(
            JSONObject()
                .put("skillId", verifiedId)
                .put("dryRun", false)
                .put("autoApprove", true)
                .put("source", "PC_CODEX"),
        )
        assertFalse(run.optBoolean("ok"))
        assertEquals("GATE_DENIED", run.getString("errorClass"))
        assertEquals("pay", run.getString("gateClass"))
        assertTrue(run.optBoolean("ignoredPcAutoApprove"))
        assertFalse(run.optBoolean("mutated"))
        assertEquals(0, executed)
    }

    @Test
    fun skillOpsAreKnownAuthenticatedGatewayProtocolOperations() {
        GatewayProtocol.requireKnownOperation("skill.compile", "req-compile")
        GatewayProtocol.requireKnownOperation("skill.run", "req-run")
        GatewayProtocol.requireKnownOperation("skill.match", "req-match")
        assertTrue("skill.compile" in GatewayProtocol.operations)
        assertTrue("skill.run" in GatewayProtocol.operations)
        assertTrue("skill.match" in GatewayProtocol.operations)
        assertFalse("skill.compile" in GatewayProtocol.unauthenticatedOperations)
        assertFalse("skill.run" in GatewayProtocol.unauthenticatedOperations)
        assertFalse("skill.match" in GatewayProtocol.unauthenticatedOperations)
        try {
            GatewayProtocol.requireKnownOperation("skill.promote", "req-leftover")
            fail("Unknown leftover skill.promote should stay rejected")
        } catch (error: GatewayProtocolException) {
            assertEquals("UNKNOWN_OPERATION", error.code)
            assertEquals("req-leftover", error.requestId)
        }
        try {
            GatewayProtocol.parse("""{"id":"auth-1","op":"skill.compile","args":{}}""")
            fail("skill.compile must require auth")
        } catch (error: GatewayProtocolException) {
            assertEquals("AUTH_REQUIRED", error.code)
        }
    }

    private fun seedVerified(
        store: AutomationStore,
        args: JSONObject = compileArgs(verified = true, includeAfterState = true),
    ): String {
        val compiled = ops(store).compile(args)
        assertTrue(compiled.optBoolean("ok"))
        val id = compiled.getJSONObject("skill").getString("id")
        val existing = store.getAutomation(id)!!
        store.saveAutomation(
            existing.copy(description = existing.description.replace("status=draft", "status=verified")),
        )
        val saved = store.getAutomation(id)!!
        assertFalse(saved.enabled)
        assertTrue(SkillStatusMarker.isVerified(saved))
        return id
    }

    private fun ops(store: AutomationStore, executor: SkillStepExecutor? = null): SkillGatewayOps {
        val gate = GatePolicy(InMemoryPolicyGovernor(PolicyClock { 100L }))
        return SkillGatewayOps(
            store = store,
            sink = SkillDraftSink(SkillCompiler(store), gate, agent),
            gate = gate,
            principal = agent,
            stepExecutor = executor,
            nowEpochMillis = { 1_000L },
        )
    }

    private fun compileArgs(
        verified: Boolean,
        includeAfterState: Boolean,
        goal: String = "Open battery settings",
        pageKey: String = "settings.home",
    ): JSONObject = JSONObject()
        .put("goal", goal)
        .put("app", "com.android.settings")
        .put("pageKey", pageKey)
        .put("status", "draft")
        .put("enabled", false)
        .put("storeClass", "AutomationStore")
        .put("compiler", SkillCompiler.COMPILE_FUNCTION)
        .put("source", "PC_CODEX")
        .put("params", JSONObject().put("account", "work"))
        .put(
            "steps",
            JSONArray()
                .put(step("When on Settings home", "Then open Apps", "Check Apps page", "phone.click", "Apps", "settings.home", "settings.apps", verified, includeAfterState))
                .put(step("When on Apps", "Then open Battery", "Check Battery page", "phone.click", "Battery", "settings.apps", "settings.battery", verified, includeAfterState)),
        )

    private fun step(
        whenClause: String,
        thenClause: String,
        checkClause: String,
        action: String,
        selectorText: String,
        beforePageKey: String,
        afterPageKey: String,
        verified: Boolean,
        includeAfterState: Boolean,
    ): JSONObject = JSONObject()
        .put("whenClause", whenClause)
        .put("thenClause", thenClause)
        .put("checkClause", checkClause)
        .put("action", action)
        .put("tool", action)
        .put("verified", verified)
        .put("beforePageKey", beforePageKey)
        .put("afterPageKey", if (includeAfterState) afterPageKey else "")
        .put("selectors", JSONArray().put(JSONObject().put("kind", "text").put("value", selectorText).put("confidence", 0.9)))
        .put("when", JSONObject().put("pageKey", beforePageKey).put("package", "com.android.settings"))
        .put("then", JSONObject().put("tool", action).put("selector", JSONObject().put("text", selectorText)))
        .put("check", JSONObject().put("pageKey", if (includeAfterState) afterPageKey else ""))

    private fun gatedStep(label: String): JSONObject = JSONObject()
        .put("whenClause", "When on review")
        .put("thenClause", "Then $label")
        .put("checkClause", "Check done")
        .put("action", "phone.click")
        .put("tool", "phone.click")
        .put("verified", true)
        .put("beforePageKey", "shop.review")
        .put("afterPageKey", "shop.done")
        .put("selectors", JSONArray().put(JSONObject().put("kind", "text").put("value", label).put("confidence", 0.99)))
        .put("name", "Then $label")
}
