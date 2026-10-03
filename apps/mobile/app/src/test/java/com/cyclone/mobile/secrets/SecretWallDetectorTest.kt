package com.cyclone.mobile.secrets

import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentPageCard
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretWallDetectorTest {
    @Test
    fun nativeLoginWallProducesExactPasswordTargetAndSafeMetadata() {
        val page = page(packageName = "com.example.app", sessionId = "workspace-7", displayId = 11)

        val wall = SecretWallDetector.passwordForLogin(page)

        requireNotNull(wall)
        assertEquals("package:com.example.app", wall.request.placeId)
        assertEquals(SecretPersona.LIVE, wall.request.persona)
        assertEquals("password", wall.request.slot)
        assertEquals("Login required", wall.request.reason)
        assertEquals("semantic:obs-login:password", wall.target.elementId)
        assertEquals("obs-login", wall.target.observationId)
        assertEquals("workspace-7", wall.target.sessionId)
        assertEquals(11, wall.target.displayId)
        assertTrue(!wall.toString().contains("hunter"))
    }

    @Test
    fun chromeLoginDoesNotPretendBrowserPackageIsAChromeOrigin() {
        assertNull(
            SecretWallDetector.passwordForLogin(
                page(packageName = "com.android.chrome", sessionId = "default-foreground", displayId = 0),
            ),
        )
    }

    @Test
    fun chromeLoginUsesCurrentCanonicalOriginAndMappingPersona() {
        val page = page("com.android.chrome", "workspace-7", 11, "https://example.com")
        val live = SecretWallDetector.passwordForLogin(page, SecretPersona.LIVE)
        val mapping = SecretWallDetector.passwordForLogin(page, SecretPersona.MAPPING)
        assertEquals("chrome:https://example.com", live?.request?.placeId)
        assertEquals(SecretPersona.LIVE, live?.request?.persona)
        assertEquals("chrome:https://example.com", mapping?.request?.placeId)
        assertEquals(SecretPersona.MAPPING, mapping?.request?.persona)
        assertTrue(mapping.toString().contains("chrome:https://example.com"))
        assertTrue(!mapping.toString().contains("private"))
    }

    private fun page(packageName: String, sessionId: String, displayId: Int, origin: String? = null): AgentPageCard {
        val password = AgentElementCandidate(
            elementId = "semantic:obs-login:password",
            observationId = "obs-login",
            label = "Password",
            semanticName = "password",
            role = "textbox",
            source = "semantic",
            relevance = 1.0,
            evidence = JSONObject()
                .put("editable", true)
                .put("enabled", true)
                .put("visibleToUser", true)
                .put("password", true)
                .put("resourceId", "$packageName:id/password"),
        )
        return AgentPageCard(
            observationId = "obs-login",
            generation = 9L,
            actionable = true,
            capturedAtMs = 10L,
            packageName = packageName,
            activity = "LoginActivity",
            pageKey = "login",
            structuralKey = "login-structure",
            contentKey = "login-content",
            accessibilityFingerprint = "fp-login",
            pageSummary = JSONObject(),
            pageText = JSONObject(),
            pageEvidence = JSONObject().apply {
                if (origin != null) {
                    put("browserOrigin", origin)
                    put("browserOriginSource", "chrome-address-bar")
                    put("browserOriginObservationId", "obs-login")
                }
            },
            controls = listOf(password),
            nextHopHints = JSONArray(),
            sessionId = sessionId,
            displayId = displayId,
        )
    }
}
