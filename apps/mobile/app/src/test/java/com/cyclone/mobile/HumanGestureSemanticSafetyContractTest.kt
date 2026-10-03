package com.cyclone.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM contract guard for Android-service ordering that cannot be instantiated by plain local tests.
 * These assertions inspect the real production source and fail if authorization/semantic-native calls
 * are moved behind Human Gesture dispatch. Pure SessionContract behavior is covered separately.
 */
class HumanGestureSemanticSafetyContractTest {
    private val service by lazy { productionSource("com/cyclone/mobile/CycloneAccessibilityService.kt") }
    private val executor by lazy { productionSource("com/cyclone/mobile/PhoneToolExecutor.kt") }
    private val dispatch by lazy { productionSource("com/cyclone/mobile/HumanGestureDispatch.kt") }
    private val controller by lazy { productionSource("com/cyclone/mobile/ai/OverlayChromeController.kt") }
    private val passthrough by lazy { productionSource("com/cyclone/mobile/ui/overlay/OverlayGesturePassthrough.kt") }
    private val runtime by lazy { productionSource("com/cyclone/mobile/ui/overlay/OverlayChromeRuntime.kt") }
    private val overlayEvent by lazy { productionSource("com/cyclone/mobile/ui/overlay/OverlayChromeEvent.kt") }
    private val loop by lazy { productionSource("com/cyclone/mobile/fastpath/FastPathLoop.kt") }

    @Test
    fun `semantic click and select stay ahead of coordinate fallback`() {
        val click = slice(service, "fun click(", "private fun activateNode")
        assertOrdered(click, "ClickGateIntercept.decide", "HumanGestureDispatch.tap(")
        assertOrdered(click, "activateNode(targetLive", "HumanGestureDispatch.tap(")
        assertFalse("HumanGestureRuntimePolicy.resolve" in click)

        val activate = slice(service, "private fun activateNode", "private fun clickActivatableRelative")
        assertTrue("ACTION_CLICK" in activate)
        assertTrue("ACTION_SELECT" in activate)
        assertOrdered(activate, "ACTION_CLICK", "ACTION_SELECT")
    }

    @Test
    fun `semantic long click stays ahead of human gesture fallback`() {
        val longPress = slice(service, "fun longPress(\n        selector", "fun typeEditable")
        assertOrdered(longPress, "ACTION_LONG_CLICK", "HumanGestureDispatch.longPress(")
    }

    @Test
    fun `semantic scroll stays ahead of safely grounded synthesized fallback`() {
        val scroll = slice(executor, "private fun foregroundScroll(", "private fun workspaceGestureEvidence")
        assertOrdered(scroll, "s.scroll(selector, forward)", "s.swipe(")
        assertOrdered(scroll, "firstOrNull { it.scrollable }", "s.swipe(")
        assertOrdered(scroll, "node.bounds.height < 96", "s.swipe(")
    }

    @Test
    fun `normal profile cannot jump ahead of semantic click`() {
        val click = slice(service, "fun click(", "private fun activateNode")
        assertTrue("preference = humanize" in click)
        assertOrdered(click, "activateNode(targetLive", "preference = humanize")
        assertOrdered(click, "clickActivatableAncestor", "preference = humanize")
    }

    @Test
    fun `foreground authorization checks remain before dispatch`() {
        val internal = slice(executor, "private fun executeInternal", "private fun currentFingerprint")
        assertOrdered(internal, "!foregroundInputAllowed()", "dispatch(context, request")
        assertTrue("DeviceState.controller == DeviceState.Controller.AGENT || humanDesktopControlActive()" in executor)
        assertTrue("PhoneToolExecutor.withHumanDesktopControl" in productionSource("com/cyclone/mobile/gateway/GatewayV33ActionAdapter.kt"))
        assertOrdered(internal, "DeviceState.requireFreshObservation", "dispatch(context, request")
        assertOrdered(internal, "MutationGrounding.requiredFor", "dispatch(context, request")
        assertOrdered(internal, "isDuplicateAction(request)", "dispatch(context, request")
        assertTrue("cacheKey(request)" in executor)
    }

    @Test
    fun `workspace stale policy and mutation lock remain ahead of input`() {
        val workspace = slice(executor, "private fun executeWorkspace", "private fun executeInternal")
        assertOrdered(workspace, "GatewayObservationStore.current(scope.sessionId)", "runtime.input(")
        assertOrdered(workspace, "STALE_SESSION", "runtime.input(")
        assertOrdered(workspace, "CycloneAiAccessPolicy.evaluate", "runtime.input(")
        // Plan 28: every background approval check is one function, called before any input.
        assertOrdered(workspace, "workspaceGate(scope", "runtime.input(")
        assertTrue("GateClassifier.classify(tool, labels)" in slice(executor, "private fun workspaceGate(", "private fun workspaceTouchFailure"))
        assertOrdered(workspace, "authorizeTouch", "HumanGestureDispatch.tap")
        assertOrdered(workspace, "HumanGestureDispatch.tap", "runtime.input(")
        assertTrue("setDisplayId" in dispatch)
        assertTrue("synchronized(mutationLock)" in executor)
    }

    @Test
    fun `off remains a legacy straight compatibility branch`() {
        val tap = slice(dispatch, "fun tap(", "fun longPress(")
        val swipe = slice(dispatch, "fun swipe(", "private fun viewport")
        assertOrdered(tap, "profile == HumanizeProfile.OFF", "legacyTap(")
        assertOrdered(swipe, "profile == HumanizeProfile.OFF", "legacySwipe(")
    }

    @Test
    fun `dispatchGesture waits for GestureResultCallback instead of treating queue as success`() {
        assertTrue("GestureResultCallback" in dispatch)
        assertTrue("onCompleted" in dispatch)
        assertTrue("onCancelled" in dispatch)
        assertTrue("dispatchAndAwait" in dispatch)
        assertFalse(
            "queued dispatchGesture must not be treated as completion",
            "dispatchGesture(gesture, null, null)" in dispatch,
        )
        assertTrue("gestureAwaitBudgetMs" in dispatch)
        assertTrue("OverlayGesturePassthrough.withHostPassthrough" in dispatch)
        assertOrdered(dispatch, "withHostPassthrough", "dispatchGesture(gesture, callback")
        assertOrdered(dispatch, "dispatchGesture(gesture, callback", "done.await")
        assertTrue("REASON_TIMEOUT" in dispatch)
        assertTrue("GestureDispatchOutcome(false, REASON_TIMEOUT)" in dispatch)
        assertFalse("GestureDispatchOutcome(true, \"gesture_timeout\")" in dispatch)
        assertFalse("GestureDispatchOutcome(true, REASON_TIMEOUT)" in dispatch)
    }

    @Test
    fun `incomplete human gesture is not retried as a second click channel`() {
        val confirmation = slice(executor, "private fun actionWithConfirmation", "private fun launchedOutcome")
        assertTrue("HumanGestureDispatch.incomplete" in confirmation)
        assertTrue("PhoneToolErrorCode.TIMEOUT" in confirmation)
        assertTrue("do not repeat this mutation" in confirmation)
        assertOrdered(confirmation, "HumanGestureDispatch.incomplete", "if (attempts <= retries)")
    }

    @Test
    fun `overlay leaves the hit tree for the host stroke`() {
        assertTrue("withHostPassthrough" in passthrough)
        assertTrue("OverlayGesturePassthrough.bind" in runtime)
        assertTrue("OverlayGesturePassthrough.unbind" in runtime)
        val flagsFor = slice(controller, "private fun flagsFor", "fun syncHostGesturePassthrough")
        assertTrue("OverlayGesturePassthrough.active()" in flagsFor)
        assertTrue("withHostGesturePassthrough" in flagsFor)
        val applyLayout = slice(controller, "private fun applyLayout", "private fun syncToolsSheet")
        assertTrue("OverlayGesturePassthrough.active()" in applyLayout)
        assertTrue("View.GONE" in applyLayout)
        assertTrue("IMPORTANT_FOR_ACCESSIBILITY_NO" in applyLayout)
        assertTrue("hostGestureYielded" in applyLayout)
        val sync = slice(controller, "fun syncHostGesturePassthrough", "private fun recordIdleTap")
        assertTrue("Choreographer.getInstance().postFrameCallback" in sync)
        assertTrue("clicksHost: Boolean = false" in overlayEvent)
        assertTrue("Overlay buttons never click host accessibility nodes." in overlayEvent)
    }

    @Test
    fun `fast path still forbids a second click after a completed gesture`() {
        assertTrue("SETTLE_MS = 300L" in loop)
        assertTrue("settleAfterCompletedGestureMs" in loop)
        assertTrue("allowSecondClickChannel" in loop)
        val secondClick = slice(loop, "fun allowSecondClickChannel", "private fun result")
        assertTrue("return false" in secondClick)
        assertFalse("durationMs + SETTLE_MS" in loop)
    }

    private fun assertOrdered(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue("Missing '$first'", firstIndex >= 0)
        assertTrue("Missing '$second'", secondIndex >= 0)
        assertTrue("Expected '$first' before '$second'", firstIndex < secondIndex)
    }

    private fun slice(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        val to = source.indexOf(end, from + start.length)
        assertTrue("Missing source start '$start'", from >= 0)
        assertTrue("Missing source end '$end'", to > from)
        return source.substring(from, to)
    }

    private fun productionSource(relative: String): String {
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("apps/mobile/app/src/main/java/$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()?.replace("\r\n", "\n")
            ?: error("Production source not found for $relative from ${File(".").absolutePath}")
    }
}
