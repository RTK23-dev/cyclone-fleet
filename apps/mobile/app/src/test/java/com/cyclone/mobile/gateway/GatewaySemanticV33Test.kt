package com.cyclone.mobile.gateway

import com.cyclone.mobile.observability.pagecontext.PageTextExtractor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaySemanticV33Test {
    @Test
    fun launchIntentUsesMutationFreshnessAndPageTransitionSettle() {
        assertTrue(GatewayV33ActionAdapter.requiresMutationObservation("phone.launch_intent"))
        assertEquals(1_800L, GatewayV33ActionAdapter.pageTransitionSettleMs("phone.launch_intent"))
        assertEquals(0L, GatewayV33ActionAdapter.pageTransitionSettleMs("phone.wait_for"))
    }

    @Test
    fun composeAndWebViewAccessibleContentRemainReadableWithoutValuesLeaking() {
        val raw = snapshot(
            node("compose-title", "Welcome", "", "text", 20, 30),
            node("compose-button", "Continue", "Continue", "button", 20, 100, clickable = true),
            node("web-heading", "Account help", "", "heading", 20, 220),
            node("web-link", "Learn more", "Learn more", "link", 20, 280, clickable = true),
            node("web-email", "victor@example.test", "Email address", "edit_text", 20, 350, editable = true),
        )
        val safe = GatewayPrivacy.sanitizeAccessibilitySnapshot(raw)
        val text = PageTextExtractor.extract(safe)
        val rendered = text.getJSONArray("lines").toString()

        assertTrue(rendered.contains("Welcome"))
        assertTrue(rendered.contains("Continue"))
        assertTrue(rendered.contains("Account help"))
        assertTrue(rendered.contains("Learn more"))
        assertTrue(rendered.contains("Email address"))
        assertFalse(rendered.contains("victor@example.test"))
    }

    @Test
    fun dialogFixturePreservesDeterministicReadingOrder() {
        val safe = GatewayPrivacy.sanitizeAccessibilitySnapshot(
            snapshot(
                node("dialog-title", "Delete draft?", "", "heading", 40, 100),
                node("dialog-body", "This cannot be undone", "", "text", 40, 160),
                node("cancel", "Cancel", "Cancel", "button", 40, 240, clickable = true),
                node("delete", "Delete", "Delete", "button", 300, 240, clickable = true),
            ),
        )
        val lines = PageTextExtractor.extract(safe).getJSONArray("lines")
        assertEquals("Delete draft?", lines.getJSONObject(0).getString("text"))
        assertEquals("This cannot be undone", lines.getJSONObject(1).getString("text"))
        assertEquals("Cancel", lines.getJSONObject(2).getString("text"))
        assertEquals("Delete", lines.getJSONObject(3).getString("text"))
    }

    @Test
    fun keyboardAndSensitiveEditableFixtureRedactsPasswordOtpPinKeyAndTokenValues() {
        val raw = snapshot(
            node("password", "correct horse battery staple", "Password", "edit_text", 20, 80, editable = true, resourceId = "id/password"),
            node("otp", "123456", "One-time code", "edit_text", 20, 150, editable = true, resourceId = "id/otp_code"),
            node("pin", "4815", "PIN", "edit_text", 20, 220, editable = true, resourceId = "id/pin"),
            node("token", "Bearer abcdefghijklmnopqrstuvwxyz", "API token", "edit_text", 20, 290, editable = true, resourceId = "id/api_token"),
            node("keyboard", "Done", "Done", "button", 700, 1700, clickable = true),
        )
        val safe = GatewayPrivacy.sanitizeAccessibilitySnapshot(raw)
        val safeText = safe.toString()
        assertFalse(safeText.contains("correct horse"))
        assertFalse(safeText.contains("123456"))
        assertFalse(safeText.contains("4815"))
        assertFalse(safeText.contains("Bearer abcdef"))
        assertTrue(safeText.contains("<redacted>"))

        val pageText = PageTextExtractor.extract(safe).toString()
        assertFalse(pageText.contains("correct horse"))
        assertFalse(pageText.contains("123456"))
        assertTrue(pageText.contains("Done"))
    }

    @Test
    fun deepScrollFixtureIsBoundedAndMarksTruncation() {
        val nodes = JSONArray()
        repeat(260) { index ->
            nodes.put(
                node(
                    id = "row-$index",
                    text = "Row ${index.toString().padStart(3, '0')}",
                    description = "",
                    role = "text",
                    left = 16,
                    top = index * 40,
                ),
            )
        }
        val raw = JSONObject()
            .put("package", "com.example.deep")
            .put("screen", JSONObject().put("width", 1080).put("height", 20_000))
            .put("nodes", nodes)
        val safe = GatewayPrivacy.sanitizeAccessibilitySnapshot(raw)
        val pageText = PageTextExtractor.extract(safe, maxLines = 80)
        assertEquals(80, pageText.getInt("lineCount"))
        assertTrue(pageText.getBoolean("truncated"))
        val lines = pageText.getJSONArray("lines")
        assertEquals("Row 000", lines.getJSONObject(0).getString("text"))
        assertEquals("Row 079", lines.getJSONObject(79).getString("text"))
    }

    @Test
    fun duplicateOverlayTextIsCollapsed() {
        val raw = snapshot(
            node("base", "Continue", "", "button", 20, 100, clickable = true),
            node("overlay", "Continue", "", "button", 22, 105, clickable = true),
        )
        val lines = PageTextExtractor.extract(GatewayPrivacy.sanitizeAccessibilitySnapshot(raw)).getJSONArray("lines")
        assertEquals(1, lines.length())
        assertEquals("Continue", lines.getJSONObject(0).getString("text"))
    }

    private fun snapshot(vararg nodes: JSONObject): JSONObject = JSONObject()
        .put("package", "com.example.fixture")
        .put("screen", JSONObject().put("width", 1080).put("height", 1920))
        .put("nodes", JSONArray().also { array -> nodes.forEach(array::put) })

    private fun node(
        id: String,
        text: String,
        description: String,
        role: String,
        left: Int,
        top: Int,
        clickable: Boolean = false,
        editable: Boolean = false,
        resourceId: String = "",
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("text", text)
        .put("contentDescription", description)
        .put("role", role)
        .put("resourceId", resourceId)
        .put("visibleToUser", true)
        .put("clickable", clickable)
        .put("editable", editable)
        .put("bounds", JSONObject()
            .put("left", left)
            .put("top", top)
            .put("right", left + 280)
            .put("bottom", top + 36))
}
