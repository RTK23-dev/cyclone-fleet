package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.ai.vision.live.LiveVisionRuntime
import com.cyclone.mobile.runtime.background.WorkspaceRuntime
import com.cyclone.mobile.runtime.session.ExecutionContext
import com.cyclone.mobile.runtime.session.ExecutionRequestScope
import com.cyclone.mobile.runtime.session.ExecutionSession
import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.runtime.session.SessionIdentityException
import com.cyclone.mobile.runtime.session.SessionPlane
import com.cyclone.mobile.runtime.session.SessionPlaneKind
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Trusted Cyclone session lifecycle adapter.
 *
 * Android remains authoritative for display allocation and execution. Desktop callers name a
 * session; the returned non-zero display identity must be carried back on every background
 * observation/action. No operation in this adapter silently substitutes display 0.
 */
internal object GatewaySessionAdapter {
    private val gatewayStates = ConcurrentHashMap<String, String>()

    fun list(context: Context): JSONObject {
        val sessions = JSONArray()
        LiveVisionRuntime.sessions.snapshot().forEach { session ->
            if (!session.isDefaultForeground && session.displayId <= 0) return@forEach
            sessions.put(sessionJson(context, session))
        }
        return JSONObject()
            .put("protocol", "cyclone.one.session.v1")
            .put("sessions", sessions)
    }

    fun start(context: Context, args: JSONObject): JSONObject {
        val packageName = args.optString("package").trim()
        if (packageName.isBlank()) {
            throw GatewayProtocolException("INVALID_REQUEST", "package is required")
        }
        val session = try {
            WorkspaceRuntime.create(context, packageName)
        } catch (error: Throwable) {
            throw workspaceFailure("start", error)
        }
        if (session.displayId <= 0 || session.isDefaultForeground) {
            runCatching { WorkspaceRuntime.close(session.sessionId) }
            throw GatewayProtocolException(
                "BACKGROUND_MODE_UNAVAILABLE",
                "Android did not allocate an isolated non-default execution display.",
            )
        }
        gatewayStates[session.sessionId] = "RUNNING"
        return sessionJson(context, session)
    }

    fun status(context: Context, args: JSONObject): JSONObject {
        val sessionId = requiredSessionId(args)
        val session = LiveVisionRuntime.sessions.snapshot().firstOrNull { it.sessionId == sessionId }
            ?: throw GatewayProtocolException("STALE_SESSION", "Execution session is no longer available")
        return sessionJson(context, session)
    }

    fun pause(context: Context, args: JSONObject): JSONObject {
        val session = requireBackground(args)
        try {
            WorkspaceRuntime.pause(session.sessionId)
        } catch (error: Throwable) {
            throw workspaceFailure("pause", error)
        }
        gatewayStates[session.sessionId] = "PAUSED"
        return sessionJson(context, session)
    }

    fun resume(context: Context, args: JSONObject): JSONObject {
        val session = requireBackground(args)
        try {
            WorkspaceRuntime.resume(session.sessionId)
        } catch (error: Throwable) {
            throw workspaceFailure("resume", error)
        }
        gatewayStates[session.sessionId] = "RUNNING"
        return sessionJson(context, session)
    }

    fun handoff(context: Context, args: JSONObject): JSONObject {
        val session = requireBackground(args)
        try {
            WorkspaceRuntime.handoff(session.sessionId)
        } catch (error: Throwable) {
            throw workspaceFailure("handoff", error)
        }
        gatewayStates[session.sessionId] = "WAITING_FOR_CONFIRMATION"
        return sessionJson(context, session)
    }

    fun stop(args: JSONObject): JSONObject {
        val session = requireBackground(args)
        try {
            WorkspaceRuntime.close(session.sessionId)
        } catch (error: Throwable) {
            throw workspaceFailure("stop", error)
        }
        gatewayStates.remove(session.sessionId)
        return JSONObject()
            .put("protocol", "cyclone.one.session.v1")
            .put("sessionId", session.sessionId)
            .put("displayId", session.displayId)
            .put("state", "STOPPED")
    }

    /** One exact-session frame. Background frames come only from that workspace's live source. */
    fun snapshot(context: Context, args: JSONObject): JSONObject {
        failClosedNamedWorkspaceOnDisplayZero(args)
        val plane = try {
            val merged = ExecutionRequestScope.merge(args, args.optJSONObject("params") ?: JSONObject())
            if (args.has("workspaceId") && !merged.has("workspaceId")) merged.put("workspaceId", args.get("workspaceId"))
            if (args.has("workspaceGeneration") && !merged.has("workspaceGeneration")) {
                merged.put("workspaceGeneration", args.get("workspaceGeneration"))
            }
            SessionContract.classify(merged)
        } catch (error: SessionIdentityException) {
            throw namedWorkspaceDisplayZeroOr(error, args)
        } catch (error: IllegalArgumentException) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", error.message ?: "Invalid execution context")
        }
        val execution = ExecutionContext(plane.sessionId, plane.displayId)
        if (execution.sessionId != ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID && execution.displayId <= 0) {
            throw GatewayProtocolException(
                "PROTOCOL_MISMATCH",
                "Named workspace displayId must be > 0; display 0 is reserved for default-foreground / Layer 2",
            )
        }
        if (execution.sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID) {
            if (execution.displayId != 0) {
                throw GatewayProtocolException("PROTOCOL_MISMATCH", "Foreground session must use display 0")
            }
            return GatewayCaptureAdapter.capture(context, args)
        }
        val session = try {
            LiveVisionRuntime.sessions.requireSessionDisplay(execution.sessionId, execution.displayId)
        } catch (error: Throwable) {
            throw GatewayProtocolException("STALE_SESSION", error.message ?: "Session/display identity is invalid")
        }
        if (!session.executable || session.displayId <= 0) {
            throw GatewayProtocolException("BACKGROUND_MODE_UNAVAILABLE", "Requested session is not an executable background workspace")
        }
        val params = JSONObject(args.toString()).put("includeBase64", true)
        val result = PhoneToolExecutor.execute(
            context,
            PhoneToolRequest("pc-session-frame-${UUID.randomUUID()}", "phone.screenshot", params),
        )
        if (!result.ok) {
            throw GatewayProtocolException(
                result.error?.code?.name ?: "CAPABILITY_UNAVAILABLE",
                result.error?.message ?: "Exact-session frame is unavailable",
            )
        }
        val payload = result.payload as? JSONObject
            ?: throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Exact-session frame returned no image payload")
        val snapshot = JSONObject(payload.toString())
            .put("sessionId", execution.sessionId)
            .put("displayId", execution.displayId)
            .put("foregroundSubstitution", false)
        return SessionContract.attach(
            snapshot,
            SessionPlane(SessionPlaneKind.SESSION_KERNEL_VD, execution.sessionId, execution.displayId),
        )
    }

    private fun sessionJson(context: Context, session: ExecutionSession): JSONObject {
        val foreground = session.isDefaultForeground
        val running = if (foreground) true else runCatching { WorkspaceRuntime.ownsInput(session.sessionId) }.getOrDefault(false)
        val state = when {
            foreground -> "FOREGROUND"
            running -> "RUNNING"
            gatewayStates[session.sessionId] != null -> gatewayStates.getValue(session.sessionId)
            LiveVisionRuntime.healthy(session.sessionId) -> "PAUSED"
            else -> "ATTENTION"
        }
        val generation = if (foreground) null else runCatching { WorkspaceRuntime.generation(session.sessionId) }.getOrNull()
        return JSONObject()
            .put("protocol", "cyclone.one.session.v1")
            .put("sessionId", session.sessionId)
            .put("displayId", session.displayId)
            .put("targetPackage", session.targetPackage ?: JSONObject.NULL)
            .put("backend", session.backend.name)
            .put("inputOwner", session.inputOwner.name)
            .put("state", state)
            .put("executable", session.executable)
            .put("createdAtEpochMs", session.createdAtEpochMs)
            .put("executionGeneration", generation ?: JSONObject.NULL)
            .put("frameHealthy", if (foreground) JSONObject.NULL else LiveVisionRuntime.healthy(session.sessionId))
            .put("appPackage", context.packageName)
            .put("plane", SessionPlane(
                if (foreground) SessionPlaneKind.FOREGROUND else SessionPlaneKind.SESSION_KERNEL_VD,
                session.sessionId, session.displayId
            ).toJson())
    }

    /**
     * Named / owned workspaces must never bind display 0. Session Contract classify reports
     * SESSION_DISPLAY_MISMATCH internally; the gateway wire code for this hole is PROTOCOL_MISMATCH.
     */
    private fun failClosedNamedWorkspaceOnDisplayZero(args: JSONObject) {
        val sessionId = requestedSessionId(args)
        if (sessionId.isBlank() || sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID) return
        val displayId = requestedDisplayId(args) ?: return
        if (displayId <= 0) {
            throw GatewayProtocolException(
                "PROTOCOL_MISMATCH",
                "Named workspace displayId must be > 0; display 0 is reserved for default-foreground / Layer 2",
            )
        }
    }

    private fun namedWorkspaceDisplayZeroOr(
        error: SessionIdentityException,
        args: JSONObject,
    ): GatewayProtocolException {
        val sessionId = requestedSessionId(args)
        val displayId = requestedDisplayId(args)
        val namedOnZero = sessionId.isNotBlank() &&
            sessionId != ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID &&
            displayId != null &&
            displayId <= 0
        val code = if (namedOnZero || error.errorClass == SessionContract.SESSION_DISPLAY_MISMATCH) {
            "PROTOCOL_MISMATCH"
        } else {
            error.errorClass
        }
        return GatewayProtocolException(code, error.message ?: "Invalid execution context")
    }

    private fun requestedSessionId(args: JSONObject): String {
        fun read(json: JSONObject?): String {
            if (json == null) return ""
            val camel = json.optString("sessionId").trim()
            if (camel.isNotBlank()) return camel
            return json.optString("session_id").trim()
        }
        read(args).takeIf { it.isNotBlank() }?.let { return it }
        read(args.optJSONObject("executionContext")).takeIf { it.isNotBlank() }?.let { return it }
        return read(args.optJSONObject("params"))
    }

    private fun requestedDisplayId(args: JSONObject): Int? {
        fun read(json: JSONObject?): Int? {
            if (json == null) return null
            if (json.has("displayId")) return json.optInt("displayId")
            if (json.has("display_id")) return json.optInt("display_id")
            return null
        }
        return read(args) ?: read(args.optJSONObject("executionContext")) ?: read(args.optJSONObject("params"))
    }

    private fun requiredSessionId(args: JSONObject): String {
        val direct = args.optString("sessionId").trim()
        val nested = args.optJSONObject("executionContext")?.optString("sessionId")?.trim().orEmpty()
        if (direct.isNotBlank() && nested.isNotBlank() && direct != nested) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "Conflicting session identities")
        }
        return (direct.ifBlank { nested }).ifBlank {
            throw GatewayProtocolException("INVALID_REQUEST", "sessionId is required")
        }
    }

    private fun requireBackground(args: JSONObject): ExecutionSession {
        val sessionId = requiredSessionId(args)
        if (sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "The default foreground session cannot be lifecycle-managed")
        }
        return LiveVisionRuntime.sessions.snapshot().firstOrNull { it.sessionId == sessionId }
            ?: throw GatewayProtocolException("STALE_SESSION", "Execution session is no longer available")
    }

    private fun workspaceFailure(operation: String, error: Throwable): GatewayProtocolException {
        val message = (error.message ?: "$operation failed").take(240)
        val code = when {
            "SCREEN_LOCKED" in message -> "PHONE_LOCKED"
            "STALE_SESSION" in message -> "STALE_SESSION"
            "POLICY_DENIED" in message -> "POLICY_DENIED"
            "FOREGROUND_REQUIRED" in message -> "FOREGROUND_REQUIRED"
            "Shizuku" in message || "BACKGROUND_MODE_UNAVAILABLE" in message -> "BACKGROUND_MODE_UNAVAILABLE"
            else -> "CAPABILITY_UNAVAILABLE"
        }
        return GatewayProtocolException(code, message)
    }
}
