package com.cyclone.mobile.runtime.session

import org.json.JSONObject

/** Transport identity is never inferred from the active Android window. */
object ExecutionRequestScope {
    fun read(params: JSONObject): ExecutionContext = bind(params)

    fun bind(params: JSONObject): ExecutionContext {
        if (params.has("executionContext") && params.opt("executionContext") !is JSONObject) {
            throw SessionIdentityException("Invalid executionContext")
        }
        val scoped = JSONObject(params.toString())
        copyWireAliases(scoped)
        scoped.optJSONObject("executionContext")?.let(::copyWireAliases)
        val nested = scoped.optJSONObject("executionContext")
        val session = string(scoped, "sessionId")
        val display = integer(scoped, "displayId")
        val nestedSession = nested?.let { string(it, "sessionId") }
        val nestedDisplay = nested?.let { integer(it, "displayId") }
        if (session != null && nestedSession != null && session != nestedSession) {
            throw SessionIdentityException("Conflicting execution session identities")
        }
        if (display != null && nestedDisplay != null && display != nestedDisplay) {
            throw SessionIdentityException("Conflicting execution display identities")
        }
        val id = session ?: nestedSession ?: ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID
        val targetDisplay = display ?: nestedDisplay
        if (id == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID) {
            if (targetDisplay != null && targetDisplay != ExecutionSession.DEFAULT_DISPLAY_ID) {
                throw SessionIdentityException("default-foreground cannot target a nonzero display")
            }
            return ExecutionContext(id, ExecutionSession.DEFAULT_DISPLAY_ID)
        }
        if (targetDisplay == null) {
            throw SessionIdentityException("An explicit background session requires its displayId")
        }
        if (targetDisplay <= 0) {
            throw SessionIdentityException("Background session requires a nonzero displayId")
        }
        return ExecutionContext(id, targetDisplay)
    }

    /** Used at legacy boundaries until a complete display-scoped implementation owns the call. */
    fun requireForeground(params: JSONObject): ExecutionContext = bind(params).also {
        if (it.sessionId != ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID || it.displayId != 0) {
            throw SessionIdentityException("BACKGROUND_MODE_UNAVAILABLE: this operation cannot address the requested workspace")
        }
    }

    fun requireUi(params: JSONObject): ExecutionContext = SessionContract.requireUi(params).let {
        ExecutionContext(it.sessionId, it.displayId)
    }

    /** Carry outer identity through adapters instead of dropping it while normalizing params. */
    fun merge(envelope: JSONObject, params: JSONObject): JSONObject {
        val out = JSONObject(params.toString())
        for (key in listOf("sessionId", "displayId", "executionContext", "session_id", "display_id")) {
            if (!envelope.has(key)) continue
            if (out.has(key) && out.get(key).toString() != envelope.get(key).toString()) {
                throw SessionIdentityException("Conflicting $key in request envelope and params")
            }
            out.put(key, envelope.get(key))
        }
        copyWireAliases(out)
        out.optJSONObject("executionContext")?.let(::copyWireAliases)
        return out
    }

    /** Write sessionId+displayId onto params without dropping other keys. */
    fun attach(params: JSONObject, context: ExecutionContext): JSONObject {
        val out = JSONObject(params.toString())
        putIdentity(out, "sessionId", context.sessionId)
        putIdentity(out, "displayId", context.displayId)
        return out
    }

    private fun putIdentity(out: JSONObject, key: String, value: Any) {
        if (out.has(key) && out.get(key).toString() != value.toString()) {
            throw SessionIdentityException("Conflicting $key in params and execution context")
        }
        out.put(key, value)
    }

    internal fun copyWireAliases(json: JSONObject) {
        copyAlias(json, "session_id", "sessionId")
        copyAlias(json, "display_id", "displayId")
    }

    private fun copyAlias(json: JSONObject, snake: String, camel: String) {
        if (!json.has(snake)) return
        if (json.has(camel)) {
            if (json.get(camel).toString() != json.get(snake).toString()) {
                throw SessionIdentityException("Conflicting $camel aliases")
            }
            return
        }
        json.put(camel, json.get(snake))
    }

    private fun string(json: JSONObject, key: String): String? {
        if (!json.has(key)) return null
        val value = json.opt(key)
        if (value !is String || value.isBlank() || value != value.trim()) {
            throw SessionIdentityException("Invalid $key")
        }
        return value
    }

    private fun integer(json: JSONObject, key: String): Int? {
        if (!json.has(key)) return null
        val value = json.opt(key)
        if (value !is Number || value.toDouble() != value.toInt().toDouble() || value.toInt() < 0) {
            throw SessionIdentityException("Invalid $key")
        }
        return value.toInt()
    }
}
