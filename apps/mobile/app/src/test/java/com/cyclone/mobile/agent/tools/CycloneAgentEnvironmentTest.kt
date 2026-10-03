package com.cyclone.mobile.agent.tools

import com.cyclone.mobile.PhoneToolResult
import com.cyclone.mobile.agent.contract.*
import com.cyclone.mobile.ai.CycloneAiAccessPolicy
import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.gateway.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class CycloneAgentEnvironmentTest {
    @Test fun visualBridgeUsesOneCaptureAndSameGenerationForAllProjections() {
        val initial = observation("visual", "home", "fp").copy(generation = 83)
        initial.payload.put("screenshot", JSONObject().put("available", true).put("pngBase64", "fixture"))
        val runtime = FakeRuntime(initial, null)
        val bridge = com.cyclone.mobile.agent.integration.CyclonePcParityBridge(CycloneAgentEnvironment(runtime))
        val captured = bridge.observeWithImage("login")!!
        val card = captured.page!!
        assertEquals(83L, card.generation)
        assertEquals(83L, card.legacyPage!!.observation!!.generation)
        assertEquals(83L, ObservationProjections.snapshot(card).getLong("generation"))
        val prompt = bridge.promptContext("login")
        assertEquals(83L, prompt.getJSONObject("pageCard").getLong("generation"))
        assertFalse(prompt.toString().contains("pngBase64"))
        assertEquals("fixture", captured.image!!.getString("pngBase64"))
        assertEquals(1, runtime.captureCalls)
    }

    @Test fun captureRaceClearsCurrentPageAndReportsBoundedRecoveryWithoutDispatch() {
        val runtime = FakeRuntime(observation("initial", "home", "fp"), null)
        val bridge = com.cyclone.mobile.agent.integration.CyclonePcParityBridge(CycloneAgentEnvironment(runtime))
        assertNotNull(bridge.observe("login"))
        runtime.captureError = com.cyclone.mobile.agent.CaptureChanged()
        assertNull(bridge.observeWithImage("login"))
        assertNull(bridge.currentPage())
        assertEquals(com.cyclone.mobile.agent.ObservationState.CAPTURE_CHANGED, bridge.observationHealth.state)
        assertFalse(bridge.observationHealth.terminal)
        assertEquals(0, runtime.executionCalls)
    }
    @Test fun authoritativeBridgeProjectsTheSourceGenerationWithOneCapture() {
        val initial = observation("shared", "home", "fp").copy(generation = 82)
        val runtime = FakeRuntime(initial, null)
        val bridge = com.cyclone.mobile.agent.integration.CyclonePcParityBridge(CycloneAgentEnvironment(runtime))
        val card = bridge.observe("login")!!
        assertEquals(82L, card.generation)
        assertEquals(82L, card.legacyPage!!.observation!!.generation)
        assertEquals("authoritative", card.pageEvidence.getString("projectionMode"))
        repeat(5) {
            val prompt = bridge.promptContext("login").getJSONObject("pageCard")
            assertEquals(82L, prompt.getJSONObject("observation").getLong("generation"))
        }
        assertTrue(runtime.captureQueue.isEmpty())
        assertEquals(1, runtime.captureCalls)
    }
    @Test fun workspaceFailuresHavePrecisePermanentHealthWithoutRawMessages() {
        val cases = mapOf("BACKEND_DISCONNECTED" to com.cyclone.mobile.agent.ObservationState.DISCONNECTED,
            "STALE_SESSION" to com.cyclone.mobile.agent.ObservationState.SCOPE_MISMATCH,
            "FOREGROUND_REQUIRED" to com.cyclone.mobile.agent.ObservationState.TARGET_NOT_VISIBLE,
            "ACCESSIBILITY_NOT_CONNECTED" to com.cyclone.mobile.agent.ObservationState.PERMISSION_REQUIRED)
        cases.forEach { (code, expected) ->
            val runtime = FakeRuntime(observation("one", "home", "fp"), null).apply {
                captureError = IllegalStateException("$code: private screen text")
            }
            val failure = CycloneAgentEnvironment(runtime).observe().failure!!
            assertFalse(failure.toString().contains("private screen text"))
            val health = com.cyclone.mobile.agent.ObservationHealth.failure(failure, "task", 7, 1, null, 0)
            assertEquals(expected, health.state)
            assertTrue(health.terminal)
            assertEquals(0, runtime.executionCalls)
        }
    }
    @Test fun movedTargetUsesFreshIdAndNeverOldCoordinates() {
        val before = observation("old", "home", "fp", "Reject cookies")
        val after = observation("fresh", "home", "fp2", "Reject cookies")
        before.elements.values.single().evidence.put("bounds", JSONObject().put("left", 0).put("top", 0).put("right", 40).put("bottom", 20))
        after.elements.values.single().evidence.put("bounds", JSONObject().put("left", 0).put("top", 80).put("right", 40).put("bottom", 100))
        val report = CurrentTargetRevalidation.resolve(before, after, elementId(before))
        assertEquals(TargetDrift.MOVED_SAME_IDENTITY, report.status)
        assertEquals(elementId(after), report.elementId)
        val runtime = FakeRuntime(before, null).apply { captureQueue.addLast(after) }
        val env = CycloneAgentEnvironment(runtime, revalidateTargets = true)
        env.observe("login")
        env.act("phone.click", JSONObject().put("elementId", elementId(before)), "reject cookies")
        assertEquals(1, runtime.executionCalls)
        assertEquals(after.id, runtime.lastParams!!.getString("observationId"))
        assertEquals(elementId(after), runtime.lastParams!!.getString("elementId"))
    }

    @Test fun rawMirrorAndNestedWebViewWrapperDoNotCreateFalseAmbiguity() {
        val before = observation("old", "home", "fp", "Continue")
        val after = observation("fresh", "home", "fp2", "Continue")
        val rect = JSONObject().put("left", 0).put("top", 0).put("right", 120).put("bottom", 48)

        val oldTarget = before.elements.values.single()
        oldTarget.evidence
            .put("bounds", JSONObject(rect.toString()))
            .put("clickable", true)
            .put("rawNodeId", "old-child")
            .put("rawPath", "/0/2/1")

        val target = after.elements.values.single()
        target.evidence
            .put("bounds", JSONObject(rect.toString()))
            .put("clickable", true)
            .put("rawNodeId", "fresh-child")
            .put("rawPath", "/0/2/1")

        val rawMirrorEvidence = JSONObject(target.evidence.toString())
            .put("id", "fresh-child")
            .put("path", "/0/2/1")
            .put("source", "raw_accessibility")
        val rawMirror = GatewayElement(
            id = "raw:fresh:fresh-child",
            source = "raw_accessibility",
            label = target.label,
            semanticName = target.semanticName,
            role = target.role,
            evidence = rawMirrorEvidence,
        )

        val wrapperEvidence = JSONObject(target.evidence.toString())
            .put("elementId", "semantic:fresh:wrapper")
            .put("source", "semantic_supplement")
            .put("rawNodeId", "fresh-parent")
            .put("rawPath", "/0/2")
        val wrapper = GatewayElement(
            id = "semantic:fresh:wrapper",
            source = "semantic_supplement",
            label = target.label,
            semanticName = target.semanticName,
            role = target.role,
            evidence = wrapperEvidence,
        )

        val represented = after.copy(
            elements = after.elements + mapOf(rawMirror.id to rawMirror, wrapper.id to wrapper),
        )
        val report = CurrentTargetRevalidation.resolve(before, represented, elementId(before))
        assertEquals(TargetDrift.MATCHED, report.status)
        assertEquals(elementId(after), report.elementId)
    }

    @Test fun overlappingSiblingWithSameLabelStillFailsClosedAsAmbiguous() {
        val before = observation("old", "home", "fp", "Continue")
        val after = observation("fresh", "home", "fp2", "Continue")
        val rect = JSONObject().put("left", 0).put("top", 0).put("right", 120).put("bottom", 48)

        before.elements.values.single().evidence
            .put("bounds", JSONObject(rect.toString()))
            .put("clickable", true)
            .put("rawNodeId", "old-target")
            .put("rawPath", "/0/2")

        val target = after.elements.values.single()
        target.evidence
            .put("bounds", JSONObject(rect.toString()))
            .put("clickable", true)
            .put("rawNodeId", "fresh-target")
            .put("rawPath", "/0/2")

        val siblingEvidence = JSONObject(target.evidence.toString())
            .put("elementId", "semantic:fresh:sibling")
            .put("source", "semantic_supplement")
            .put("rawNodeId", "fresh-sibling")
            .put("rawPath", "/0/3")
        val sibling = GatewayElement(
            id = "semantic:fresh:sibling",
            source = "semantic_supplement",
            label = target.label,
            semanticName = target.semanticName,
            role = target.role,
            evidence = siblingEvidence,
        )

        val ambiguous = after.copy(elements = after.elements + (sibling.id to sibling))
        assertEquals(
            TargetDrift.AMBIGUOUS,
            CurrentTargetRevalidation.resolve(before, ambiguous, elementId(before)).status,
        )
    }

    @Test fun replacementAmbiguityOcclusionAndScopeDriftNeverDispatch() {
        val before = observation("old", "home", "fp", "Reject cookies")
        before.elements.values.single().evidence.put("bounds", JSONObject().put("left", 0).put("top", 0).put("right", 40).put("bottom", 20))
        val same = observation("fresh", "home", "fp2", "Reject cookies")
        same.elements.values.single().evidence.put("bounds", JSONObject().put("left", 0).put("top", 0).put("right", 40).put("bottom", 20))
        val duplicate = same.elements.values.single().copy(id = "semantic:fresh:duplicate")
        val cases = listOf(
            observation("accept", "home", "fp2", "Accept cookies") to TargetDrift.DISAPPEARED,
            same.copy(elements = same.elements + (duplicate.id to duplicate)) to TargetDrift.AMBIGUOUS,
            same.copy(execution = same.execution.copy(displayId = 7)) to TargetDrift.SCOPE_MISMATCH,
        )
        cases.forEach { (after, status) ->
            assertEquals(status, CurrentTargetRevalidation.resolve(before, after, elementId(before)).status)
            val runtime = FakeRuntime(before, null).apply { captureQueue.addLast(after) }
            val env = CycloneAgentEnvironment(runtime, revalidateTargets = true)
            env.observe("login")
            assertFalse(env.act("phone.click", JSONObject().put("elementId", elementId(before)), "reject cookies").executorInvoked)
            assertEquals(0, runtime.executionCalls)
        }
        same.elements.values.single().evidence.put("visibleToUser", false)
        assertEquals(TargetDrift.OCCLUDED, CurrentTargetRevalidation.resolve(before, same, elementId(before)).status)
    }
    @Test fun oneCaptureFeedsLegacyAndExecutableViewsDuringBannerArrival() {
        val initial = observation("first", "home", "fp1")
        val banner = observation("second", "consent", "fp2")
        val runtime = FakeRuntime(initial, null).apply { captureQueue.addLast(banner) }
        val bridge = com.cyclone.mobile.agent.integration.CyclonePcParityBridge(CycloneAgentEnvironment(runtime, projectionMode = ObservationProjectionMode.SHADOW))
        val first = bridge.observe("login")!!
        assertTrue(first.pageEvidence.getJSONObject("projectionShadow").getBoolean("matches"))
        assertSame(initial.page, first.legacyPage)
        assertEquals(first.pageKey, first.legacyPage!!.pageKey)
        assertEquals(1, runtime.captureQueue.size)
        assertTrue(first.controls.all { it.observationId == first.observationId })
        val second = bridge.observe("login")!!
        assertTrue(second.pageEvidence.getJSONObject("projectionShadow").getBoolean("matches"))
        assertSame(banner.page, second.legacyPage)
        assertEquals(second.pageKey, second.legacyPage!!.pageKey)
        assertTrue(runtime.captureQueue.isEmpty())
    }

    @Test fun screenshotSkewRotationAndChangedSurfaceFailClosed() {
        val env = CycloneAgentEnvironment(FakeRuntime(observation("first", "home", "fp1"), null))
        val card = env.observe("login").page!!.let { it.copy(pageEvidence = JSONObject()
            .put("captureWidth", 100).put("captureHeight", 200)) }
        val shot = JSONObject().put("sessionId", card.sessionId).put("displayId", card.displayId)
            .put("width", 100).put("height", 200)
        val check = com.cyclone.mobile.agent.ObservationCoherence
        assertTrue(check.accepts(card, card.copy(observationId = "new"), shot, 100))
        assertFalse(check.accepts(card, card, shot, 1501))
        assertFalse(check.accepts(card, card.copy(packageName = "other"), shot, 100))
        assertFalse(check.accepts(card, card.copy(displayId = 7), shot, 100))
        assertFalse(check.accepts(card, card.copy(contentKey = "banner"), shot, 100))
        assertFalse(check.accepts(card, card.copy(pageEvidence = JSONObject().put("captureWidth", 200).put("captureHeight", 100)), shot, 100))
    }
    @Test fun cookieRejectionSurvivesCompactControlLimit() {
        val before = observationWithHiddenRawTarget()
        val controls = before.payload.getJSONArray("semanticControls")
        controls.put(JSONObject().put("elementId", "semantic:${before.id}:cookies")
            .put("observationId", before.id).put("label", "Reject Optional Cookies")
            .put("role", "button").put("clickable", true))
        val env = CycloneAgentEnvironment(FakeRuntime(before, null))
        val page = env.observe("open reddit.com and login for me").page!!
        assertTrue(page.controls.size <= 36)
        assertNotNull(com.cyclone.mobile.ai.CookieInterruptionPolicy().next(page, "open reddit.com and login for me"))
    }

    @Test fun executorSuccessWithoutSemanticChangeRemainsUnverifiedAndUnlearned() {
        val before = observation("obs-1", "home", "fp-1")
        val runtime = FakeRuntime(before, observation("obs-2", "home", "fp-1"))
        val env = CycloneAgentEnvironment(runtime)
        env.observe("Continue")
        val result = env.act("phone.click", JSONObject().put("elementId", elementId(before)), "Continue")
        assertTrue(result.androidExecutionOk)
        assertFalse(result.verification.passed)
        assertEquals(AgentVerificationStatus.OBSERVED, result.verification.status)
        assertEquals(AgentFailureClass.VERIFICATION_FAILED, result.errorClass)
        assertEquals(0, runtime.learningCalls)
    }

    @Test fun samePageSelectedCheckedFocusedAndEditableTextChangesAreVerifiedProgress() {
        val before = semantic(textState = "t1")
        listOf(
            semantic(selected = true, textState = "t1") to "SELECTED_STATE_CHANGED",
            semantic(checked = true, textState = "t1") to "CHECKED_STATE_CHANGED",
            semantic(focused = true, textState = "t1") to "FOCUSED_STATE_CHANGED",
            semantic(textState = "t2") to "EDITABLE_TEXT_CHANGED",
        ).forEach { (after, basis) ->
            val result = AgentSemanticVerifier.verify("phone.type", true, false, false, "", "", before, after)
            assertTrue(result.passed)
            assertEquals(basis, result.basis)
        }
    }

    @Test fun pageTransitionRequiresSemanticSurfaceChange() {
        val churnOnly = AgentSemanticVerifier.verify(
            "phone.click", true, false, false, "", "",
            semantic(pageKey = "home", fingerprint = "fp-1"),
            semantic(pageKey = "settings", fingerprint = "fp-2"),
        )
        assertFalse(churnOnly.passed)
        assertEquals("NO_SEMANTIC_PROGRESS", churnOnly.basis)

        val semanticTransition = AgentSemanticVerifier.verify(
            "phone.click", true, false, false, "", "",
            semantic(pageKey = "home", fingerprint = "fp-1", label = "Continue"),
            semantic(pageKey = "settings", fingerprint = "fp-2", label = "Settings"),
        )
        assertTrue(semanticTransition.passed)
        assertEquals("SEMANTIC_PAGE_CHANGED", semanticTransition.basis)
    }

    @Test fun elementIndexFromCurrentObservationResolvesToElementId() {
        val before = observation("obs-idx", "home", "fp-idx")
        val evidence = before.elements.values.first().evidence.put("elementIndex", 1).put("element_index", 1)
        val runtime = FakeRuntime(before, observation("obs-idx-after", "settings", "fp-idx-2", "Settings"))
        val env = CycloneAgentEnvironment(runtime)
        env.observe("Continue")
        val result = env.act("phone.click", JSONObject().put("elementIndex", 1), "Continue")
        assertTrue(result.androidExecutionOk)
        assertEquals(1, runtime.executionCalls)
        assertEquals(1, evidence.optInt("elementIndex"))
    }

    @Test fun staleElementIdExpiresImmediatelyAfterMutation() {
        val before = observation("obs-1", "home", "fp-1")
        val runtime = FakeRuntime(before, observation("obs-2", "settings", "fp-2"))
        val env = CycloneAgentEnvironment(runtime)
        env.observe("Continue")
        val stale = elementId(before)
        env.act("phone.click", JSONObject().put("elementId", stale), "Continue")
        val second = env.act("phone.click", JSONObject().put("elementId", stale), "Continue")
        assertEquals(AgentFailureClass.STALE_OBSERVATION, second.errorClass)
        assertTrue(second.retryable)
        assertEquals(1, runtime.executionCalls)
    }

    @Test fun freshRelocatePublishesNewUsableElementId() {
        val first = observation("obs-1", "home", "fp-1")
        val runtime = FakeRuntime(first, observation("obs-2", "settings", "fp-2"))
        val env = CycloneAgentEnvironment(runtime)
        env.observe("Continue")
        env.act("phone.click", JSONObject().put("elementId", elementId(first)), "Continue")
        val fresh = observation("obs-3", "settings", "fp-2", "Open details")
        runtime.captureQueue.addLast(fresh)
        runtime.afterObservation = observation("obs-4", "details", "fp-3", "Done")
        val candidate = env.locate("Open details").candidates.first()
        val result = env.act("phone.click", JSONObject().put("elementId", candidate.elementId), "Open details")
        assertEquals(fresh.id, candidate.observationId)
        assertTrue(result.androidExecutionOk)
        assertEquals(AgentFailureClass.NONE, result.errorClass)
        assertEquals(2, runtime.executionCalls)
    }

    @Test fun semanticSearchFindsCandidateAbsentFromCompactControls() {
        val source = observationWithHiddenRawTarget()
        val env = CycloneAgentEnvironment(FakeRuntime(source, source))
        env.observe("")
        val result = env.search("Deep hidden target", "Find target")
        assertTrue(result.candidates.any { it.label == "Deep hidden target" && it.source == "raw_accessibility" })
    }

    @Test fun screenshotDoesNotChangeActionAuthorityOrScope() {
        val before = observation("obs-1", "home", "fp-1")
        val runtime = FakeRuntime(before, observation("obs-2", "settings", "fp-2"))
        val env = CycloneAgentEnvironment(runtime)
        env.observe("Continue")
        val shot = env.screenshot("Visual evidence")
        val result = env.act("phone.click", JSONObject().put("elementId", elementId(before)), "Continue")
        assertNotNull(shot.filePath)
        assertEquals(before.id, shot.observationId)
        assertTrue(result.androidExecutionOk)
        assertEquals(1, runtime.executionCalls)
    }

    @Test fun mindMissionsMayTypeIntoOrdinaryFieldsButTheStepAgentMayNot() {
        fun field(id: String, label: String, password: Boolean = false): GatewayObservation {
            val obs = observation(id, "chat", "fp-$id", label)
            obs.elements.values.single().evidence.put("editable", true).put("enabled", true).put("password", password)
            return obs
        }
        fun typed(ownerMission: Boolean, obs: GatewayObservation): JSONObject {
            val runtime = FakeRuntime(obs, obs)
            val env = CycloneAgentEnvironment(runtime, userTaskGoal = "ask ChatGPT for better examples", ownerMission = ownerMission)
            env.observe("type")
            env.act("phone.type", JSONObject().put("elementId", elementId(obs)).put("value", "Find better examples")
                .put("user_authorized", true), "type")
            return runtime.lastParams!!
        }
        assertTrue(typed(true, field("m1", "Reply to ChatGPT")).optBoolean("user_authorized"))
        assertFalse("the step agent keeps its narrow rule", typed(false, field("s1", "Reply to ChatGPT")).optBoolean("user_authorized"))
        assertFalse(typed(true, field("m2", "Password", password = true)).optBoolean("user_authorized"))
        assertFalse(typed(true, field("m3", "Verification code")).optBoolean("user_authorized"))
    }

    @Test fun aMindMissionTypesIntoTheFocusedTextBoxWithoutARef() {
        val obs = observation("f1", "chat", "fp-f1", "Reply to ChatGPT")
        obs.elements.values.single().evidence.put("editable", true).put("enabled", true).put("focused", true)
        val runtime = FakeRuntime(obs, obs)
        val env = CycloneAgentEnvironment(runtime, userTaskGoal = "ask ChatGPT", ownerMission = true)
        env.observe("type")
        env.act("phone.type", JSONObject().put("focused", true).put("value", "Find better examples"), "type")
        val sent = runtime.lastParams!!
        assertTrue(sent.getBoolean("focused"))
        assertTrue(sent.optBoolean("user_authorized"))
        assertFalse(sent.has("elementId"))

        val unfocused = observation("f2", "chat", "fp-f2", "Reply to ChatGPT")
        val none = FakeRuntime(unfocused, unfocused)
        val env2 = CycloneAgentEnvironment(none, userTaskGoal = "ask ChatGPT", ownerMission = true)
        env2.observe("type")
        val refused = env2.act("phone.type", JSONObject().put("focused", true).put("value", "x"), "type")
        assertFalse(refused.executorInvoked)
        assertEquals(0, none.executionCalls)
        val step = CycloneAgentEnvironment(FakeRuntime(obs, obs), userTaskGoal = "ask ChatGPT")
        step.observe("type")
        assertFalse("the step agent still needs a ref", step.act("phone.type", JSONObject().put("focused", true).put("value", "x"), "type").executorInvoked)
    }

    @Test fun agentInputCannotOverrideCycloneAiPolicyDenial() {
        val params = JSONObject()
            .put("user_authorized", true)
            .put("force", true)
            .put("selector", JSONObject().put("text", "Delete account"))
        val decision = CycloneAiAccessPolicy.evaluate(CycloneAiAccessProfile.FULL, "phone.click", params)
        assertFalse(decision.allowed)
        assertEquals("LOCAL_CONFIRMATION_REQUIRED", decision.reasonCode)
    }

    @Test fun unverifiedActionNeverCallsVerifiedLearningPort() {
        val before = observation("obs-1", "home", "fp-1")
        val runtime = FakeRuntime(before, observation("obs-2", "home", "fp-1"))
        val env = CycloneAgentEnvironment(runtime)
        env.observe("Continue")
        val result = env.act("phone.click", JSONObject().put("elementId", elementId(before)), "Continue")
        assertFalse(result.learning.recorded)
        assertEquals(0, runtime.learningCalls)
    }

    @Test fun verifiedSafePageRouteEntersCanonicalLearningPort() {
        val before = observation("obs-1", "home", "fp-1", "Continue")
        val runtime = FakeRuntime(before, observation("obs-2", "settings", "fp-2", "Settings"))
        val env = CycloneAgentEnvironment(runtime)
        env.observe("Continue")
        val result = env.act("phone.click", JSONObject().put("elementId", elementId(before)), "Continue")
        assertTrue(result.verification.passed)
        assertEquals("SEMANTIC_PAGE_CHANGED", result.verification.basis)
        assertTrue(result.learning.recorded)
        assertEquals(1, runtime.learningCalls)
    }

    @Test fun unsupportedExplicitExpectationCannotPromoteExecutorSuccess() {
        val unchanged = observation("obs-expect", "home", "fp-expect")
        val result = GatewayV33ActionAdapter.verifyAfterState(
            tool = "phone.type",
            expectedPackage = "",
            goalLabel = "Continue",
            beforeObservation = unchanged,
            afterObservation = observation("obs-expect-after", "home", "fp-expect"),
            androidExecutionOk = true,
            executorAssertionFailed = false,
            explicitExpectation = true,
        )
        assertFalse(result.passed)
        assertEquals(AgentVerificationStatus.OBSERVED, result.status)
        assertEquals("NO_SEMANTIC_PROGRESS", result.basis)
    }

    @Test fun reusedObservationCannotVerifyBack() {
        val same = observation("same", "home", "fp")
        val result = GatewayV33ActionAdapter.verifyAfterState(
            tool = "phone.back", expectedPackage = "", goalLabel = "Back",
            beforeObservation = same, afterObservation = same, androidExecutionOk = true,
            executorAssertionFailed = false, explicitExpectation = false)
        assertFalse(result.passed)
        assertEquals(AgentVerificationStatus.FAILED, result.status)
        assertEquals("OBSERVATION_IDENTITY_MISMATCH", result.basis)
    }

    @Test fun pcFacingVerifierRejectsIdentityChurnAndAcceptsSemanticWitness() {
        assertFalse(GatewayV33ActionAdapter.verifiedByAfterState(
            "phone.click", "", "home", "fp-1", "pkg", "home", "fp-1",
        ))
        assertFalse(GatewayV33ActionAdapter.verifiedByAfterState(
            "phone.click", "", "home", "fp-1", "pkg", "settings", "fp-2",
        ))
        assertTrue(GatewayV33ActionAdapter.verifiedByAfterState(
            "phone.click", "", "home", "fp-1", "pkg", "settings", "fp-2",
            goalLabel = "Settings",
            afterHaystack = "Settings",
        ))
    }

    @Test fun afterObservationFailureCannotClaimSemanticSuccess() {
        val before = observation("obs-1", "home", "fp-1")
        val env = CycloneAgentEnvironment(FakeRuntime(before, null))
        env.observe("Continue")
        val result = env.act("phone.click", JSONObject().put("elementId", elementId(before)), "Continue")
        assertTrue(result.androidExecutionOk)
        assertEquals(AgentVerificationStatus.DEGRADED, result.verification.status)
        assertFalse(result.semanticSuccessClaimed)
        assertEquals(AgentFailureClass.AFTER_OBSERVATION_FAILED, result.errorClass)
    }

    @Test fun failedOpenAppStillCapturesAfterStateAndStaysRetryable() {
        val before = observation("obs-1", "launcher", "fp-1")
        val after = observation("obs-2", "launcher", "fp-2")
        val runtime = FakeRuntime(before, after).apply {
            executionOk = false
            executionError = com.cyclone.mobile.PhoneToolError(
                com.cyclone.mobile.PhoneToolErrorCode.CAPABILITY_UNAVAILABLE,
                "Execution scope unavailable (MUTATE_LOCK); observe the current session again.",
            )
        }
        val env = CycloneAgentEnvironment(runtime)
        env.observe("open Settings")
        val result = env.act(
            "phone.open_app",
            JSONObject().put("package", "com.android.settings"),
            "open Settings",
        )
        assertFalse(result.androidExecutionOk)
        assertEquals(1, runtime.afterCaptureCalls)
        assertEquals("obs-2", result.afterObservationId)
        assertTrue(result.retryable)
        assertEquals(AgentFailureClass.CAPABILITY_UNAVAILABLE, result.errorClass)
        assertFalse(
            com.cyclone.mobile.agent.recovery.ActionOutcomePolicy.hardBlocker(
                result.errorClass,
                result.safeMessage,
            ),
        )
    }

    @Test fun accessibilityLossDoesNotCaptureAfter() {
        val before = observation("obs-1", "home", "fp-1")
        val runtime = FakeRuntime(before, observation("obs-2", "home", "fp-2")).apply {
            executionOk = false
            executionError = com.cyclone.mobile.PhoneToolError(
                com.cyclone.mobile.PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED,
                "Accessibility is disconnected.",
            )
        }
        val env = CycloneAgentEnvironment(runtime)
        env.observe("Continue")
        val result = env.act("phone.click", JSONObject().put("elementId", elementId(before)), "Continue")
        assertEquals(0, runtime.afterCaptureCalls)
        assertNull(result.afterObservationId)
        assertEquals(AgentFailureClass.ACCESSIBILITY_UNAVAILABLE, result.errorClass)
    }

    @Test fun recentHistoryKeepsNewestEightOutcomesInChronologicalOrder() {
        val first = observation("obs-0", "page-0", "fp-0")
        val runtime = FakeRuntime(first, first)
        val env = CycloneAgentEnvironment(runtime)
        repeat(12) { i ->
            val before = observation("obs-$i", "page-$i", "fp-$i")
            runtime.captureQueue.addLast(before)
            runtime.afterObservation = observation("after-$i", "next-$i", "next-fp-$i", "Next")
            env.observe("Step $i")
            val current = runtime.current()!!
            env.act("phone.click", JSONObject().put("elementId", elementId(current)), "Step $i")
        }
        assertEquals((4..11).map { "Step $it" }, env.history().takeLast(8).map { it.goal })
        assertEquals("Step 11", env.history().last().goal)
    }

    private fun semantic(
        pageKey: String = "home",
        fingerprint: String = "fp-1",
        selected: Boolean = false,
        checked: Boolean = false,
        focused: Boolean = false,
        textState: String? = null,
        label: String = "Continue",
    ) = SemanticObservationState(
        "pkg", pageKey, fingerprint, "Home $label",
        listOf(SemanticElementState(
            "control", label, if (textState == null) "button" else "textbox",
            selected, checked, focused, textState,
        )),
    )

    private fun observation(
        id: String,
        pageKey: String,
        fingerprint: String,
        label: String = "Continue",
    ): GatewayObservation {
        val elementId = "semantic:$id:control"
        val evidence = JSONObject()
            .put("elementId", elementId)
            .put("observationId", id)
            .put("source", "semantic")
            .put("controlKey", "control")
            .put("label", label)
            .put("semanticName", label.lowercase().replace(' ', '_'))
            .put("role", "button")
            .put("selector", JSONObject().put("text", label).put("clickable", true))
            .put("resourceId", "id/control")
            .put("selected", false)
            .put("checked", false)
            .put("focused", false)
        val element = GatewayElement(elementId, "semantic", label, evidence.getString("semanticName"), "button", evidence)
        val page = PageContext(
            pageKey = pageKey,
            packageName = "pkg",
            className = "pkg.Main",
            title = pageKey,
            structuralKey = "struct-$pageKey",
            contentKey = "content-$pageKey",
            controls = emptyList(),
            observationCount = 1,
            firstSeenAt = 1,
            lastSeenAt = 1,
        )
        val payload = JSONObject()
            .put("activity", "pkg.Main")
            .put("accessibilityFingerprint", fingerprint)
            .put("pageSummary", JSONObject().put("summary", pageKey))
            .put("pageText", JSONObject().put("text", "$pageKey $label").put("lines", JSONArray()).put("lineCount", 1))
            .put("pageEvidence", JSONObject().put("pageKey", pageKey))
            .put("nextHopHints", JSONArray())
            .put("semanticControls", JSONArray().put(evidence))
        return GatewayObservation(id, 1, page, payload, mapOf(elementId to element))
    }

    private fun observationWithHiddenRawTarget(): GatewayObservation {
        val source = observation("obs-search", "home", "fp-search")
        val elements = linkedMapOf<String, GatewayElement>()
        val semantic = JSONArray()
        repeat(40) { index ->
            val id = "semantic:" + source.id + ":c$index"
            val evidence = JSONObject()
                .put("elementId", id)
                .put("observationId", source.id)
                .put("source", "semantic")
                .put("controlKey", "c$index")
                .put("label", "Visible control $index")
                .put("semanticName", "visible_control_$index")
                .put("role", "button")
                .put("selector", JSONObject().put("text", "Visible control $index"))
            elements[id] = GatewayElement(id, "semantic", "Visible control $index", "visible_control_$index", "button", evidence)
            if (index < 36) semantic.put(evidence)
        }
        val rawId = "raw:" + source.id + ":deep"
        val raw = JSONObject()
            .put("elementId", rawId)
            .put("observationId", source.id)
            .put("source", "raw_accessibility")
            .put("label", "Deep hidden target")
            .put("text", "Deep hidden target")
            .put("role", "button")
            .put("resourceId", "id/deep")
        elements[rawId] = GatewayElement(rawId, "raw_accessibility", "Deep hidden target", "deep_hidden_target", "button", raw)
        return source.copy(
            payload = JSONObject(source.payload.toString()).put("semanticControls", semantic),
            elements = elements,
        )
    }

    private fun elementId(observation: GatewayObservation) = observation.elements.keys.first()

    private class FakeRuntime(
        initial: GatewayObservation,
        var afterObservation: GatewayObservation?,
    ) : CycloneAgentRuntimePort {
        var currentObservation: GatewayObservation? = null
        var captureCalls = 0
        var captureError: Throwable? = null
        val captureQueue = ArrayDeque<GatewayObservation>().apply { addLast(initial) }
        var executionCalls = 0
        var lastParams: JSONObject? = null
        var learningCalls = 0
        var executionOk = true
        var executionError: com.cyclone.mobile.PhoneToolError? = null
        var afterCaptureCalls = 0

        override fun capture(): GatewayObservation {
            captureCalls++
            captureError?.let { throw it }
            val value = if (captureQueue.isEmpty()) currentObservation ?: error("No observation") else captureQueue.removeFirst()
            currentObservation = value
            return value
        }
        override fun current() = currentObservation
        override fun search(observation: GatewayObservation, query: String, limit: Int) =
            GatewayObservationAdapter.search(observation, query, limit)
        override fun element(observation: GatewayObservation, elementId: String) =
            GatewayObservationAdapter.element(observation, elementId)
        override fun screenshot(goal: String) = JSONObject()
            .put("filePath", "/tmp/evidence.png").put("width", 720).put("height", 1280).put("timestampMs", 10)
        override fun readinessFailure(): AgentFailure? = null
        override fun policyFailure(tool: String, params: JSONObject): AgentFailure? = null
        override fun execute(requestId: String, tool: String, params: JSONObject): PhoneToolResult {
            lastParams = JSONObject(params.toString())
            executionCalls += 1
            return PhoneToolResult(requestId, tool, executionOk, 1, 2, error = executionError)
        }
        override fun captureAfter(tool: String, params: JSONObject, before: GatewayObservation): GatewayObservation? {
            afterCaptureCalls += 1
            currentObservation = afterObservation
            return afterObservation
        }
        override fun verify(
            tool: String,
            expectedPackage: String,
            goalLabel: String,
            before: GatewayObservation,
            after: GatewayObservation?,
            androidExecutionOk: Boolean,
            executorAssertionFailed: Boolean,
            explicitExpectation: Boolean,
        ) = AgentSemanticVerifier.verify(
            tool, androidExecutionOk, executorAssertionFailed, explicitExpectation,
            expectedPackage, goalLabel, semantic(before), after?.let(::semantic),
        )
        override fun recordLearning(
            goal: String,
            tool: String,
            params: JSONObject,
            before: GatewayObservation,
            after: GatewayObservation?,
            androidExecutionOk: Boolean,
            verification: AgentSemanticVerification,
        ): AgentLearningResult {
            learningCalls += 1
            return AgentLearningResult(true, "Verified semantic route recorded", JSONObject().put("recorded", true))
        }
        override fun brainRecall(goal: String) = JSONObject().put("goal", goal)
        override fun knownRoutes(goal: String) = JSONObject().put("goal", goal)

        private fun semantic(observation: GatewayObservation) = SemanticObservationState(
            observation.page.packageName,
            observation.page.pageKey,
            observation.payload.optString("accessibilityFingerprint"),
            observation.page.title + " " + observation.elements.values.joinToString(" ") { it.label } + " " +
                observation.payload.optJSONObject("pageText")?.optString("text").orEmpty(),
            observation.elements.values.map { element ->
                val e = element.evidence
                SemanticElementState(
                    e.optString("controlKey").ifBlank { element.semanticName + "|" + element.role },
                    element.label,
                    element.role,
                    e.optBoolean("selected"),
                    e.optBoolean("checked"),
                    e.optBoolean("focused"),
                    e.optString("textStateDigest").takeIf { it.isNotBlank() && it != "null" },
                )
            },
        )
    }
}
