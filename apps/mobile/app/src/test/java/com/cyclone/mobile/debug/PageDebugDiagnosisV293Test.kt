package com.cyclone.mobile.debug

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.PageControl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PageDebugDiagnosisV293Test {
    @Test
    fun `classifies missing raw target as accessibility perception`() {
        val diagnosis = PageDebugDiagnosisV293.diagnose(
            "Saved items",
            snapshot(node("Home", clickable = true)),
            page(emptyList()),
            agent(emptyList()),
        )
        assertEquals("ACCESSIBILITY_PERCEPTION", diagnosis.getString("stage"))
    }

    @Test
    fun `classifies raw target lost before PageContext as semanticization loss`() {
        val diagnosis = PageDebugDiagnosisV293.diagnose(
            "Saved items",
            snapshot(node("Saved items", clickable = true)),
            page(emptyList()),
            agent(emptyList()),
        )
        assertEquals("SEMANTICIZATION_LOSS", diagnosis.getString("stage"))
    }

    @Test
    fun `classifies semantic target omitted from agent payload as context truncation`() {
        val target = control("saved", "Saved items")
        val diagnosis = PageDebugDiagnosisV293.diagnose(
            "Saved items",
            snapshot(node("Saved items", clickable = true)),
            page(listOf(target)),
            agent(emptyList()),
        )
        assertEquals("AGENT_CONTEXT_TRUNCATION", diagnosis.getString("stage"))
    }

    @Test
    fun `classifies target reaching agent as reasoning or memory`() {
        val target = control("saved", "Saved items")
        val diagnosis = PageDebugDiagnosisV293.diagnose(
            "Saved items",
            snapshot(node("Saved items", clickable = true)),
            page(listOf(target)),
            agent(listOf(target)),
        )
        assertEquals("AGENT_REASONING_OR_MEMORY", diagnosis.getString("stage"))
    }

    private fun snapshot(vararg nodes: JSONObject): JSONObject = JSONObject()
        .put("package", "example.app")
        .put("class", "ExampleActivity")
        .put("nodes", JSONArray().also { array -> nodes.forEach(array::put) })

    private fun node(text: String, clickable: Boolean): JSONObject = JSONObject()
        .put("visibleToUser", true)
        .put("text", text)
        .put("contentDescription", "")
        .put("resourceId", "")
        .put("role", if (clickable) "button" else "text")
        .put("class", if (clickable) "android.widget.Button" else "android.widget.TextView")
        .put("path", "0/0")
        .put("clickable", clickable)

    private fun control(id: String, label: String): PageControl = PageControl(
        key = id,
        label = label,
        semanticName = label.lowercase().replace(' ', '_'),
        role = "button",
        selector = JSONObject().put("text", label),
        androidActions = listOf("ACTION_CLICK"),
        risk = ActionRisk.SAFE,
    )

    private fun page(controls: List<PageControl>): PageContext = PageContext(
        pageKey = "example:key",
        packageName = "example.app",
        className = "ExampleActivity",
        title = "Example",
        structuralKey = "structure",
        contentKey = "content",
        controls = controls,
        observationCount = 1,
        firstSeenAt = 1,
        lastSeenAt = 1,
    )

    private fun agent(controls: List<PageControl>): JSONObject = JSONObject().put(
        "CURRENT_PAGE",
        JSONObject().put("controls", JSONArray().also { array -> controls.forEach { control ->
            array.put(JSONObject()
                .put("id", control.key)
                .put("label", control.label)
                .put("semanticName", control.semanticName)
                .put("selector", control.selector))
        } }),
    )
}
