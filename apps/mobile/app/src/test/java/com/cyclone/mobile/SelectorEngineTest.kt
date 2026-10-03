package com.cyclone.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorEngineTest {
    private fun node(
        id: String,
        path: String,
        parent: String?,
        children: List<String> = emptyList(),
        text: String = "",
        description: String = "",
        resourceId: String = "",
        role: String = "generic",
        clickable: Boolean = false,
        editable: Boolean = false,
        bounds: UiBounds = UiBounds(0, 0, 100, 100),
    ) = UiNodeSnapshot(
        id = id,
        path = path,
        parentId = parent,
        childIds = children,
        depth = path.count { it == '/' },
        windowId = 1,
        className = if (role == "button") "android.widget.Button" else "android.view.View",
        role = role,
        text = text,
        contentDescription = description,
        resourceId = resourceId,
        bounds = bounds,
        clickable = clickable,
        longClickable = false,
        editable = editable,
        scrollable = false,
        enabled = true,
        selected = false,
        checked = false,
        checkable = false,
        focused = false,
        focusable = clickable || editable,
        visibleToUser = true,
    )

    private fun snapshot(nodes: List<UiNodeSnapshot>) = UiSnapshot(
        packageName = "com.test",
        className = "TestActivity",
        screenWidth = 1080,
        screenHeight = 2400,
        timestampMs = 1,
        fingerprint = "abc",
        controller = "agent",
        windows = emptyList(),
        nodes = nodes,
    )

    @Test
    fun resourceIdWinsDeterministically() {
        val nodes = listOf(
            node("a", "0/0", null, text = "Claim", resourceId = "wrong", clickable = true),
            node("b", "0/1", null, text = "Claim", resourceId = "com.test:id/claim", clickable = true),
        )
        val result = SelectorEngine.resolve(snapshot(nodes), ElementSelector(resourceId = "com.test:id/claim"))
        assertEquals("b", result.first().node.id)
    }

    @Test
    fun ancestorAndDescendantSelectorsWork() {
        val parent = node("p", "0/0", null, children = listOf("c"), text = "Open shift")
        val child = node("c", "0/0/0", "p", text = "Claim", role = "button", clickable = true)
        val snap = snapshot(listOf(parent, child))

        val childMatch = SelectorEngine.resolve(snap, ElementSelector(text = "Claim", ancestorText = "Open shift"))
        assertEquals("c", childMatch.first().node.id)

        val parentMatch = SelectorEngine.resolve(snap, ElementSelector(text = "Open shift", descendantText = "Claim"))
        assertEquals("p", parentMatch.first().node.id)
    }

    @Test
    fun coordinatesPreferContainingNode() {
        val nodes = listOf(
            node("left", "0/0", null, bounds = UiBounds(0, 0, 100, 100)),
            node("right", "0/1", null, bounds = UiBounds(200, 0, 300, 100)),
        )
        val result = SelectorEngine.resolve(snapshot(nodes), ElementSelector(x = 250, y = 50))
        assertEquals("right", result.first().node.id)
    }

    @Test
    fun fuzzyTextToleratesSmallVariation() {
        val nodes = listOf(node("claim", "0/0", null, text = "Claim available shift", clickable = true))
        val result = SelectorEngine.resolve(snapshot(nodes), ElementSelector(fuzzyText = "claim availble shift", minFuzzyScore = 0.65))
        assertTrue(result.isNotEmpty())
        assertEquals("claim", result.first().node.id)
    }

    @Test
    fun textSelectorMatchesTalkBackContentDescription() {
        val nodes = listOf(
            node(
                "up", "0/0", null, text = "", description = "Navigate up",
                role = "button", clickable = true,
            ),
        )
        val result = SelectorEngine.resolve(snapshot(nodes), ElementSelector(text = "Navigate up"))
        assertEquals("up", result.first().node.id)
    }

    @Test
    fun relativeSelectorFindsButtonBelowAnchor() {
        val nodes = listOf(
            node("label", "0/0", null, text = "Shift details", bounds = UiBounds(0, 100, 500, 200)),
            node("button", "0/1", null, text = "Claim", role = "button", clickable = true, bounds = UiBounds(0, 400, 500, 500)),
        )
        val result = SelectorEngine.resolve(
            snapshot(nodes),
            ElementSelector(text = "Claim", relativeToText = "Shift details", relativeDirection = RelativeDirection.BELOW),
        )
        assertEquals("button", result.first().node.id)
    }
}
