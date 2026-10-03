package com.cyclone.mobile.skills

import com.cyclone.mobile.runtime.session.ExecutionSession

/**
 * Promotes stable per-package playbooks into deterministic PhoneToolExecutor routes.
 *
 * A route compiles only when the same selector sequence has succeeded at least
 * [MIN_SUCCESSES] times (or is a user override), every step is semantic and SAFE,
 * and session/display binding from Stage 2 is legal. Named workspace routes never
 * bind display 0.
 */
object SkillRouteCompiler {
    const val MIN_SUCCESSES = 2

    fun compile(hint: PlaybookHint, nowMs: Long = hint.lastSuccessAtMs): SkillCompileResult {
        if (hint.steps.size < 2) return SkillCompileResult(null, "need 2+ verified steps")
        if (!PlaybookSafety.goalAllowed(hint.goal)) return SkillCompileResult(null, "goal is approval-sensitive; not compiled")
        if (!PlaybookSafety.workspaceDisplayLegal(hint.sessionId, hint.displayId)) {
            return SkillCompileResult(null, "workspace playbook cannot bind display 0")
        }
        if (hint.sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID && hint.displayId != 0) {
            return SkillCompileResult(null, "default-foreground cannot target a nonzero display")
        }
        val needed = if (hint.userOverride) 1 else MIN_SUCCESSES
        if (hint.successCount < needed) {
            return SkillCompileResult(null, "need $needed successful Fast Path runs before compile")
        }
        hint.steps.forEachIndexed { index, step ->
            if (!PlaybookSafety.toolAllowed(step.tool)) {
                return SkillCompileResult(null, "unsafe tool ${step.tool} at step $index")
            }
            if (!step.selector.isSemantic) {
                return SkillCompileResult(null, "non-semantic selector at step $index")
            }
            if (!PlaybookSafety.paramsSafe(step.params)) {
                return SkillCompileResult(null, "secret params at step $index")
            }
        }
        val steps = hint.steps.mapIndexed { index, step ->
            CompiledSkillStep(
                id = "step-${index + 1}",
                nl = step.nl,
                tool = step.tool,
                selector = step.selector,
                beforePageKey = step.beforePageKey,
                afterPageKey = step.afterPageKey,
                expectedPageChange = step.expectedPageChange,
                params = PlaybookSafety.stripParams(step.params),
            )
        }
        val route = CompiledSkillRoute(
            id = CompiledSkillIds.of(hint.packageName, hint.goalSignature, hint.startPageKey, hint.sessionId, hint.displayId),
            packageName = hint.packageName,
            goal = hint.goal,
            goalSignature = hint.goalSignature,
            startPageKey = hint.startPageKey,
            sessionId = hint.sessionId,
            displayId = hint.displayId,
            steps = steps,
            nlPlaybook = hint.nlPlaybook,
            compiledFromSuccesses = hint.successCount,
            compiledAtMs = nowMs,
        )
        return SkillCompileResult(route)
    }

    fun compileReady(store: PlaybookHintStore, packageName: String? = null, nowMs: Long = 0L): List<CompiledSkillRoute> {
        return store.list(packageName).mapNotNull { compile(it, nowMs).route }
    }

    fun match(
        routes: List<CompiledSkillRoute>,
        packageName: String,
        goal: String,
        startPageKey: String,
        sessionId: String,
        displayId: Int,
    ): CompiledSkillRoute? {
        val signature = PlaybookGoal.signature(goal)
        return routes
            .filter {
                it.packageName == packageName &&
                    it.goalSignature == signature &&
                    it.startPageKey == startPageKey &&
                    it.sessionId == sessionId &&
                    it.displayId == displayId
            }
            .maxWithOrNull(
                compareBy<CompiledSkillRoute> { it.compiledFromSuccesses }.thenBy { it.compiledAtMs },
            )
    }
}
