package com.cyclone.mobile.fastpath

import com.cyclone.mobile.AccessibilityRoles
import com.cyclone.mobile.UiNodeSnapshot
import org.json.JSONArray
import org.json.JSONObject

data class FastPathIndexedElement(
    val elementIndex: Int,
    val nodeId: String,
    val role: String,
    val label: String,
    val clickable: Boolean,
    val editable: Boolean,
)

/**
 * A11y-first observation helpers. Stable 1-based indices are observation-scoped: they die on the
 * next mutation the same way elementIds do. Vision is an escalate, never the primary loop.
 */
object FastPathTree {
    const val MIN_USEFUL_CONTROLS = 1
    val INTERACTIVE_ROLES = setOf(
        "button", "tab", "switch", "checkbox", "edit_text", "textbox", "row",
    )

    fun isInteractive(node: UiNodeSnapshot): Boolean {
        val interactive = node.clickable || node.longClickable || node.editable ||
            node.scrollable || node.checkable || node.role.lowercase() in INTERACTIVE_ROLES
        if (!interactive) return false
        return AccessibilityRoles.isPublishedInteractive(
            node.visibleToUser,
            true,
            node.bounds.width,
            node.bounds.height,
        )
    }

    fun indexInteractive(nodes: List<UiNodeSnapshot>): List<FastPathIndexedElement> {
        var index = 0
        return nodes.mapNotNull { node ->
            if (!isInteractive(node)) return@mapNotNull null
            index += 1
            FastPathIndexedElement(
                elementIndex = index,
                nodeId = node.id,
                role = node.role,
                label = node.text.ifBlank { node.contentDescription }.ifBlank { node.resourceId.substringAfterLast('/') },
                clickable = node.clickable,
                editable = node.editable,
            )
        }
    }

    fun treeUseful(rawNodeCount: Int, indexedControlCount: Int): Boolean {
        if (indexedControlCount >= MIN_USEFUL_CONTROLS) return true
        // Empty tree or custom canvas: nodes may exist but nothing the agent can act on.
        return false
    }

    fun perceptionMode(rawNodeCount: Int, indexedControlCount: Int): String =
        if (treeUseful(rawNodeCount, indexedControlCount)) "a11y" else "vision_escalate"

    fun assignControlIndices(controls: JSONArray): Int {
        var index = 0
        for (i in 0 until controls.length()) {
            val evidence = controls.optJSONObject(i) ?: continue
            index += 1
            evidence.put("elementIndex", index)
            evidence.put("element_index", index)
        }
        return index
    }

    fun lookupElementId(elements: Map<String, JSONObject>, elementIndex: Int): String? {
        if (elementIndex < 1) return null
        return elements.entries.firstOrNull { (_, evidence) ->
            evidence.optInt("elementIndex", evidence.optInt("element_index", -1)) == elementIndex
        }?.key
    }

    fun lookupElementIdFromEvidence(evidences: Iterable<JSONObject>, elementIndex: Int): String? {
        if (elementIndex < 1) return null
        return evidences.firstOrNull { evidence ->
            evidence.optInt("elementIndex", evidence.optInt("element_index", -1)) == elementIndex
        }?.optString("elementId")?.takeIf { it.isNotBlank() }
    }
}
