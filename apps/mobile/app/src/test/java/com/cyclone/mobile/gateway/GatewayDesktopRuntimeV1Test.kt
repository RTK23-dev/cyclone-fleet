package com.cyclone.mobile.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayDesktopRuntimeV1Test {
    @Test
    fun authenticatedPcSessionRemainsVisibleAcrossShortRequestConnections() {
        GatewayRuntime.PcSessionTracker.reset()
        assertFalse(GatewayRuntime.PcSessionTracker.isRecent(1_000L))

        GatewayRuntime.PcSessionTracker.noteAuthenticated(1_000L)

        assertTrue(GatewayRuntime.PcSessionTracker.isRecent(1_000L))
        assertTrue(GatewayRuntime.PcSessionTracker.isRecent(46_000L))
        assertFalse(GatewayRuntime.PcSessionTracker.isRecent(46_001L))
        assertFalse(GatewayRuntime.PcSessionTracker.isRecent(999L))
        GatewayRuntime.PcSessionTracker.reset()
    }

    @Test
    fun pairingCodeIsFourUppercaseLettersAndExpiresWithinSixtySeconds() {
        var now = 1_000L
        val engine = DesktopPairingEngine(nowMs = { now })
        val challenge = engine.begin("usb-session", "pc-nonce-abcdefghijklmnop")
        assertTrue(Regex("^[A-Z]{4}$").matches(challenge.code))
        assertEquals(61_000L, challenge.expiresAtMs)
        now = challenge.expiresAtMs + 1
        assertEquals(
            DesktopPairingCompletion.Failure(DesktopPairingFailure.EXPIRED),
            engine.complete(challenge.challengeId, challenge.usbSessionId, challenge.pcNonce, challenge.code),
        )
    }

    @Test
    fun requestingNewPairingCodeSafelySupersedesPreviousChallenge() {
        val engine = DesktopPairingEngine()
        val first = engine.begin("usb-session", "pc-nonce-abcdefghijklmnop")
        val second = engine.begin("usb-session", "pc-nonce-ponmlkjihgfedcba")

        assertNotEquals(first.challengeId, second.challengeId)
        assertEquals(second.challengeId, engine.activeForUser()?.challengeId)
        assertEquals(
            DesktopPairingCompletion.Failure(DesktopPairingFailure.REPLAY),
            engine.complete(first.challengeId, first.usbSessionId, first.pcNonce, first.code),
        )
        assertEquals(
            DesktopPairingCompletion.Success,
            engine.complete(second.challengeId, second.usbSessionId, second.pcNonce, second.code),
        )
    }

    @Test
    fun pairingIsBoundToUsbSessionAndNonceAndCannotReplay() {
        val engine = DesktopPairingEngine()
        val challenge = engine.begin("usb-A", "pc-nonce-abcdefghijklmnop")
        assertEquals(
            DesktopPairingCompletion.Failure(DesktopPairingFailure.SESSION_MISMATCH),
            engine.complete(challenge.challengeId, "usb-B", challenge.pcNonce, challenge.code),
        )
        assertEquals(
            DesktopPairingCompletion.Failure(DesktopPairingFailure.REPLAY),
            engine.complete(challenge.challengeId, challenge.usbSessionId, challenge.pcNonce, challenge.code),
        )
    }

    @Test
    fun pairingStopsAfterFiveWrongCodes() {
        val engine = DesktopPairingEngine()
        val challenge = engine.begin("usb", "pc-nonce-abcdefghijklmnop")
        repeat(4) {
            assertEquals(
                DesktopPairingCompletion.Failure(DesktopPairingFailure.CODE_REJECTED),
                engine.complete(challenge.challengeId, challenge.usbSessionId, challenge.pcNonce, "ZZZZ"),
            )
        }
        assertEquals(
            DesktopPairingCompletion.Failure(DesktopPairingFailure.ATTEMPTS_EXCEEDED),
            engine.complete(challenge.challengeId, challenge.usbSessionId, challenge.pcNonce, "ZZZZ"),
        )
    }

    @Test
    fun qrPairingRequiresLocalScanAndKeepsInvalidLinksFromApproving() {
        val engine = DesktopPairingEngine()
        val challenge = engine.begin("usb", "pc-nonce-abcdefghijklmnop")

        assertEquals(
            DesktopQrPairingCompletion.Pending,
            engine.completeQr(challenge.challengeId, challenge.usbSessionId, challenge.pcNonce),
        )
        assertFalse(engine.approveQr(challenge.challengeId, "forged-nonce-abcdefghijklmnop"))
        assertEquals(
            DesktopQrPairingCompletion.Pending,
            engine.completeQr(challenge.challengeId, challenge.usbSessionId, challenge.pcNonce),
        )
        assertTrue(engine.approveQr(challenge.challengeId, challenge.pcNonce))
        assertEquals(
            DesktopQrPairingCompletion.Success,
            engine.completeQr(challenge.challengeId, challenge.usbSessionId, challenge.pcNonce),
        )
        assertEquals(
            DesktopQrPairingCompletion.Failure(DesktopPairingFailure.REPLAY),
            engine.completeQr(challenge.challengeId, challenge.usbSessionId, challenge.pcNonce),
        )
    }

    @Test
    fun normalizedTapMapsToCurrentDisplayBounds() {
        assertEquals(0, DesktopManualControlContract.normalizedPixel(0.0, 1080))
        assertEquals(1079, DesktopManualControlContract.normalizedPixel(1.0, 1080))
        assertTrue(DesktopManualControlContract.normalizedPixel(0.5, 1080) in 539..540)
        assertTrue(runCatching { DesktopManualControlContract.normalizedPixel(1.1, 1080) }.isFailure)
        assertEquals(
            setOf("tap", "swipe", "back", "home", "scroll_up", "scroll_down", "text", "wake"),
            DesktopManualControlContract.allowedKinds,
        )
    }

    @Test
    fun clipboardPrivacyRejectsCredentialLikeValues() {
        assertTrue(DesktopClipboardPolicy.looksSensitive("OTP: 123456"))
        assertTrue(DesktopClipboardPolicy.looksSensitive("123456"))
        assertTrue(DesktopClipboardPolicy.looksSensitive("Bearer abcdefghijklmnopqrstuvwxyz0123456789"))
        assertFalse(DesktopClipboardPolicy.looksSensitive("hello from Cyclone desktop"))
    }

    @Test
    fun V33TrustBootstrapMayOmitAuthWhileLegacyTransitionIsReadOnly() {
        assertEquals(
            setOf(
                "trust.negotiate", "trust.begin", "trust.complete", "trust.session.begin", "trust.session.complete",
                "pair.begin", "pair.complete", "pair.qr.complete",
            ),
            GatewayProtocol.unauthenticatedOperations,
        )
        assertTrue("bridge.status" in GatewayProtocol.legacyReadOnlyOperations)
        assertTrue("atlas.places" in GatewayProtocol.legacyReadOnlyOperations)
        assertTrue("atlas.get" in GatewayProtocol.legacyReadOnlyOperations)
        assertTrue("secrets.slots" in GatewayProtocol.legacyReadOnlyOperations)
        assertFalse("secrets.request" in GatewayProtocol.legacyReadOnlyOperations)
        assertFalse("action.execute" in GatewayProtocol.legacyReadOnlyOperations)
        assertFalse("manual.execute" in GatewayProtocol.legacyReadOnlyOperations)
        assertFalse("clipboard.set" in GatewayProtocol.legacyReadOnlyOperations)
        val lower = GatewayProtocol.operations.map(String::lowercase)
        assertFalse(lower.any { "shell" in it || "powershell" in it || "root" in it || it == "adb" })
    }

    @Test
    fun clientWorkerResourcesAreBounded() {
        assertEquals(4, GatewaySocketServer.MAX_CLIENT_WORKERS)
        assertEquals(8, GatewaySocketServer.MAX_QUEUED_CLIENTS)
    }
}
