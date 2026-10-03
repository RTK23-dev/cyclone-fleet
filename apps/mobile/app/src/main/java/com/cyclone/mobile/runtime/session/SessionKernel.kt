package com.cyclone.mobile.runtime.session

import org.json.JSONObject

/** Display-scoped session identity. Product hot-gate is 1; the store/API can hold N sessions. */
object SessionKernel {
    const val PRODUCT_HOT_BACKGROUND_LIMIT = 1
    const val API_SESSION_CAPACITY = 8

    fun switchWorkspace(context: android.content.Context, id: String): com.cyclone.mobile.PhoneToolResult =
        com.cyclone.mobile.PhoneToolExecutor.execute(context, com.cyclone.mobile.PhoneToolRequest(
            "workspace-switch-${System.nanoTime()}", "workspace.switch", JSONObject().put("id", id)))

    fun bind(params: JSONObject): ExecutionContext = ExecutionRequestScope.bind(params)
    fun attach(params: JSONObject, context: ExecutionContext): JSONObject =
        ExecutionRequestScope.attach(params, context)
    fun snapshot(store: ExecutionSessionStore): List<ExecutionSession> = store.snapshot()
    fun list(store: ExecutionSessionStore): List<ExecutionSession> = store.snapshot()
}
