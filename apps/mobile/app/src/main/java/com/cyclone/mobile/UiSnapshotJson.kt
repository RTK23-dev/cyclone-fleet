package com.cyclone.mobile

import org.json.JSONObject

/** Rehydrates Cyclone's normalized Accessibility snapshot without touching AccessibilityNodeInfo. */
fun uiSnapshotFromJson(json: JSONObject): UiSnapshot {
    fun bounds(obj: JSONObject?): UiBounds = UiBounds(
        obj?.optInt("left") ?: 0,
        obj?.optInt("top") ?: 0,
        obj?.optInt("right") ?: 0,
        obj?.optInt("bottom") ?: 0,
    )
    val windows = json.optJSONArray("windows")?.let { array ->
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { w ->
            UiWindowSnapshot(
                id = w.optInt("id"),
                title = w.optString("title"),
                type = w.optInt("type"),
                layer = w.optInt("layer"),
                active = w.optBoolean("active"),
                focused = w.optBoolean("focused"),
                bounds = bounds(w.optJSONObject("bounds")),
            )
        } }
    }.orEmpty()
    val nodes = json.optJSONArray("nodes")?.let { array ->
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { n ->
            val childIds = n.optJSONArray("childIds")?.let { ids ->
                (0 until ids.length()).mapNotNull { ids.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            val actions = n.optJSONArray("actions")?.let { acts ->
                (0 until acts.length()).mapNotNull { acts.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            UiNodeSnapshot(
                id = n.optString("id"),
                path = n.optString("path"),
                parentId = n.optString("parentId").takeIf { it.isNotBlank() },
                childIds = childIds,
                depth = n.optInt("depth"),
                windowId = n.optInt("windowId"),
                className = n.optString("class"),
                role = n.optString("role"),
                text = n.optString("text"),
                contentDescription = n.optString("contentDescription"),
                resourceId = n.optString("resourceId"),
                bounds = bounds(n.optJSONObject("bounds")),
                clickable = n.optBoolean("clickable"),
                longClickable = n.optBoolean("longClickable"),
                editable = n.optBoolean("editable"),
                scrollable = n.optBoolean("scrollable"),
                enabled = n.optBoolean("enabled", true),
                selected = n.optBoolean("selected"),
                checked = n.optBoolean("checked"),
                checkable = n.optBoolean("checkable"),
                focused = n.optBoolean("focused"),
                focusable = n.optBoolean("focusable"),
                visibleToUser = n.optBoolean("visibleToUser", true),
                actions = actions,
            )
        } }
    }.orEmpty()
    val screen = json.optJSONObject("screen")
    return UiSnapshot(
        packageName = json.optString("package").takeIf { it.isNotBlank() },
        className = json.optString("class").takeIf { it.isNotBlank() },
        screenWidth = screen?.optInt("width") ?: 0,
        screenHeight = screen?.optInt("height") ?: 0,
        timestampMs = json.optLong("timestampMs", System.currentTimeMillis()),
        fingerprint = json.optString("fingerprint"),
        controller = json.optString("controller"),
        windows = windows,
        nodes = nodes,
    )
}
