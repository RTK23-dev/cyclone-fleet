package com.cyclone.mobile.fastpath

import com.cyclone.mobile.runtime.session.ExecutionContext
import com.cyclone.mobile.runtime.session.ExecutionRequestScope
import com.cyclone.mobile.runtime.session.ExecutionSession
import com.cyclone.mobile.runtime.session.NamedWorkspaceControlPolicy
import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.runtime.session.SessionIdentityException
import com.cyclone.mobile.runtime.session.SessionPlane
import com.cyclone.mobile.runtime.session.SessionPlaneKind
import org.json.JSONObject

object NamedWorkspaceFastPath {
    const val BUDGET_MS = 90_000L
    const val CHROME_PACKAGE = "com.android.chrome"
    const val ACCEPTANCE_GOAL = "Open Chrome and search for Pixel 8"
    const val SEARCH_QUERY = "Pixel 8"
    const val ACCEPTANCE_SESSION_ID = "named-vd"
    const val ACCEPTANCE_DISPLAY_ID = 7

    /** Fail-closed: must be Session Kernel VD (sessionId != default-foreground, displayId > 0). */
    fun requireOwnedVd(sessionId: String, displayId: Int): SessionPlane =
        requireOwnedVd(JSONObject().put("sessionId", sessionId).put("displayId", displayId))

    fun requireOwnedVd(params: JSONObject): SessionPlane {
        val plane = SessionContract.classify(params)
        if (
            plane.kind == SessionPlaneKind.LAYER2_WORKSPACE ||
            !plane.workspaceId.isNullOrBlank() ||
            plane.workspaceGeneration != null
        ) {
            throw SessionIdentityException(
                "Layer 2 workspace ids cannot mix with a Session Kernel VD",
                SessionContract.PLANE_MISMATCH,
            )
        }
        if (
            plane.kind != SessionPlaneKind.SESSION_KERNEL_VD ||
            plane.sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID ||
            plane.displayId <= 0
        ) {
            throw SessionIdentityException(
                "Named Fast Path requires Session Kernel VD (sessionId != default-foreground, displayId > 0)",
                SessionContract.SESSION_DISPLAY_MISMATCH,
            )
        }
        return plane
    }

    fun attachIdentity(params: JSONObject, sessionId: String, displayId: Int): JSONObject {
        if (hasLayer2Ids(params)) {
            throw SessionIdentityException(
                "Layer 2 workspace ids cannot mix with a Session Kernel VD",
                SessionContract.PLANE_MISMATCH,
            )
        }
        if (sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID || displayId <= 0) {
            throw SessionIdentityException(
                "Named Fast Path cannot bind default-foreground or display 0",
                SessionContract.SESSION_DISPLAY_MISMATCH,
            )
        }
        return ExecutionRequestScope.attach(params, ExecutionContext(sessionId, displayId))
    }

    /**
     * Chrome search as multi-turn Fast Path (nav isolation: one screen-changing act per turn).
     * Turn 0: phone.open_app Chrome (landing).
     * Turn 1: phone.type SEARCH_QUERY (form batch) then phone.click Search (nav).
     */
    fun chromeSearchTurns(goal: String = ACCEPTANCE_GOAL): List<List<FastPathPlannedAction>> {
        val landing = FastPathLanding.resolve(goal)
        val openApp = FastPathPlannedAction(
            tool = landing?.tool ?: "phone.open_app",
            expectedPageChange = true,
        )
        return listOf(
            FastPathNavIsolation.keepPlanned(listOf(openApp)).allowed,
            FastPathNavIsolation.keepPlanned(
                listOf(
                    FastPathPlannedAction("phone.type", expectedPageChange = false),
                    FastPathPlannedAction("phone.click", expectedPageChange = true),
                ),
            ).allowed,
        )
    }

    fun runAcceptance(
        sessionId: String,
        displayId: Int,
        act: NamedWorkspaceActPort,
        observeFingerprint: (sessionId: String, displayId: Int) -> String?,
        sleepMs: (Long) -> Unit = {},
        nowMs: () -> Long,
        userPaused: Boolean = false,
        gateRequired: Boolean = false,
    ): NamedWorkspaceAcceptanceResult {
        val started = nowMs()
        val landing = FastPathLanding.resolve(ACCEPTANCE_GOAL)
        val landingTool = landing?.tool
        val landingPackage = landing?.packageName
        fun finish(
            ok: Boolean,
            planeKind: String,
            errorClass: String?,
            stepsRun: Int,
            secondClickSuppressed: Boolean = true,
        ): NamedWorkspaceAcceptanceResult {
            val elapsedMs = (nowMs() - started).coerceAtLeast(0L)
            val withinBudget = elapsedMs <= BUDGET_MS
            val accepted = ok && withinBudget
            return NamedWorkspaceAcceptanceResult(
                ok = accepted,
                sessionId = sessionId,
                displayId = displayId,
                planeKind = planeKind,
                elapsedMs = elapsedMs,
                withinBudget = withinBudget,
                usedLlm = false,
                usedVision = false,
                secondClickSuppressed = secondClickSuppressed,
                errorClass = when {
                    !ok -> errorClass
                    !withinBudget -> "BUDGET_EXCEEDED"
                    else -> null
                },
                stepsRun = stepsRun,
                landingTool = landingTool,
                landingPackage = landingPackage,
                artifact = artifact(
                    sessionId = sessionId,
                    displayId = displayId,
                    planeKind = planeKind,
                    elapsedMs = elapsedMs,
                    withinBudget = withinBudget,
                    landingTool = landingTool,
                    landingPackage = landingPackage,
                    stepsRun = stepsRun,
                    secondClickSuppressed = secondClickSuppressed,
                ),
            )
        }

        val plane = try {
            requireOwnedVd(sessionId, displayId)
        } catch (error: SessionIdentityException) {
            return finish(ok = false, planeKind = "", errorClass = error.errorClass, stepsRun = 0)
        }
        val control = NamedWorkspaceControlPolicy.mayMutate(
            sessionId = sessionId,
            displayId = displayId,
            userPaused = userPaused,
            gateRequired = gateRequired,
        )
        if (!control.allowMutate) {
            return finish(ok = false, planeKind = plane.wireKind, errorClass = control.errorClass, stepsRun = 0)
        }

        var stepsRun = 0
        var secondClickSuppressed = true
        for (turn in chromeSearchTurns()) {
            val isolated = FastPathNavIsolation.keepPlanned(turn)
            for (action in isolated.allowed) {
                val params = attachIdentity(paramsFor(action, landing), sessionId, displayId)
                val screenChanging = FastPathNavIsolation.isScreenChanging(action.tool, action.expectedPageChange)
                val before = if (screenChanging) observeFingerprint(sessionId, displayId) else null
                val outcome = act.act(action.tool, params, sessionId, displayId)
                stepsRun += 1
                if (outcome.gateRequired) {
                    return finish(false, plane.wireKind, "GATE_REQUIRED", stepsRun, secondClickSuppressed)
                }
                if (outcome.policyDenied) {
                    return finish(false, plane.wireKind, "POLICY_DENIED", stepsRun, secondClickSuppressed)
                }
                if (!outcome.ok) {
                    return finish(false, plane.wireKind, "ACT_FAILED", stepsRun, secondClickSuppressed)
                }
                if (!screenChanging) continue
                val settle = FastPathLoop.settle(
                    beforeFingerprint = before,
                    sleepMs = sleepMs,
                    observeFingerprint = { observeFingerprint(sessionId, displayId) },
                    nowMs = nowMs,
                )
                val retry = FastPathLoop.allowSecondClickChannel(actionPerformed = true, settle = settle)
                if (retry) secondClickSuppressed = false
                if (settle.changed != true || !settle.verified) {
                    return finish(
                        ok = false,
                        planeKind = plane.wireKind,
                        errorClass = "UNCHANGED",
                        stepsRun = stepsRun,
                        secondClickSuppressed = !retry,
                    )
                }
            }
        }
        return finish(
            ok = true,
            planeKind = plane.wireKind,
            errorClass = null,
            stepsRun = stepsRun,
            secondClickSuppressed = secondClickSuppressed,
        )
    }

    private fun paramsFor(action: FastPathPlannedAction, landing: FastPathLandingHint?): JSONObject {
        val params = JSONObject()
        when (action.tool) {
            "phone.open_app" -> params.put("package", landing?.packageName ?: CHROME_PACKAGE)
            "phone.launch_intent" -> landing?.uri?.let { params.put("uri", it) }
            "phone.type" -> params.put("value", SEARCH_QUERY)
            "phone.click" -> params.put("selector", JSONObject().put("text", "Search"))
        }
        return params
    }

    private fun hasLayer2Ids(params: JSONObject): Boolean {
        val workspaceId = params.opt("workspaceId")
        if (workspaceId is String && workspaceId.isNotBlank()) return true
        return params.has("workspaceGeneration") && params.opt("workspaceGeneration") != JSONObject.NULL
    }

    private fun artifact(
        sessionId: String,
        displayId: Int,
        planeKind: String,
        elapsedMs: Long,
        withinBudget: Boolean,
        landingTool: String?,
        landingPackage: String?,
        stepsRun: Int,
        secondClickSuppressed: Boolean,
    ): JSONObject = JSONObject()
        .put("scenario", "chrome-search-named-vd")
        .put("sessionId", sessionId)
        .put("displayId", displayId)
        .put("planeKind", planeKind)
        .put("goal", ACCEPTANCE_GOAL)
        .put("elapsedMs", elapsedMs)
        .put("budgetMs", BUDGET_MS)
        .put("withinBudget", withinBudget)
        .put("physicalPixel8", "UNVERIFIED")
        .put("secondClickSuppressed", secondClickSuppressed)
        .put("landingTool", landingTool ?: JSONObject.NULL)
        .put("landingPackage", landingPackage ?: JSONObject.NULL)
        .put("stepsRun", stepsRun)
}

fun interface NamedWorkspaceActPort {
    fun act(tool: String, params: JSONObject, sessionId: String, displayId: Int): NamedWorkspaceActOutcome
}

data class NamedWorkspaceActOutcome(
    val ok: Boolean,
    val afterFingerprint: String?,
    val gateRequired: Boolean = false,
    val policyDenied: Boolean = false,
)

data class NamedWorkspaceAcceptanceResult(
    val ok: Boolean,
    val sessionId: String,
    val displayId: Int,
    val planeKind: String,
    val elapsedMs: Long,
    val withinBudget: Boolean,
    val usedLlm: Boolean,
    val usedVision: Boolean,
    val secondClickSuppressed: Boolean,
    val errorClass: String?,
    val stepsRun: Int,
    val landingTool: String?,
    val landingPackage: String?,
    val artifact: JSONObject,
)
