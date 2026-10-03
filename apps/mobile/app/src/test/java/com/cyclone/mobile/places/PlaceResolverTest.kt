package com.cyclone.mobile.places

import com.cyclone.mobile.UiBounds
import com.cyclone.mobile.UiNodeSnapshot
import com.cyclone.mobile.UiSnapshot
import com.cyclone.mobile.agent.contract.AgentPageCard
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceResolverTest {
    @Test fun nativePackageResolves() {
        assertEquals("package:com.example.app", PlaceResolver.resolveCurrent(page("com.example.app"))?.id)
    }

    @Test fun chromeAddressBarResolvesToOriginOnly() {
        val origin = PlaceResolver.observedChromeOrigin(snapshot("HTTPS://Example.COM:443/private?token=secret#part"))
        assertEquals("https://example.com", origin)
        assertEquals("chrome:https://example.com", PlaceResolver.resolveCurrent(page("com.android.chrome", origin))?.id)
    }

    @Test fun explicitNonDefaultPortsRemainAndDefaultsDisappear() {
        assertEquals("http://example.com", PlaceResolver.canonicalOriginFromUrl("HTTP://Example.COM:80/a"))
        assertEquals("https://example.com:8443", PlaceResolver.canonicalOriginFromUrl("https://EXAMPLE.com:8443/a"))
        assertNull(PlaceResolver.canonicalOrigin("https://example.com/path"))
    }

    @Test fun unprovenOrStaleBrowserDoesNotBecomePackagePlace() {
        assertNull(PlaceResolver.resolveCurrent(page("com.android.chrome")))
        val stale = page("com.android.chrome", "https://example.com").copy(
            pageEvidence = JSONObject().put("browserOrigin", "https://example.com")
                .put("browserOriginSource", "chrome-address-bar")
                .put("browserOriginObservationId", "old"),
        )
        assertNull(PlaceResolver.resolveCurrent(stale))
        assertNull(PlaceResolver.observedChromeOrigin(snapshot("https://example.com", focused = true)))
    }

    @Test fun arbitraryPageTextAndInvalidSchemeDoNotProveOrigin() {
        assertNull(PlaceResolver.observedChromeOrigin(snapshot("example.com/login")))
        assertNull(PlaceResolver.observedChromeOrigin(snapshot("javascript:alert(1)")))
        assertNull(PlaceResolver.canonicalOriginFromUrl("https://user:pass@example.com/"))
        assertNull(PlaceResolver.canonicalOriginFromUrl("file:///tmp/page"))
        assertNull(PlaceResolver.observedChromeOrigin(snapshot("https://example.com", resourceId = "com.example.app:id/url_bar")))
    }

    @Test fun exactAddressBarDescriptionCanProveOriginWhenTextIsCollapsed() {
        assertEquals(
            "https://example.com",
            PlaceResolver.observedChromeOrigin(snapshot("example.com", description = "https://example.com/private?q=1")),
        )
    }

    private fun page(packageName: String, origin: String? = null): AgentPageCard = AgentPageCard(
        observationId = "obs-1", generation = 1, actionable = true, capturedAtMs = 1,
        packageName = packageName, activity = null, pageKey = "p", structuralKey = "s",
        contentKey = "c", accessibilityFingerprint = "f", pageSummary = JSONObject(),
        pageText = JSONObject(), pageEvidence = JSONObject().apply {
            if (origin != null) {
                put("browserOrigin", origin)
                put("browserOriginSource", "chrome-address-bar")
                put("browserOriginObservationId", "obs-1")
            }
        }, controls = emptyList(), nextHopHints = JSONArray(),
    )

    private fun snapshot(
        url: String,
        focused: Boolean = false,
        resourceId: String = "com.android.chrome:id/url_bar",
        description: String = "",
    ): UiSnapshot = UiSnapshot(
        packageName = "com.android.chrome", className = "Chrome", screenWidth = 100,
        screenHeight = 100, timestampMs = 1, fingerprint = "fp", controller = "agent",
        windows = emptyList(), nodes = listOf(UiNodeSnapshot(
            id = "bar", path = "0/1", parentId = null, childIds = emptyList(), depth = 1,
            windowId = 1, className = "EditText", role = "edit_text", text = url,
            contentDescription = description, resourceId = resourceId, bounds = UiBounds(0, 0, 100, 20),
            clickable = true, longClickable = false, editable = true, scrollable = false,
            enabled = true, selected = false, checked = false, checkable = false,
            focused = focused, focusable = true, visibleToUser = true,
        )),
    )
}
