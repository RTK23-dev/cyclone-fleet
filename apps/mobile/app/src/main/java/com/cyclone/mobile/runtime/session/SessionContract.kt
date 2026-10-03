package com.cyclone.mobile.runtime.session

import org.json.JSONObject

enum class SessionPlaneKind {
    FOREGROUND,
    SESSION_KERNEL_VD,
    LAYER2_WORKSPACE,
}

data class SessionPlane(
    val kind: SessionPlaneKind,
    val sessionId: String,
    val displayId: Int,
    val workspaceId: String? = null,
    val workspaceGeneration: Long? = null,
) {
    val wireKind: String
        get() = when (kind) {
            SessionPlaneKind.FOREGROUND -> "foreground"
            SessionPlaneKind.SESSION_KERNEL_VD -> "session_kernel_vd"
            SessionPlaneKind.LAYER2_WORKSPACE -> "layer2_workspace"
        }
    val label: String
        get() = when (kind) {
            SessionPlaneKind.FOREGROUND -> "Foreground"
            SessionPlaneKind.SESSION_KERNEL_VD -> "Session Kernel VD"
            SessionPlaneKind.LAYER2_WORKSPACE -> "Layer 2 workspace"
        }
    fun toJson(): JSONObject = JSONObject()
        .put("kind", wireKind)
        .put("sessionId", sessionId)
        .put("displayId", displayId)
        .put("workspaceId", workspaceId ?: JSONObject.NULL)
        .put("workspaceGeneration", workspaceGeneration ?: JSONObject.NULL)
        .put("label", label)
}

object SessionContract {
    const val SESSION_REQUIRED = "SESSION_REQUIRED"
    const val SESSION_DISPLAY_MISMATCH = "SESSION_DISPLAY_MISMATCH"
    const val PLANE_MISMATCH = "PLANE_MISMATCH"
    const val WORKSPACE_GENERATION_REQUIRED = "WORKSPACE_GENERATION_REQUIRED"
    const val WORKSPACE_GENERATION_STALE = "WORKSPACE_GENERATION_STALE"

    private val WORKSPACE_ID = Regex("[A-Za-z0-9_-]{1,80}")

    /** Internal/legacy: omitted session binds default-foreground. Still fail-closes illegal mixes. */
    fun classify(params: JSONObject): SessionPlane {
        val scoped = withSessionAlias(params)
        val bound = try {
            ExecutionRequestScope.bind(scoped)
        } catch (error: SessionIdentityException) {
            throw wrapBindFailure(error)
        }
        val nested = scoped.optJSONObject("executionContext")
        val workspaceId = agree("workspaceId", readWorkspaceId(scoped), nested?.let { readWorkspaceId(it) })
        val workspaceGeneration = agree("workspaceGeneration", readGeneration(scoped), nested?.let { readGeneration(it) })
        val hasWorkspaceId = workspaceId != null
        val hasGeneration = workspaceGeneration != null
        if (hasWorkspaceId != hasGeneration) {
            throw SessionIdentityException(
                "workspaceId and workspaceGeneration must be supplied together",
                WORKSPACE_GENERATION_REQUIRED,
            )
        }
        if (hasWorkspaceId && (bound.sessionId != ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID || bound.displayId != 0)) {
            throw SessionIdentityException(
                "Layer 2 workspaceId cannot mix with a Session Kernel VD; Layer 2 stays default-foreground / display 0",
                PLANE_MISMATCH,
            )
        }
        return when {
            hasWorkspaceId -> SessionPlane(
                SessionPlaneKind.LAYER2_WORKSPACE,
                bound.sessionId,
                bound.displayId,
                workspaceId,
                workspaceGeneration,
            )
            bound.sessionId != ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID ->
                SessionPlane(SessionPlaneKind.SESSION_KERNEL_VD, bound.sessionId, bound.displayId)
            else -> SessionPlane(SessionPlaneKind.FOREGROUND, bound.sessionId, bound.displayId)
        }
    }

    /** UI observe/act: missing sessionId → SESSION_REQUIRED. Never invent default-foreground. */
    fun requireUi(params: JSONObject): SessionPlane {
        if (!hasUiSession(params)) {
            throw SessionIdentityException(
                "session_id is required. Pass default-foreground for the live human display, or a named workspace session with display_id > 0.",
                SESSION_REQUIRED,
            )
        }
        return classify(params)
    }

    fun attach(payload: JSONObject, plane: SessionPlane): JSONObject {
        val out = JSONObject(payload.toString())
        out.put("plane", plane.toJson())
        out.put("sessionId", plane.sessionId)
        out.put("displayId", plane.displayId)
        return out
    }

    private fun hasUiSession(params: JSONObject): Boolean =
        nonBlankSession(params) || params.optJSONObject("executionContext")?.let(::nonBlankSession) == true

    private fun nonBlankSession(json: JSONObject): Boolean {
        val sessionId = json.opt("sessionId")
        val alias = json.opt("session_id")
        return (sessionId is String && sessionId.isNotBlank()) || (alias is String && alias.isNotBlank())
    }

    private fun withSessionAlias(params: JSONObject): JSONObject {
        val out = JSONObject(params.toString())
        copySessionAlias(out)
        copyDisplayAlias(out)
        out.optJSONObject("executionContext")?.let { nested ->
            copySessionAlias(nested)
            copyDisplayAlias(nested)
        }
        return out
    }

    private fun copySessionAlias(json: JSONObject) {
        if (!json.has("session_id")) return
        if (json.has("sessionId")) {
            if (json.get("sessionId").toString() != json.get("session_id").toString()) {
                throw SessionIdentityException("Conflicting sessionId aliases", SESSION_DISPLAY_MISMATCH)
            }
            return
        }
        json.put("sessionId", json.get("session_id"))
    }

    private fun copyDisplayAlias(json: JSONObject) {
        if (!json.has("display_id")) return
        if (json.has("displayId")) {
            if (json.get("displayId").toString() != json.get("display_id").toString()) {
                throw SessionIdentityException("Conflicting displayId aliases", SESSION_DISPLAY_MISMATCH)
            }
            return
        }
        json.put("displayId", json.get("display_id"))
    }

    private fun wrapBindFailure(error: SessionIdentityException): SessionIdentityException {
        val message = error.message.orEmpty()
        val lowered = message.lowercase()
        val errorClass = if (
            "default-foreground" in lowered ||
            "display" in lowered ||
            "background" in lowered
        ) SESSION_DISPLAY_MISMATCH else SESSION_REQUIRED
        return SessionIdentityException(message, errorClass)
    }

    private fun <T> agree(key: String, left: T?, right: T?): T? {
        if (left != null && right != null && left != right) {
            throw SessionIdentityException("Conflicting $key", PLANE_MISMATCH)
        }
        return left ?: right
    }

    private fun readWorkspaceId(json: JSONObject): String? {
        if (!json.has("workspaceId")) return null
        val value = json.opt("workspaceId")
        if (value == null || value == JSONObject.NULL) return null
        if (value !is String) throw SessionIdentityException("Invalid workspaceId", PLANE_MISMATCH)
        if (value.isBlank()) return null
        if (!value.matches(WORKSPACE_ID)) throw SessionIdentityException("Invalid workspaceId", PLANE_MISMATCH)
        return value
    }

    private fun readGeneration(json: JSONObject): Long? {
        if (!json.has("workspaceGeneration")) return null
        val value = json.opt("workspaceGeneration")
        if (value !is Number || value.toDouble() != value.toLong().toDouble() || value.toLong() < 0L) {
            throw SessionIdentityException("Invalid workspaceGeneration", PLANE_MISMATCH)
        }
        return value.toLong()
    }
}
