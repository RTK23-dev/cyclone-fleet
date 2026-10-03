package com.cyclone.mobile.applearner

import android.content.Context
import org.json.JSONObject

/** Outcome shape deliberately separates a delivered command from a verified page transition. */
data class PcRouteOutcomeEvidence(
    val transportOk: Boolean,
    val androidExecutionOk: Boolean,
    val verificationStatus: String,
    val before: PageContext?,
    val after: PageContext?,
) {
    val isVerifiedPageOutcome: Boolean
        get() = transportOk && androidExecutionOk && verificationStatus == "PASSED" &&
            before != null && after != null &&
            before.packageName == after.packageName && before.pageKey != after.pageKey
}

data class PcRouteLearningResult(
    val recorded: Boolean,
    val reason: String,
    val fromPageKey: String? = null,
    val toPageKey: String? = null,
    val transitionState: KnowledgeState? = null,
)

/**
 * Adds PC-driven, semantically verified clicks to the existing App Graph. This is intentionally
 * additive: it never creates a second execution engine and never treats transport success as a
 * learned route. Typed values, coordinates, cross-app transitions, and unknown controls are
 * rejected before persistence.
 */
internal object PcVerifiedRouteLearning {
    fun record(
        context: Context,
        store: AppKnowledgeStore,
        tool: String,
        params: JSONObject,
        outcome: PcRouteOutcomeEvidence,
    ): PcRouteLearningResult {
        if (!outcome.isVerifiedPageOutcome) {
            return PcRouteLearningResult(false, "Route learning requires a PASSED semantic after-state on a different page")
        }
        if (tool != "phone.click" && tool != "phone.long_press") {
            return PcRouteLearningResult(false, "Only semantic click routes are reusable next-hop evidence")
        }
        val selector = semanticSelector(params.optJSONObject("selector"))
            ?: return PcRouteLearningResult(false, "Coordinate-only or empty selectors are not reusable routes")
        val before = requireNotNull(outcome.before)
        val after = requireNotNull(outcome.after)
        val control = before.controls.firstOrNull { selectorsMatch(it.selector, selector) }
            ?: return PcRouteLearningResult(false, "The executed selector was not present in the source semantic page")
        if (control.risk != ActionRisk.SAFE || control.role in setOf("textbox", "edit_text")) {
            return PcRouteLearningResult(false, "Only safe, non-input controls become reusable routes")
        }

        val now = System.currentTimeMillis()
        val app = store.getApp(before.packageName) ?: LearnedApp(
            packageName = before.packageName,
            label = appLabel(context, before.packageName),
            knowledgeState = KnowledgeState.UNDERSTOOD,
            confidence = 0.75,
            lastLearnedAt = now,
            lastVerifiedAt = now,
            instructionSummary = "Semantic PC route evidence",
        )
        store.upsertApp(app.copy(
            knowledgeState = if (app.knowledgeState == KnowledgeState.STALE) KnowledgeState.UNDERSTOOD else app.knowledgeState,
            confidence = maxOf(app.confidence, 0.75),
            lastLearnedAt = now,
            lastVerifiedAt = now,
        ))

        val fromScreen = upsertScreen(store, before, now)
        val toScreen = upsertScreen(store, after, now)
        val learnedAction = LearnedAction(
            packageName = before.packageName,
            screenId = fromScreen.id,
            semanticName = control.semanticName,
            label = control.label,
            androidActions = control.androidActions.ifEmpty { listOf(if (tool == "phone.long_press") "ACTION_LONG_CLICK" else "ACTION_CLICK") },
            selectorJson = selector.toString(),
            risk = ActionRisk.SAFE,
            knowledgeState = KnowledgeState.UNDERSTOOD,
            confidence = control.confidence.coerceAtLeast(0.70),
        )
        store.upsertAction(learnedAction)
        val storedAction = store.listActions(before.packageName)
            .firstOrNull { it.screenId == fromScreen.id && it.semanticName == learnedAction.semanticName && selectorsMatch(runCatching { JSONObject(it.selectorJson) }.getOrNull(), selector) }
            ?: return PcRouteLearningResult(false, "The safe semantic action could not be stored")
        store.markActionSuccess(storedAction.id)
        store.upsertTransition(LearnedTransition(
            packageName = before.packageName,
            fromScreenId = fromScreen.id,
            actionId = storedAction.id,
            toScreenId = toScreen.id,
            knowledgeState = KnowledgeState.UNDERSTOOD,
            confidence = minOf(storedAction.confidence, fromScreen.confidence, toScreen.confidence).coerceAtLeast(0.70),
            observedCount = 1,
            successfulCount = 1,
            lastObservedAt = now,
        ))
        PageAwarenessRuntime.recordTransition(context, before, control, tool, JSONObject().put("selector", selector), after, success = true)
        store.mirror(before.packageName)
        val transition = store.graph(before.packageName)?.transitions?.firstOrNull {
            it.fromScreenId == fromScreen.id && it.actionId == storedAction.id && it.toScreenId == toScreen.id
        }
        return PcRouteLearningResult(
            recorded = transition != null,
            reason = if (transition == null) "Route transition was not available after persistence" else "Verified semantic route recorded",
            fromPageKey = before.pageKey,
            toPageKey = after.pageKey,
            transitionState = transition?.knowledgeState,
        )
    }

    private fun upsertScreen(store: AppKnowledgeStore, page: PageContext, now: Long): LearnedScreen {
        val existing = store.graph(page.packageName)?.screens?.firstOrNull {
            it.recognition.semanticFingerprint == page.pageKey
        }
        val recognition = ScreenRecognition(
            semanticFingerprint = page.pageKey,
            structuralFingerprint = page.structuralKey,
            stableAnchors = page.controls.map { PageSignatureEngine.normalizeLabel(it.label) }.filter(String::isNotBlank).distinct().take(24),
            className = page.className,
            titleHints = listOf(page.title).filter(String::isNotBlank),
        )
        val screen = existing?.copy(
            title = page.title.ifBlank { existing.title },
            recognition = recognition,
            knowledgeState = if (existing.knowledgeState == KnowledgeState.STALE) KnowledgeState.UNDERSTOOD else existing.knowledgeState,
            confidence = maxOf(existing.confidence, 0.75),
            lastSeenAt = now,
            lastVerifiedAt = now,
        ) ?: LearnedScreen(
            packageName = page.packageName,
            identity = PageSignatureEngine.semanticName(page.title, "page"),
            title = page.title,
            purpose = "Observed through a verified semantic PC route.",
            recognition = recognition,
            knowledgeState = KnowledgeState.UNDERSTOOD,
            confidence = 0.75,
            lastSeenAt = now,
            lastVerifiedAt = now,
        )
        store.upsertScreen(screen)
        return screen
    }

    private fun appLabel(context: Context, packageName: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun semanticSelector(raw: JSONObject?): JSONObject? {
        val selector = raw ?: return null
        val safe = JSONObject()
        listOf("resourceId", "text", "textContains", "contentDescription", "contentDescriptionContains", "role", "className")
            .forEach { key -> selector.optString(key).takeIf(String::isNotBlank)?.let { safe.put(key, it.take(180)) } }
        listOf("clickable", "enabled").forEach { key -> if (selector.has(key)) safe.put(key, selector.optBoolean(key)) }
        return safe.takeIf { it.length() > 0 }
    }

    private fun selectorsMatch(left: JSONObject?, right: JSONObject): Boolean {
        val first = left ?: return false
        return listOf("resourceId", "text", "textContains", "contentDescription", "contentDescriptionContains", "role")
            .any { key -> first.optString(key).isNotBlank() && first.optString(key) == right.optString(key) }
    }
}
