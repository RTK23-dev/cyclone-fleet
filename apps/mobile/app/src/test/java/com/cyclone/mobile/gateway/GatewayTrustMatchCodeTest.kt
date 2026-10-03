package com.cyclone.mobile.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayTrustMatchCodeTest {
    /** Same transcript and expected code as test_trust_match_code_is_shared_with_the_phone (device gateway). */
    private val transcript = "cyclone.android.trust.v3\npurpose=trust-complete\nprotocol=3.3\nchallengeId=challenge-1\n" +
        "phoneId=phone-1\npcId=pc-1\npcNonce=pcnonce\nphoneNonce=phonenonce\nexpiresAtMs=1700000000000\n"

    @Test
    fun phoneAndPcDeriveTheSameSixDigitCode() {
        assertEquals("173715", GatewayTrustProtocolV33.matchCode(transcript))
        val challenge = GatewayPendingTrust(
            challengeId = "challenge-1", phoneId = "phone-1", pcId = "pc-1", pcLabel = "Desk PC", pcPublicKeyBase64 = "k",
            pcNonce = "pcnonce", phoneNonce = "phonenonce", expiresAtMs = 1_700_000_000_000L,
        )
        assertEquals(transcript, GatewayTrustProtocolV33.trustTranscript(challenge))
        assertEquals("173715", GatewayTrustProtocolV33.matchCode(challenge))
        assertEquals("173 715", GatewayTrustPrompt.formatCode("173715"))
    }
}
