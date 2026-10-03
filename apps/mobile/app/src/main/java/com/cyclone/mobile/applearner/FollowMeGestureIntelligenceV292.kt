package com.cyclone.mobile.applearner

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.ai.TracePrivacy
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.guided.TeachingGestureEvidenceV292
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * V2.9.2 fills the biggest Follow Me gap: a user swipe used to collapse into a generic scroll and
 * never became a directional navigation transition. This additive observer turns real human swipes
 * into before/after page evidence, App Graph gesture hops and exact phone.swipe Brain micro-skills.
 */
object FollowMeGestureIntelligenceV292 {
    private val executor = Executors.newSingleThreadExecutor()
    private val lastObserve = AtomicLong(0L)

    @Volatile private var sessionId: String? = null
    @Volatile private var previousPackage: String? = null
    @Volatile private var previousPageKey: String? = null
    @Volatile private var previousPageTitle: String? = null
    @Volatile private var pendingGesture: PendingGesture? = null
    private val lastScrollPosition = mutableMapOf<String, Pair<Int, Int>>()

    private data class PendingGesture(
        val packageName: String,
        val direction: String,
        val label: String,
        val sourceSelector: JSONObject,
        val eventAt: Long,
    )

    fun onAccessibilityEvent(context: Context, event: AccessibilityEvent) {
        val progress = FollowMeLearnerRuntime.progress()
        if (!progress.active || progress.paused) {
            if (!progress.active) reset()
            return
        }
        if (progress.teachingSessionId != sessionId) {
            reset()
            sessionId = progress.teachingSessionId
        }

        val packageName = event.packageName?.toString()?.takeIf(String::isNotBlank) ?: return
        if (packageName == context.packageName) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val direction = inferDirection(packageName, event)
            if (direction != null) {
                pendingGesture = PendingGesture(
                    packageName = packageName,
                    direction = direction,
                    label = "Swipe $direction",
                    sourceSelector = event.source?.let(::selectorFromNode) ?: JSONObject(),
                    eventAt = System.currentTimeMillis(),
                )
            }
        }

        if (event.eventType !in setOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
            )) return

        val now = System.currentTimeMillis()
        val previous = lastObserve.get()
        if (now - previous < 380L || !lastObserve.compareAndSet(previous, now)) return
        executor.submit {
            Thread.sleep(if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) 360L else 180L)
            observeAndLink(context)
        }
    }

    @Synchronized
    private fun observeAndLink(context: Context) {
        val progress = FollowMeLearnerRuntime.progress()
        if (!progress.active || progress.paused) return
        AppLearnerRuntime.initialize(context)
        AdaptiveBrainRuntime.initialize(context)
        PageAwarenessRuntime.initialize(context)

        val result = PhoneToolExecutor.execute(
            context,
            PhoneToolRequest("v292-follow-gesture-observe-${UUID.randomUUID()}", "phone.observe", JSONObject()),
        )
        val snapshot = result.payload as? JSONObject ?: return
        val packageName = snapshot.optString("package").takeIf(String::isNotBlank) ?: return
        if (packageName == context.packageName) return
        val page = PageAwarenessRuntime.capture(context, snapshot)

        val oldPackage = previousPackage
        val oldPageKey = previousPageKey
        val oldTitle = previousPageTitle
        val gesture = pendingGesture
        val width = snapshot.optInt("screenWidth", 1080).coerceAtLeast(320)
        val height = snapshot.optInt("screenHeight", 1920).coerceAtLeast(480)
        val gestureFresh = gesture != null && gesture.packageName == packageName && System.currentTimeMillis() - gesture.eventAt < 4_000L
        val pageChanged = oldPackage == packageName && !oldPageKey.isNullOrBlank() && oldPageKey != page.pageKey

        if (pageChanged && gestureFresh && gesture != null) {
            var graph = AppLearnerRuntime.graph(packageName)
            var from = graph?.screens?.firstOrNull { it.recognition.semanticFingerprint == oldPageKey }
            var to = graph?.screens?.firstOrNull { it.recognition.semanticFingerprint == page.pageKey }
            if (from == null || to == null) {
                Thread.sleep(180L)
                graph = AppLearnerRuntime.graph(packageName)
                from = graph?.screens?.firstOrNull { it.recognition.semanticFingerprint == oldPageKey }
                to = graph?.screens?.firstOrNull { it.recognition.semanticFingerprint == page.pageKey }
            }

            if (from != null && to != null) {
                val gestureSelector = stableGestureSelector(gesture, width, height, oldPageKey.orEmpty(), page.pageKey)
                val action = LearnedAction(
                    packageName = packageName,
                    screenId = from.id,
                    semanticName = "swipe_${gesture.direction}",
                    label = gesture.label,
                    androidActions = listOf("USER_SWIPE_${gesture.direction.uppercase()}", "GESTURE", "HUMAN_DEMONSTRATED"),
                    selectorJson = gestureSelector.toString(),
                    risk = ActionRisk.SAFE,
                    knowledgeState = KnowledgeState.UNDERSTOOD,
                    // Intentionally below the legacy graph fast-path's click threshold. V2.9.2 Page
                    // Agent reads this gesture metadata + exact Brain phone.swipe evidence instead.
                    confidence = 0.69,
                )
                AppLearnerRuntime.store.upsertAction(action)
                val stored = AppLearnerRuntime.store.listActions(packageName).firstOrNull {
                    it.screenId == from.id && it.semanticName == action.semanticName && it.selectorJson == action.selectorJson
                }
                if (stored != null) {
                    // Do not markActionSuccess(): legacy V2.8 graph replay treats all verified graph
                    // actions as clicks. The transition itself can still become VERIFIED by repeated
                    // demonstrations, while exact executable swipe evidence lives in Adaptive Brain.
                    AppLearnerRuntime.store.upsertTransition(
                        LearnedTransition(
                            packageName = packageName,
                            fromScreenId = from.id,
                            actionId = stored.id,
                            toScreenId = to.id,
                            knowledgeState = KnowledgeState.UNDERSTOOD,
                            confidence = 0.91,
                        ),
                    )
                }
            }
            recordGestureEvidence(context, progress.teachingSessionId, gesture, packageName, oldPageKey.orEmpty(), oldTitle.orEmpty(), page.pageKey, page.title, width, height)
            pendingGesture = null
        } else if (gestureFresh && gesture != null && oldPackage == packageName && !oldPageKey.isNullOrBlank()) {
            // Some horizontal pagers/carousels keep the same semantic page key even though Android
            // emitted a real scroll. The old learner discarded these. Keep the demonstrated gesture
            // as Brain/routine evidence even when the page fingerprint itself does not change.
            recordGestureEvidence(context, progress.teachingSessionId, gesture, packageName, oldPageKey, oldTitle.orEmpty(), page.pageKey, page.title, width, height)
            pendingGesture = null
        }

        previousPackage = packageName
        previousPageKey = page.pageKey
        previousPageTitle = page.title
    }

    private fun recordGestureEvidence(
        context: Context,
        teachingSessionId: String?,
        gesture: PendingGesture,
        packageName: String,
        fromPageKey: String,
        fromTitle: String,
        toPageKey: String,
        toTitle: String,
        width: Int,
        height: Int,
    ) {
        val params = swipeParams(gesture.direction, width, height)
        val before = JSONObject().put("currentPackage", packageName).put("fingerprint", fromPageKey)
        val after = JSONObject().put("currentPackage", packageName).put("fingerprint", toPageKey)
        AdaptiveBrainRuntime.recordToolOutcome(
            context = context,
            goal = if (fromPageKey == toPageKey) "use ${gesture.label} on ${fromTitle.ifBlank { "this page" }}" else "navigate from ${fromTitle.ifBlank { "this page" }} to ${toTitle.ifBlank { "the next page" }} with ${gesture.label}",
            tool = "phone.swipe",
            params = params,
            before = before,
            after = after,
            ok = true,
            source = "HUMAN_FOLLOW_ME_GESTURE",
        )
        AdaptiveBrainRuntime.store.writeMirror()
        teachingSessionId?.let { id ->
            TeachingGestureEvidenceV292.append(
                context = context,
                sessionId = id,
                timestampMs = gesture.eventAt,
                packageName = packageName,
                fromPageKey = fromPageKey,
                fromTitle = fromTitle,
                toPageKey = toPageKey,
                toTitle = toTitle,
                direction = gesture.direction,
                params = params,
            )
        }
        AppLearnerRuntime.store.mirror(packageName)
    }

    private fun stableGestureSelector(gesture: PendingGesture, width: Int, height: Int, fromPageKey: String, toPageKey: String): JSONObject = JSONObject().apply {
        put("gesture", "swipe_${gesture.direction}")
        gesture.sourceSelector.optString("resourceId").takeIf(String::isNotBlank)?.let { put("resourceId", it) }
        gesture.sourceSelector.optString("contentDescription").takeIf(String::isNotBlank)?.let { put("contentDescription", it) }
        gesture.sourceSelector.optString("role").takeIf(String::isNotBlank)?.let { put("role", it) }
        if (gesture.sourceSelector.has("scrollable")) put("scrollable", gesture.sourceSelector.optBoolean("scrollable"))
        put("screenWidth", width)
        put("screenHeight", height)
        put("fromPageKey", fromPageKey)
        put("toPageKey", toPageKey)
    }

    private fun inferDirection(packageName: String, event: AccessibilityEvent): String? {
        val dx = event.scrollDeltaX
        val dy = event.scrollDeltaY
        val direction = when {
            abs(dx) >= 4 && abs(dx) >= abs(dy) -> if (dx > 0) "left" else "right"
            abs(dy) >= 4 -> if (dy > 0) "up" else "down"
            else -> {
                val previous = lastScrollPosition[packageName]
                val current = event.scrollX to event.scrollY
                lastScrollPosition[packageName] = current
                when {
                    previous != null && abs(current.first - previous.first) >= 4 -> if (current.first > previous.first) "left" else "right"
                    previous != null && abs(current.second - previous.second) >= 4 -> if (current.second > previous.second) "up" else "down"
                    event.fromIndex >= 0 && event.toIndex >= 0 && event.fromIndex != event.toIndex -> if (event.toIndex > event.fromIndex) "left" else "right"
                    else -> null
                }
            }
        }
        lastScrollPosition[packageName] = event.scrollX to event.scrollY
        return direction
    }

    internal fun swipeParamsForTest(direction: String, width: Int, height: Int): JSONObject = swipeParams(direction, width, height)

    private fun swipeParams(direction: String, width: Int, height: Int): JSONObject {
        val left = (width * 0.18).toInt()
        val right = (width * 0.82).toInt()
        val top = (height * 0.25).toInt()
        val bottom = (height * 0.75).toInt()
        val centerX = width / 2
        val centerY = height / 2
        return when (direction) {
            "left" -> JSONObject().put("x1", right).put("y1", centerY).put("x2", left).put("y2", centerY).put("durationMs", 320)
            "right" -> JSONObject().put("x1", left).put("y1", centerY).put("x2", right).put("y2", centerY).put("durationMs", 320)
            "down" -> JSONObject().put("x1", centerX).put("y1", top).put("x2", centerX).put("y2", bottom).put("durationMs", 320)
            else -> JSONObject().put("x1", centerX).put("y1", bottom).put("x2", centerX).put("y2", top).put("durationMs", 320)
        }
    }

    private fun selectorFromNode(node: AccessibilityNodeInfo): JSONObject {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return JSONObject().apply {
            node.viewIdResourceName?.takeIf(String::isNotBlank)?.let { put("resourceId", it) }
            node.contentDescription?.toString()?.let(TracePrivacy::clean)?.takeIf(String::isNotBlank)?.take(160)?.let { put("contentDescription", it) }
            put("role", if (node.isScrollable) "scroll_container" else "generic")
            put("scrollable", node.isScrollable)
            put("bounds", JSONObject().put("left", rect.left).put("top", rect.top).put("right", rect.right).put("bottom", rect.bottom))
            put("androidActions", JSONArray().also { a -> node.actionList.orEmpty().forEach { a.put(it.label?.toString().orEmpty().ifBlank { it.id.toString() }) } })
        }
    }

    @Synchronized
    private fun reset() {
        sessionId = null
        previousPackage = null
        previousPageKey = null
        previousPageTitle = null
        pendingGesture = null
        lastScrollPosition.clear()
        lastObserve.set(0L)
    }
}
