package com.cyclone.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccessibilityRolesTest {
    @Test
    fun inferRoleEmitsTabForTabLayoutAndSelectedClockTab() {
        assertEquals(
            "tab",
            AccessibilityRoles.inferRole(
                className = "com.google.android.material.tabs.TabLayout.TabView",
                clickable = true,
                selected = true,
                text = "Timer",
            ),
        )
        assertEquals(
            "tab",
            AccessibilityRoles.inferRole(
                className = "android.widget.TextView",
                clickable = true,
                selected = true,
                text = "Alarm",
                parentClassName = "com.google.android.material.tabs.TabLayout",
            ),
        )
        assertFalse(
            AccessibilityRoles.inferRole(
                className = "android.widget.TextView",
                text = "Timer",
            ) == "tab",
        )
    }

    @Test
    fun foldTalkBackHostsPromotesDescendantLabelOntoClickableRow() {
        val host = node("host", "0/1", null, listOf("leaf"), "", "generic", clickable = true, className = "androidx.preference.Preference")
        val leaf = node("leaf", "0/1/0", "host", emptyList(), "Network & internet", "text", clickable = false)
        val folded = AccessibilityRoles.foldTalkBackHosts(listOf(host, leaf))
        val promoted = folded.first { it.id == "host" }
        assertEquals("Network & internet", promoted.text)
        assertTrue(promoted.clickable)
        assertEquals("row", promoted.role)
        assertEquals("host", AccessibilityRoles.resolveActivationTarget(folded, promoted).id)
    }

    @Test
    fun emptySelectorIsHardError() {
        val snap = UiSnapshot(
            packageName = "com.test",
            className = "Act",
            screenWidth = 100,
            screenHeight = 100,
            timestampMs = 1,
            fingerprint = "fp",
            controller = "agent",
            windows = emptyList(),
            nodes = listOf(node("top", "0", null, emptyList(), "chrome", "text")),
        )
        val empty = ElementSelector.fromJson(JSONObject())
        assertTrue(empty.isEmpty())
        try {
            SelectorEngine.resolve(snap, empty, 8)
            fail("empty selector must hard-error")
        } catch (error: EmptySelectorException) {
            assertTrue(error.message!!.contains("empty selector"))
        }
        val ignoredId = ElementSelector.fromJson(JSONObject().put("id", "semantic:obs:x"))
        assertTrue(ignoredId.isEmpty())
    }

    @Test
    fun navigateUpEvidenceDoesNotForceLabelIntoSelectorText() {
        val evidence = JSONObject()
            .put("label", "Navigate up")
            .put("role", "button")
            .put("clickable", true)
            .put("contentDescription", "Navigate up")
            .put("selector", JSONObject().put("contentDescription", "Navigate up").put("role", "button"))
        val selector = ObservationSelectorLookup.fromEvidence(evidence, "semantic:obs:up")
        assertEquals(null, selector.text)
        assertEquals("Navigate up", selector.contentDescription)
        assertEquals("button", selector.role)
    }

    @Test
    fun filesRowFoldKeepsFilenameAheadOfDateAndSize() {
        val host = node("row", "0/1", null, listOf("name", "date", "size"), "", "generic", clickable = true)
        val name = node("name", "0/1/0", "row", emptyList(), "Cyclone-3.6.0-beta.2.apk", "text")
        val date = node("date", "0/1/1", "row", emptyList(), "Tue, 01 Sept", "text")
        val size = node("size", "0/1/2", "row", emptyList(), "69,98 MB • 2 days ago", "text")
        val folded = AccessibilityRoles.foldTalkBackHosts(listOf(host, name, date, size))
        val promoted = folded.first { it.id == "row" }
        assertTrue(promoted.text.contains("Cyclone-3.6.0-beta.2.apk"))
        assertTrue(promoted.clickable)
        assertTrue("ACTION_CLICK" in promoted.actions)
    }

    @Test
    fun filesRowFilenameRefActivatesButtonSiblingNotGenericHost() {
        val parent = node(
            "row", "0/1", null, listOf("name", "open"), "", "generic",
            clickable = true, actions = emptyList(),
        )
        val name = node("name", "0/1/0", "row", emptyList(), "Cyclone-3.6.0-beta.2.apk", "text")
        val open = node(
            "open", "0/1/1", "row", emptyList(), "Cyclone-3.6.0-beta.2.apk", "button",
            clickable = true, className = "android.widget.Button",
            actions = listOf("ACTION_CLICK"),
        )
        val folded = AccessibilityRoles.foldTalkBackHosts(listOf(parent, name, open))
        val foldedParent = folded.first { it.id == "row" }
        val foldedButton = folded.first { it.id == "open" }
        assertTrue(foldedButton.text.contains("Cyclone-3.6.0-beta.2.apk"))
        assertTrue("ACTION_CLICK" in foldedButton.actions)
        assertTrue(foldedButton.clickable)
        assertFalse(foldedParent.clickable)
        assertFalse("ACTION_CLICK" in foldedParent.actions)

        val fromParent = AccessibilityRoles.resolveActivationTarget(folded, foldedParent)
        assertEquals("open", fromParent.id)
        val fromName = AccessibilityRoles.resolveActivationTarget(folded, folded.first { it.id == "name" })
        assertEquals("open", fromName.id)
        val fromButton = AccessibilityRoles.resolveActivationTarget(folded, foldedButton)
        assertEquals("open", fromButton.id)

        val advertisedParent = node(
            "row2", "0/2", null, listOf("name2", "open2"), "Cyclone-3.6.0-beta.2.apk", "generic",
            clickable = true, actions = listOf("ACTION_CLICK"),
        )
        val name2 = node("name2", "0/2/0", "row2", emptyList(), "Cyclone-3.6.0-beta.2.apk", "text")
        val open2 = node(
            "open2", "0/2/1", "row2", emptyList(), "Cyclone-3.6.0-beta.2.apk", "button",
            clickable = true, className = "android.widget.Button",
            actions = listOf("ACTION_CLICK"),
        )
        val advertised = listOf(advertisedParent, name2, open2)
        val filenameRef = advertised.first { it.id == "row2" }
        assertEquals("generic", filenameRef.role)
        val live = AccessibilityRoles.resolveActivationTarget(advertised, filenameRef)
        assertEquals("open2", live.id)
        assertTrue("ACTION_CLICK" in live.actions)
    }

    @Test
    fun calculatorDigitSevenSurvivesFoldAndStaysClickable() {
        val seven = node(
            "seven", "0/2/7", "pad", emptyList(), "7", "button",
            clickable = true, className = "android.widget.Button",
        )
        val folded = AccessibilityRoles.foldTalkBackHosts(listOf(seven))
        assertEquals("7", folded.single().text)
        assertTrue(folded.single().clickable)
        assertEquals("button", folded.single().role)
        assertEquals("seven", AccessibilityRoles.resolveActivationTarget(folded, folded.single()).id)
    }

    @Test
    fun calculatorUnlabeledHostPromotesDigitChild() {
        val host = node("key7", "0/2/7", null, listOf("glyph"), "", "generic", clickable = true)
        val glyph = node("glyph", "0/2/7/0", "key7", emptyList(), "7", "text")
        val folded = AccessibilityRoles.foldTalkBackHosts(listOf(host, glyph))
        assertEquals("7", folded.first { it.id == "key7" }.text)
    }

    @Test
    fun clockTabViewPrefersActivatableAncestorEvenWhenNotClickable() {
        assertEquals(
            "tab",
            AccessibilityRoles.inferRole(
                className = "com.google.android.material.tabs.TabLayout.TabView",
                clickable = false,
                selected = false,
                text = "Alarms",
                actions = listOf("ACTION_CLICK", "ACTION_SELECT"),
            ),
        )
        assertTrue(AccessibilityRoles.isActivatable(false, listOf("ACTION_CLICK")))
        assertTrue(
            AccessibilityRoles.preferClickableAncestor(
                role = "tab",
                nodeClickable = false,
                actions = emptyList(),
            ),
        )
        assertFalse(
            AccessibilityRoles.preferClickableAncestor(
                role = "tab",
                nodeClickable = false,
                actions = listOf("ACTION_SELECT"),
            ),
        )
        val tab = node(
            "tab", "0/4", null, listOf("label"), "", "generic",
            clickable = false, className = "com.google.android.material.tabs.TabLayout.TabView",
            actions = listOf("ACTION_CLICK"), selected = false,
        )
        val label = node("label", "0/4/0", "tab", emptyList(), "Alarms", "text")
        val folded = AccessibilityRoles.foldTalkBackHosts(listOf(tab, label))
        val host = folded.first { it.id == "tab" }
        assertEquals("Alarms", host.text)
        assertEquals("tab", host.role)
        assertTrue(host.clickable)
        assertEquals("tab", AccessibilityRoles.resolveActivationTarget(folded, host).id)
    }

    @Test
    fun unpublishedKeypadStillPublishesOnScreenInteractiveDigits() {
        assertTrue(
            AccessibilityRoles.isPublishedInteractive(
                visibleToUser = false,
                interactive = true,
                boundsWidth = 120,
                boundsHeight = 120,
            ),
        )
        assertFalse(
            AccessibilityRoles.isPublishedInteractive(
                visibleToUser = false,
                interactive = false,
                boundsWidth = 120,
                boundsHeight = 120,
            ),
        )
    }

    private fun node(
        id: String,
        path: String,
        parent: String?,
        children: List<String>,
        text: String,
        role: String,
        clickable: Boolean = false,
        className: String = "android.view.View",
        actions: List<String>? = null,
        selected: Boolean = false,
    ) = UiNodeSnapshot(
        id = id,
        path = path,
        parentId = parent,
        childIds = children,
        depth = path.count { it == '/' },
        windowId = 1,
        className = className,
        role = role,
        text = text,
        contentDescription = "",
        resourceId = "",
        bounds = UiBounds(0, 0, 10, 10),
        clickable = clickable,
        longClickable = false,
        editable = false,
        scrollable = false,
        enabled = true,
        selected = selected,
        checked = false,
        checkable = false,
        focused = false,
        focusable = clickable,
        visibleToUser = true,
        actions = actions ?: if (clickable) listOf("ACTION_CLICK") else emptyList(),
    )
}
