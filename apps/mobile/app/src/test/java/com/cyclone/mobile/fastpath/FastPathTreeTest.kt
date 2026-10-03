package com.cyclone.mobile.fastpath

import com.cyclone.mobile.UiBounds
import com.cyclone.mobile.UiNodeSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPathTreeTest {
    @Test
    fun indexesInteractiveNodesStablyAndSkipsDecorations() {
        val nodes = listOf(
            node("chrome", "status", role = "text", clickable = false),
            node("apps", "Apps", role = "button", clickable = true),
            node("search", "Search", role = "textbox", editable = true),
            node("hidden", "Ghost", role = "button", clickable = true, visible = false, width = 0, height = 0),
        )
        val indexed = FastPathTree.indexInteractive(nodes)
        assertEquals(listOf(1, 2), indexed.map { it.elementIndex })
        assertEquals(listOf("apps", "search"), indexed.map { it.nodeId })
        assertEquals("a11y", FastPathTree.perceptionMode(nodes.size, indexed.size))
        assertTrue(FastPathTree.treeUseful(nodes.size, indexed.size))
    }

    @Test
    fun emptyOrCanvasTreeEscalatesToVision() {
        assertFalse(FastPathTree.treeUseful(rawNodeCount = 0, indexedControlCount = 0))
        assertFalse(FastPathTree.treeUseful(rawNodeCount = 40, indexedControlCount = 0))
        assertEquals("vision_escalate", FastPathTree.perceptionMode(40, 0))
        assertTrue(FastPathTree.treeUseful(8, 1))
    }

    @Test
    fun assignControlIndicesIsOneBasedAndLookupRoundTrips() {
        val controls = JSONArray()
            .put(JSONObject().put("elementId", "semantic:obs:apps").put("label", "Apps"))
            .put(JSONObject().put("elementId", "semantic:obs:network").put("label", "Network"))
        assertEquals(2, FastPathTree.assignControlIndices(controls))
        assertEquals(1, controls.getJSONObject(0).optInt("elementIndex"))
        assertEquals(2, controls.getJSONObject(1).optInt("element_index"))
        val byId = mapOf(
            "semantic:obs:apps" to controls.getJSONObject(0),
            "semantic:obs:network" to controls.getJSONObject(1),
        )
        assertEquals("semantic:obs:network", FastPathTree.lookupElementId(byId, 2))
        assertEquals("semantic:obs:apps", FastPathTree.lookupElementIdFromEvidence(byId.values, 1))
        assertEquals(null, FastPathTree.lookupElementId(byId, 0))
    }

    private fun node(
        id: String,
        text: String,
        role: String,
        clickable: Boolean = false,
        editable: Boolean = false,
        visible: Boolean = true,
        width: Int = 80,
        height: Int = 40,
    ) = UiNodeSnapshot(
        id = id,
        path = id,
        parentId = null,
        childIds = emptyList(),
        depth = 1,
        windowId = 0,
        className = "android.widget.Button",
        role = role,
        text = text,
        contentDescription = "",
        resourceId = "",
        bounds = UiBounds(0, 0, width, height),
        clickable = clickable,
        longClickable = false,
        editable = editable,
        scrollable = false,
        enabled = true,
        selected = false,
        checked = false,
        checkable = false,
        focused = false,
        focusable = false,
        visibleToUser = visible,
        actions = if (clickable) listOf("ACTION_CLICK") else emptyList(),
    )
}
