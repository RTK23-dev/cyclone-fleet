package com.cyclone.mobile.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCaptureContractTest {
    @Test
    fun captureScreenshotIsAFrozenAuthenticatedOperation() {
        assertTrue(GatewayProtocol.operations.contains("capture.screenshot"))
        assertTrue("capture.screenshot" !in GatewayProtocol.unauthenticatedOperations)
        GatewayProtocol.requireKnownOperation("capture.screenshot")
    }

    @Test
    fun captureScreenshotParsesWithOptionalCaptureArgs() {
        val request = GatewayProtocol.parse(
            """{"id":"shot-1","op":"capture.screenshot","args":{"maxDimension":720,"includeBase64":true},"auth":"token"}""",
        )
        assertEquals("shot-1", request.id)
        assertEquals("capture.screenshot", request.op)
        assertEquals(720, request.args.getInt("maxDimension"))
        assertTrue(request.args.getBoolean("includeBase64"))
    }

    @Test
    fun observeSemanticParsesWithScreenshotEvidenceArgs() {
        val request = GatewayProtocol.parse(
            """{"id":"obs-1","op":"observe.semantic","args":{"includeScreenshot":true,"screenshotMaxDimension":480,"includeScreenshotBase64":true},"auth":"token"}""",
        )
        assertEquals("observe.semantic", request.op)
        assertTrue(request.args.getBoolean("includeScreenshot"))
        assertEquals(480, request.args.getInt("screenshotMaxDimension"))
        assertTrue(request.args.getBoolean("includeScreenshotBase64"))
    }
}
