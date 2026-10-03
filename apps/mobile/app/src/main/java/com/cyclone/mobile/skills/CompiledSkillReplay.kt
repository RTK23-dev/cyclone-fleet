package com.cyclone.mobile.skills

import com.cyclone.mobile.fastpath.FastPathLoop
import com.cyclone.mobile.fastpath.FastPathNavIsolation
import com.cyclone.mobile.runtime.session.ExecutionSession
import org.json.JSONObject

/**
 * Executes a compiled skill through [PhoneToolPort] (production: PhoneToolExecutor).
 *
 * Misses escalate to Fast Path LLM. Vision is used only when the current tree is
 * empty or `perceptionMode=vision_escalate`. Unchanged after Fast Path settle is
 * never a second click. Named workspace routes never inject on display 0.
 */
fun interface PhoneToolPort {
    fun act(tool: String, params: JSONObject, sessionId: String, displayId: Int): SkillActOutcome
}

fun interface PageObservePort {
    fun observe(sessionId: String, displayId: Int): ReplayPage
}

data class ReplayPage(
    val packageName: String,
    val pageKey: String,
    val perceptionMode: String = "a11y",
    val treeUseful: Boolean = true,
    val fingerprint: String? = null,
    val controls: List<SemanticSelector> = emptyList(),
)

object CompiledSkillReplay {
    fun replay(
        route: CompiledSkillRoute,
        page: ReplayPage,
        sessionId: String,
        displayId: Int,
        act: PhoneToolPort,
        observe: PageObservePort? = null,
    ): SkillReplayResult {
        bind(route, page, sessionId, displayId)?.let { return it }

        val isolated = FastPathNavIsolation.keep(
            route.steps,
            { it.tool },
            { it.expectedPageChange },
        )
        // Compiled skills are whole-path replays, not one-turn LLM batches. Isolation still
        // forbids treating Unchanged as a retry channel; it does not drop later verified hops.
        if (isolated.allowed.isEmpty()) {
            return miss(SkillMissReason.NO_MATCH, SkillEscalateTo.FAST_PATH_LLM, "compiled route had no executable steps", route.id)
        }

        var current = page
        var stepsRun = 0
        for (step in route.steps) {
            visionMiss(current, route.id, stepsRun)?.let { return it }
            if (current.pageKey != step.beforePageKey) {
                return miss(
                    SkillMissReason.PAGE_MISMATCH,
                    SkillEscalateTo.FAST_PATH_LLM,
                    "expected ${step.beforePageKey} before ${step.id}, saw ${current.pageKey}",
                    route.id,
                    stepsRun,
                )
            }
            if (!selectorPresent(step.selector, current)) {
                return miss(
                    SkillMissReason.SELECTOR_MISS,
                    escalateForTree(current),
                    "selector for ${step.id} not on current page",
                    route.id,
                    stepsRun,
                )
            }
            val params = ExecutionIdentity.attach(step.toPhoneParams(), sessionId, displayId)
            val outcome = act.act(step.tool, params, sessionId, displayId)
            stepsRun += 1
            if (outcome.gateRequired) {
                return miss(SkillMissReason.GATE_REQUIRED, SkillEscalateTo.FAST_PATH_LLM, "GATE blocked compiled step ${step.id}", route.id, stepsRun)
            }
            if (outcome.policyDenied) {
                return miss(SkillMissReason.POLICY, SkillEscalateTo.FAST_PATH_LLM, "policy denied compiled step ${step.id}", route.id, stepsRun)
            }
            if (!outcome.ok) {
                return miss(SkillMissReason.AFTER_STATE_MISMATCH, SkillEscalateTo.FAST_PATH_LLM,
                    "executor rejected compiled step", route.id, stepsRun)
            }
            if (!outcome.selectorResolved) {
                return miss(SkillMissReason.SELECTOR_MISS, escalateForTree(current), "executor could not resolve ${step.id}", route.id, stepsRun)
            }
            if (step.expectedPageChange && (outcome.unchangedWarning || !outcome.fingerprintChanged)) {
                // Fast Path contract: Unchanged is not a second click and is not a compiled-skill success.
                return miss(
                    SkillMissReason.UNCHANGED,
                    SkillEscalateTo.FAST_PATH_LLM,
                    FastPathLoop.let { "UNCHANGED after ${step.id}; compiled skill missed" },
                    route.id,
                    stepsRun,
                )
            }
            val after = observe?.observe(sessionId, displayId)?.also { current = it }
                ?: ReplayPage(
                    packageName = outcome.afterPackage ?: current.packageName,
                    pageKey = outcome.afterPageKey ?: current.pageKey,
                    perceptionMode = outcome.perceptionMode,
                    treeUseful = outcome.treeUseful,
                ).also { current = it }
            if (step.expectedPageChange && after.pageKey != step.afterPageKey) {
                return miss(
                    SkillMissReason.AFTER_STATE_MISMATCH,
                    SkillEscalateTo.FAST_PATH_LLM,
                    "expected ${step.afterPageKey} after ${step.id}, saw ${after.pageKey}",
                    route.id,
                    stepsRun,
                )
            }
        }
        return SkillReplayResult.Hit(
            routeId = route.id,
            stepsRun = stepsRun,
            nlPlaybook = route.nlPlaybook,
            sessionId = sessionId,
            displayId = displayId,
        )
    }

    fun tryReplay(
        routes: List<CompiledSkillRoute>,
        packageName: String,
        goal: String,
        page: ReplayPage,
        sessionId: String,
        displayId: Int,
        act: PhoneToolPort,
        observe: PageObservePort? = null,
    ): SkillReplayResult {
        visionMiss(page, routeId = null, stepsRun = 0)?.let { return it }
        val matched = SkillRouteCompiler.match(routes, packageName, goal, page.pageKey, sessionId, displayId)
            ?: return miss(SkillMissReason.NO_MATCH, SkillEscalateTo.FAST_PATH_LLM, "no compiled skill for $packageName / ${PlaybookGoal.signature(goal)}")
        return replay(matched, page, sessionId, displayId, act, observe)
    }

    private fun bind(
        route: CompiledSkillRoute,
        page: ReplayPage,
        sessionId: String,
        displayId: Int,
    ): SkillReplayResult.Miss? {
        if (route.isWorkspace && displayId == ExecutionSession.DEFAULT_DISPLAY_ID) {
            return miss(SkillMissReason.DISPLAY_ZERO_WORKSPACE, SkillEscalateTo.FAST_PATH_LLM, "named workspace compiled skill cannot inject on display 0", route.id)
        }
        if (sessionId != route.sessionId) {
            return miss(SkillMissReason.CROSS_SESSION, SkillEscalateTo.FAST_PATH_LLM, "compiled skill session ${route.sessionId} cannot authorize $sessionId", route.id)
        }
        if (displayId != route.displayId) {
            return miss(SkillMissReason.DISPLAY_MISMATCH, SkillEscalateTo.FAST_PATH_LLM, "compiled skill display ${route.displayId} does not match $displayId", route.id)
        }
        if (page.packageName != route.packageName) {
            return miss(SkillMissReason.NO_MATCH, SkillEscalateTo.FAST_PATH_LLM, "package ${page.packageName} is not ${route.packageName}", route.id)
        }
        if (page.pageKey != route.startPageKey) {
            return miss(SkillMissReason.PAGE_MISMATCH, SkillEscalateTo.FAST_PATH_LLM, "start page ${page.pageKey} is not ${route.startPageKey}", route.id)
        }
        return null
    }

    private fun selectorPresent(selector: SemanticSelector, page: ReplayPage): Boolean {
        if (page.controls.isEmpty()) return true
        return page.controls.any { selector.matches(it) || it.matches(selector) }
    }

    private fun visionMiss(page: ReplayPage, routeId: String?, stepsRun: Int): SkillReplayResult.Miss? {
        if (page.perceptionMode == "vision_escalate" || !page.treeUseful) {
            return miss(
                if (!page.treeUseful) SkillMissReason.EMPTY_TREE else SkillMissReason.VISION_ESCALATE,
                SkillEscalateTo.VISION,
                "a11y tree is not usable; vision/Fast Path LLM only on miss",
                routeId,
                stepsRun,
            )
        }
        return null
    }

    private fun escalateForTree(page: ReplayPage): SkillEscalateTo =
        if (page.perceptionMode == "vision_escalate" || !page.treeUseful) SkillEscalateTo.VISION else SkillEscalateTo.FAST_PATH_LLM

    private fun miss(
        reason: SkillMissReason,
        escalateTo: SkillEscalateTo,
        detail: String,
        routeId: String? = null,
        stepsRun: Int = 0,
    ) = SkillReplayResult.Miss(reason, escalateTo, detail, routeId, stepsRun)
}

object ExecutionIdentity {
    fun attach(params: JSONObject, sessionId: String, displayId: Int): JSONObject {
        val out = JSONObject(params.toString())
        out.put("sessionId", sessionId)
        out.put("displayId", displayId)
        return out
    }
}
