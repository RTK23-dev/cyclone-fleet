package com.cyclone.mobile.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class GatewayTrustV33Test {
    private class MemoryRepository : GatewayTrustRecordRepository {
        private val records = linkedMapOf<String, GatewayTrustedPc>()

        override fun all(): List<GatewayTrustedPc> = records.values.toList()
        override fun get(trustId: String): GatewayTrustedPc? = records[trustId]
        override fun put(record: GatewayTrustedPc) { records[record.trustId] = record }
        override fun revoke(trustId: String, revokedAtMs: Long): GatewayTrustedPc? {
            val current = records[trustId] ?: return null
            return current.copy(revokedAtMs = current.revokedAtMs ?: revokedAtMs).also { records[trustId] = it }
        }
    }

    private class TestIdentity(
        private val keyPair: KeyPair = ecKeyPair(),
    ) : GatewayPhoneIdentity {
        override val publicKeyBase64: String = GatewayTrustCrypto.encodeBase64Url(keyPair.public.encoded)
        override val phoneId: String = GatewayTrustCrypto.sha256Base64Url(keyPair.public.encoded)
        override fun sign(payload: String): String = sign(keyPair, payload)
    }

    private data class TrustedFixture(
        val repository: MemoryRepository,
        val identity: TestIdentity,
        val pc: KeyPair,
        val engine: GatewayTrustEngine,
        val trustId: String,
        val generation: Long,
    )

    @Test
    fun beginConfirmCompleteAndFutureSessionNeedNoCopiedPersistentToken() {
        val fixture = createTrust()
        val session = openSession(fixture.engine, fixture.trustId, fixture.generation, fixture.pc)
        val token = session.getString("sessionToken")

        assertTrue(session.getBoolean("authenticated"))
        assertTrue(token.length >= 40)
        assertNotNull(fixture.engine.authenticateSession(token))

        val status = fixture.engine.status().toString()
        assertFalse(status.contains(token))
        assertFalse(status.contains("sessionToken"))
        assertFalse(status.contains("privateKey"))
        assertTrue(status.contains("\"reusableSecretExposed\":false"))
    }

    @Test
    fun phoneConfirmationIsRequiredAndExplicitRejectionFailsClosed() {
        val repository = MemoryRepository()
        val identity = TestIdentity()
        val pc = ecKeyPair()
        val engine = GatewayTrustEngine(repository, identity)
        val begin = engine.beginTrust(beginArgs(pc))

        expectCode("PHONE_CONFIRMATION_REQUIRED") {
            engine.completeTrust(completeArgs(begin, pc))
        }
        assertTrue(engine.decideTrust(begin.getString("challengeId"), false))
        expectCode("TRUST_REJECTED") {
            engine.completeTrust(completeArgs(begin, pc))
        }
        assertTrue(repository.all().isEmpty())
    }

    @Test
    fun expiredChallengeAndReplayAreRejected() {
        var now = 10_000L
        val repository = MemoryRepository()
        val identity = TestIdentity()
        val pc = ecKeyPair()
        val engine = GatewayTrustEngine(repository, identity, nowMs = { now })
        val expired = engine.beginTrust(beginArgs(pc))
        engine.decideTrust(expired.getString("challengeId"), true)
        now += GatewayTrustProtocolV33.CHALLENGE_LIFETIME_MS + 1
        expectCode("TRUST_EXPIRED") { engine.completeTrust(completeArgs(expired, pc)) }

        val fresh = engine.beginTrust(beginArgs(pc, "fresh-pc-nonce-00000001"))
        engine.decideTrust(fresh.getString("challengeId"), true)
        engine.completeTrust(completeArgs(fresh, pc))
        expectCode("TRUST_REPLAY") { engine.completeTrust(completeArgs(fresh, pc)) }
    }

    @Test
    fun wrongPcSignatureAndWrongPhoneIdentityAreRejected() {
        val repository = MemoryRepository()
        val identity = TestIdentity()
        val pc = ecKeyPair()
        val wrongPc = ecKeyPair()
        val engine = GatewayTrustEngine(repository, identity)
        val begin = engine.beginTrust(beginArgs(pc))
        engine.decideTrust(begin.getString("challengeId"), true)

        expectCode("AUTH_SIGNATURE_INVALID") {
            engine.completeTrust(
                JSONObject()
                    .put("protocolVersion", GatewayTrustProtocolV33.VERSION)
                    .put("challengeId", begin.getString("challengeId"))
                    .put("pcSignature", sign(wrongPc, begin.getString("transcript"))),
            )
        }

        val trusted = engine.completeTrust(completeArgs(begin, pc))
        val restartedOnDifferentPhone = GatewayTrustEngine(repository, TestIdentity())
        expectCode("TRUST_PHONE_MISMATCH") {
            restartedOnDifferentPhone.beginSession(
                sessionBeginArgs(
                    trusted.getString("trustId"),
                    trusted.getLong("generation"),
                    "wrong-phone-session-nonce-01",
                ),
            )
        }
    }

    @Test
    fun oneLinkedPcCanBeLoggedOutWhileOthersStay() {
        val fixture = createTrust()
        val otherPc = ecKeyPair()
        val begin = fixture.engine.beginTrust(beginArgs(otherPc, "pc-nonce-zyxwvutsrqponmlk"))
        fixture.engine.decideTrust(begin.getString("challengeId"), true)
        val other = fixture.engine.completeTrust(completeArgs(begin, otherPc))
        val first = openSession(fixture.engine, fixture.trustId, fixture.generation, fixture.pc).getString("sessionToken")
        val second = openSession(fixture.engine, other.getString("trustId"), other.getLong("generation"), otherPc).getString("sessionToken")
        assertEquals(2, fixture.engine.linkedPcs().size)
        assertTrue(fixture.engine.isSessionActive(fixture.trustId))

        assertNotNull(fixture.engine.revokeLocal(fixture.trustId))
        assertNull(fixture.engine.authenticateSession(first))
        assertNotNull(fixture.engine.authenticateSession(second))
        assertEquals(listOf(other.getString("trustId")), fixture.engine.linkedPcs().map { it.trustId })
        assertFalse(fixture.engine.isSessionActive(fixture.trustId))
        expectCode("TRUST_REVOKED") {
            fixture.engine.beginSession(sessionBeginArgs(fixture.trustId, fixture.generation, "session-nonce-after-revoke"))
        }
        assertNull(fixture.engine.revokeLocal("missing-trust"))
    }

    @Test
    fun linkedPcLastUsedReadsLikeAChatApp() {
        val pc = GatewayTrustedPc("t", "p", "pc", "Desk PC", "k", 1, createdAtMs = 1_000, lastSessionAtMs = 0)
        assertEquals("Last used just now", linkedPcLastUsed(pc, now = 30_000))
        assertEquals("Last used 5 min ago", linkedPcLastUsed(pc, now = 1_000 + 5 * 60_000))
        assertEquals("Last used 3 h ago", linkedPcLastUsed(pc, now = 1_000 + 3 * 3_600_000))
        assertEquals("Last used 4 days ago", linkedPcLastUsed(pc, now = 1_000 + 4 * 86_400_000L))
    }

    @Test
    fun aPcComingBackAfterAWhileIsAnnouncedButQuickReconnectsAreNot() {
        val hour = 3_600_000L
        val pc = GatewayTrustedPc("t", "p", "pc", "Desk PC", "k", 1, createdAtMs = 0, lastSessionAtMs = 10 * hour)
        assertFalse(GatewayPcConnectedNotice.shouldAnnounce(pc, 11 * hour))
        assertTrue(GatewayPcConnectedNotice.shouldAnnounce(pc, 17 * hour))
        assertFalse("just linked: the Allow card was the notice", GatewayPcConnectedNotice.shouldAnnounce(pc.copy(lastSessionAtMs = 0, createdAtMs = 10 * hour), 10 * hour + 60_000))
        assertFalse(GatewayPcConnectedNotice.shouldAnnounce(pc.copy(revokedAtMs = 1), 30 * hour))
    }

    @Test
    fun persistedTrustSurvivesEngineRestartButSessionsDoNot() {
        val fixture = createTrust()
        val firstSession = openSession(fixture.engine, fixture.trustId, fixture.generation, fixture.pc)
        val firstToken = firstSession.getString("sessionToken")
        assertNotNull(fixture.engine.authenticateSession(firstToken))

        val restarted = GatewayTrustEngine(fixture.repository, fixture.identity)
        assertNull(restarted.authenticateSession(firstToken))
        val secondSession = openSession(restarted, fixture.trustId, fixture.generation, fixture.pc)
        assertTrue(secondSession.getBoolean("authenticated"))
        assertNotEquals(firstToken, secondSession.getString("sessionToken"))
    }

    @Test
    fun sessionChallengeBindsPcNonceAndCannotReplay() {
        val fixture = createTrust()
        val begin = fixture.engine.beginSession(
            sessionBeginArgs(fixture.trustId, fixture.generation, "session-nonce-abcdefghijkl"),
        )
        val completed = fixture.engine.completeSession(
            JSONObject()
                .put("protocolVersion", GatewayTrustProtocolV33.VERSION)
                .put("challengeId", begin.getString("challengeId"))
                .put("pcSignature", sign(fixture.pc, begin.getString("transcript"))),
        )
        assertTrue(completed.getBoolean("authenticated"))
        expectCode("TRUST_REPLAY") {
            fixture.engine.completeSession(
                JSONObject()
                    .put("protocolVersion", GatewayTrustProtocolV33.VERSION)
                    .put("challengeId", begin.getString("challengeId"))
                    .put("pcSignature", sign(fixture.pc, begin.getString("transcript"))),
            )
        }
    }

    @Test
    fun rotateInvalidatesSessionAndRequiresCurrentGeneration() {
        val fixture = createTrust()
        val session = openSession(fixture.engine, fixture.trustId, fixture.generation, fixture.pc)
        val token = session.getString("sessionToken")
        val rotated = fixture.engine.rotateSessionCredential(
            token,
            JSONObject().put("protocolVersion", GatewayTrustProtocolV33.VERSION),
        )
        assertEquals(fixture.generation + 1, rotated.getLong("generation"))
        assertNull(fixture.engine.authenticateSession(token))
        expectCode("PROTOCOL_MISMATCH") {
            fixture.engine.beginSession(
                sessionBeginArgs(fixture.trustId, fixture.generation, "old-generation-nonce-0001"),
            )
        }
    }

    @Test
    fun revokeInvalidatesSessionAndFutureSessionOpen() {
        val fixture = createTrust()
        val session = openSession(fixture.engine, fixture.trustId, fixture.generation, fixture.pc)
        val token = session.getString("sessionToken")
        val revoked = fixture.engine.revoke(
            token,
            JSONObject().put("protocolVersion", GatewayTrustProtocolV33.VERSION),
        )
        assertTrue(revoked.getBoolean("revoked"))
        assertNull(fixture.engine.authenticateSession(token))
        expectCode("TRUST_REVOKED") {
            fixture.engine.beginSession(
                sessionBeginArgs(fixture.trustId, fixture.generation, "revoked-session-nonce-0001"),
            )
        }
    }

    @Test
    fun protocolMismatchIsActionableAndFailClosed() {
        val engine = GatewayTrustEngine(MemoryRepository(), TestIdentity())
        val error = expectCode("PROTOCOL_MISMATCH") {
            engine.negotiate(JSONObject().put("protocolVersion", "3.2"))
        }
        val details = error.details as JSONObject
        assertEquals("3.3", details.getString("phoneProtocolVersion"))
        assertTrue(details.getString("recovery").contains("Update Cyclone PC Companion"))
    }

    private fun createTrust(): TrustedFixture {
        val repository = MemoryRepository()
        val identity = TestIdentity()
        val pc = ecKeyPair()
        val engine = GatewayTrustEngine(repository, identity)
        val begin = engine.beginTrust(beginArgs(pc))
        assertEquals("CONFIRMATION_REQUIRED", engine.status().getString("trustState"))
        assertTrue(engine.decideTrust(begin.getString("challengeId"), true))
        val trusted = engine.completeTrust(completeArgs(begin, pc))
        assertTrue(trusted.getBoolean("trusted"))
        return TrustedFixture(
            repository,
            identity,
            pc,
            engine,
            trusted.getString("trustId"),
            trusted.getLong("generation"),
        )
    }

    private fun openSession(
        engine: GatewayTrustEngine,
        trustId: String,
        generation: Long,
        pc: KeyPair,
    ): JSONObject {
        val begin = engine.beginSession(
            sessionBeginArgs(trustId, generation, "session-nonce-${System.nanoTime()}-abcd"),
        )
        return engine.completeSession(
            JSONObject()
                .put("protocolVersion", GatewayTrustProtocolV33.VERSION)
                .put("challengeId", begin.getString("challengeId"))
                .put("pcSignature", sign(pc, begin.getString("transcript"))),
        )
    }

    private fun beginArgs(pc: KeyPair, nonce: String = "pc-nonce-abcdefghijklmnop"): JSONObject = JSONObject()
        .put("protocolVersion", GatewayTrustProtocolV33.VERSION)
        .put("pcLabel", "Development PC")
        .put("pcPublicKey", GatewayTrustCrypto.encodeBase64Url(pc.public.encoded))
        .put("pcNonce", nonce)

    private fun completeArgs(begin: JSONObject, pc: KeyPair): JSONObject = JSONObject()
        .put("protocolVersion", GatewayTrustProtocolV33.VERSION)
        .put("challengeId", begin.getString("challengeId"))
        .put("pcSignature", sign(pc, begin.getString("transcript")))

    private fun sessionBeginArgs(trustId: String, generation: Long, nonce: String): JSONObject = JSONObject()
        .put("protocolVersion", GatewayTrustProtocolV33.VERSION)
        .put("trustId", trustId)
        .put("generation", generation)
        .put("pcNonce", nonce)

    private fun expectCode(code: String, block: () -> Unit): GatewayProtocolException {
        try {
            block()
            fail("Expected GatewayProtocolException $code")
        } catch (error: GatewayProtocolException) {
            assertEquals(code, error.code)
            return error
        }
        throw AssertionError("unreachable")
    }

    companion object {
        private fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        private fun sign(keyPair: KeyPair, payload: String): String {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(keyPair.private)
            signature.update(payload.toByteArray(Charsets.UTF_8))
            return GatewayTrustCrypto.encodeBase64Url(signature.sign())
        }
    }
}
