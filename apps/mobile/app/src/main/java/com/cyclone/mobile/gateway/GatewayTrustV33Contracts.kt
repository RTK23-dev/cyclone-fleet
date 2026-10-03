package com.cyclone.mobile.gateway

import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal object GatewayTrustProtocolV33 {
    const val VERSION = "3.3"
    const val ID = "cyclone.android.trust.v3"
    const val SIGNATURE_ALGORITHM = "ECDSA_P256_SHA256"
    const val CHALLENGE_LIFETIME_MS = 90_000L
    const val SESSION_CHALLENGE_LIFETIME_MS = 30_000L
    const val SESSION_LIFETIME_MS = 5 * 60_000L

    val capabilities = listOf(
        "trust.device-bound",
        "trust.phone-confirmation",
        "trust.session-short-lived",
        "trust.rotate",
        "trust.revoke",
        "semantic.page-context",
        "action.typed-policy-governed",
        "capture.single-frame",
    )

    fun requireVersion(args: JSONObject, requestId: String = "") {
        val requested = args.optString("protocolVersion").trim()
        if (requested != VERSION) {
            throw GatewayProtocolException(
                code = "PROTOCOL_MISMATCH",
                message = "Cyclone Android trust protocol requires $VERSION; PC requested ${requested.ifBlank { "missing" }}.",
                requestId = requestId,
                details = JSONObject()
                    .put("phoneProtocolVersion", VERSION)
                    .put("requestedProtocolVersion", if (requested.isBlank()) JSONObject.NULL else requested)
                    .put("recovery", "Update Cyclone PC Companion and retry trust negotiation."),
            )
        }
    }

    fun trustTranscript(challenge: GatewayPendingTrust): String = canonical(
        "purpose" to "trust-complete",
        "protocol" to VERSION,
        "challengeId" to challenge.challengeId,
        "phoneId" to challenge.phoneId,
        "pcId" to challenge.pcId,
        "pcNonce" to challenge.pcNonce,
        "phoneNonce" to challenge.phoneNonce,
        "expiresAtMs" to challenge.expiresAtMs.toString(),
    )

    /**
     * Six digits the phone and the PC both show while a PC is being connected. Not a secret: it lets the user
     * see that the request on the phone is the one on the PC screen. PC twin: `trust_match_code` in trust_v33.py.
     */
    fun matchCode(transcript: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray(Charsets.UTF_8))
        val value = ((digest[0].toLong() and 0xff) shl 24) or ((digest[1].toLong() and 0xff) shl 16) or
            ((digest[2].toLong() and 0xff) shl 8) or (digest[3].toLong() and 0xff)
        return (value % 1_000_000L).toString().padStart(6, '0')
    }

    fun matchCode(challenge: GatewayPendingTrust): String = matchCode(trustTranscript(challenge))

    fun trustReceiptTranscript(record: GatewayTrustedPc, challengeId: String): String = canonical(
        "purpose" to "trust-receipt",
        "protocol" to VERSION,
        "challengeId" to challengeId,
        "trustId" to record.trustId,
        "phoneId" to record.phoneId,
        "pcId" to record.pcId,
        "generation" to record.generation.toString(),
    )

    fun sessionTranscript(challenge: GatewayPendingSession): String = canonical(
        "purpose" to "session-open",
        "protocol" to VERSION,
        "challengeId" to challenge.challengeId,
        "trustId" to challenge.trustId,
        "phoneId" to challenge.phoneId,
        "pcId" to challenge.pcId,
        "generation" to challenge.generation.toString(),
        "pcNonce" to challenge.pcNonce,
        "phoneNonce" to challenge.phoneNonce,
        "expiresAtMs" to challenge.expiresAtMs.toString(),
    )

    fun sessionReceiptTranscript(session: GatewayTrustedSession, tokenDigest: String): String = canonical(
        "purpose" to "session-receipt",
        "protocol" to VERSION,
        "sessionId" to session.sessionId,
        "trustId" to session.trustId,
        "phoneId" to session.phoneId,
        "pcId" to session.pcId,
        "generation" to session.generation.toString(),
        "expiresAtMs" to session.expiresAtMs.toString(),
        "tokenSha256" to tokenDigest,
    )

    private fun canonical(vararg fields: Pair<String, String>): String = buildString {
        append(ID).append('\n')
        fields.forEach { (key, value) -> append(key).append('=').append(value).append('\n') }
    }
}

internal enum class GatewayTrustDecision { PENDING, ALLOWED, REJECTED }

internal data class GatewayPendingTrust(
    val challengeId: String,
    val phoneId: String,
    val pcId: String,
    val pcLabel: String,
    val pcPublicKeyBase64: String,
    val pcNonce: String,
    val phoneNonce: String,
    val expiresAtMs: Long,
    var decision: GatewayTrustDecision = GatewayTrustDecision.PENDING,
)

internal data class GatewayTrustedPc(
    val trustId: String,
    val phoneId: String,
    val pcId: String,
    val pcLabel: String,
    val pcPublicKeyBase64: String,
    val generation: Long,
    val createdAtMs: Long,
    val lastSessionAtMs: Long,
    val revokedAtMs: Long? = null,
)

internal data class GatewayPendingSession(
    val challengeId: String,
    val trustId: String,
    val phoneId: String,
    val pcId: String,
    val generation: Long,
    val pcNonce: String,
    val phoneNonce: String,
    val expiresAtMs: Long,
)

internal data class GatewayTrustedSession(
    val sessionId: String,
    val trustId: String,
    val phoneId: String,
    val pcId: String,
    val generation: Long,
    val expiresAtMs: Long,
    val token: String,
)

internal interface GatewayTrustRecordRepository {
    fun all(): List<GatewayTrustedPc>
    fun get(trustId: String): GatewayTrustedPc?
    fun put(record: GatewayTrustedPc)
    fun revoke(trustId: String, revokedAtMs: Long): GatewayTrustedPc?
}

internal interface GatewayPhoneIdentity {
    val phoneId: String
    val publicKeyBase64: String
    fun sign(payload: String): String
}

internal object GatewayTrustCrypto {
    fun publicKeyId(publicKeyBase64: String): String = sha256Base64Url(decodeBase64Url(publicKeyBase64))

    fun decodePcPublicKey(publicKeyBase64: String): PublicKey {
        val bytes = try {
            decodeBase64Url(publicKeyBase64)
        } catch (_: IllegalArgumentException) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "PC identity public key is not valid base64url")
        }
        if (bytes.size !in 64..512) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "PC identity public key has an invalid size")
        }
        val key = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        } catch (_: Exception) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "PC identity must be an X.509 P-256 EC public key")
        }
        val ec = key as? ECPublicKey
            ?: throw GatewayProtocolException("PROTOCOL_MISMATCH", "PC identity must use an EC public key")
        if (ec.params.curve.field.fieldSize != 256) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "PC identity must use the P-256 curve")
        }
        return key
    }

    fun verifyPcSignature(publicKeyBase64: String, payload: String, signatureBase64: String): Boolean {
        if (signatureBase64.length !in 40..256) return false
        return try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(decodePcPublicKey(publicKeyBase64))
            verifier.update(payload.toByteArray(Charsets.UTF_8))
            verifier.verify(decodeBase64Url(signatureBase64))
        } catch (_: Exception) {
            false
        }
    }

    fun sha256Base64Url(value: String): String = sha256Base64Url(value.toByteArray(Charsets.UTF_8))

    fun sha256Base64Url(value: ByteArray): String =
        encodeBase64Url(MessageDigest.getInstance("SHA-256").digest(value))

    fun encodeBase64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    fun decodeBase64Url(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}

internal class GatewayTrustEngine(
    private val records: GatewayTrustRecordRepository,
    private val phoneIdentity: GatewayPhoneIdentity,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
) {
    private var pendingTrust: GatewayPendingTrust? = null
    private val pendingSessions = LinkedHashMap<String, GatewayPendingSession>()
    private val sessionsByToken = LinkedHashMap<String, GatewayTrustedSession>()
    private val consumedChallenges = LinkedHashSet<String>()

    fun negotiate(args: JSONObject): JSONObject {
        GatewayTrustProtocolV33.requireVersion(args)
        return JSONObject()
            .put("protocolId", GatewayTrustProtocolV33.ID)
            .put("protocolVersion", GatewayTrustProtocolV33.VERSION)
            .put("supportedProtocolVersions", JSONArray(listOf(GatewayTrustProtocolV33.VERSION)))
            .put("signatureAlgorithm", GatewayTrustProtocolV33.SIGNATURE_ALGORITHM)
            .put("phoneId", phoneIdentity.phoneId)
            .put("phonePublicKey", phoneIdentity.publicKeyBase64)
            .put("capabilities", JSONArray(GatewayTrustProtocolV33.capabilities))
            .put("legacyTransition", JSONObject()
                .put("available", true)
                .put("mode", "PAIR_CODE_READ_ONLY")
                .put("normalUsbFlow", false))
    }

    @Synchronized
    fun beginTrust(args: JSONObject): JSONObject {
        GatewayTrustProtocolV33.requireVersion(args)
        val pcNonce = requireNonce(args.optString("pcNonce"), "pcNonce")
        val publicKeyBase64 = args.optString("pcPublicKey").trim()
        GatewayTrustCrypto.decodePcPublicKey(publicKeyBase64)
        val pcId = GatewayTrustCrypto.publicKeyId(publicKeyBase64)
        val suppliedPcId = args.optString("pcId").trim()
        if (suppliedPcId.isNotBlank() && suppliedPcId != pcId) {
            throw GatewayProtocolException("AUTH_SIGNATURE_INVALID", "PC identity fingerprint does not match its public key")
        }
        val label = sanitizeLabel(args.optString("pcLabel").ifBlank { "Cyclone PC" })
        pendingTrust?.let { consume(it.challengeId) }
        val challenge = GatewayPendingTrust(
            challengeId = randomToken(18),
            phoneId = phoneIdentity.phoneId,
            pcId = pcId,
            pcLabel = label,
            pcPublicKeyBase64 = publicKeyBase64,
            pcNonce = pcNonce,
            phoneNonce = randomToken(24),
            expiresAtMs = nowMs() + GatewayTrustProtocolV33.CHALLENGE_LIFETIME_MS,
        )
        pendingTrust = challenge
        return JSONObject()
            .put("protocolVersion", GatewayTrustProtocolV33.VERSION)
            .put("challengeId", challenge.challengeId)
            .put("phoneId", challenge.phoneId)
            .put("phonePublicKey", phoneIdentity.publicKeyBase64)
            .put("pcId", challenge.pcId)
            .put("phoneNonce", challenge.phoneNonce)
            .put("expiresAtMs", challenge.expiresAtMs)
            .put("expiresInMs", GatewayTrustProtocolV33.CHALLENGE_LIFETIME_MS)
            .put("confirmationRequired", true)
            .put("confirmationState", "PENDING")
            .put("transcript", GatewayTrustProtocolV33.trustTranscript(challenge))
            .put("matchCode", GatewayTrustProtocolV33.matchCode(challenge))
    }

    @Synchronized
    fun pendingForUser(): GatewayPendingTrust? {
        expirePendingTrustIfNeeded()
        return pendingTrust?.takeIf { it.decision == GatewayTrustDecision.PENDING }
    }

    @Synchronized
    fun decideTrust(challengeId: String, allow: Boolean): Boolean {
        expirePendingTrustIfNeeded()
        val current = pendingTrust ?: return false
        if (current.challengeId != challengeId || current.decision != GatewayTrustDecision.PENDING) return false
        current.decision = if (allow) GatewayTrustDecision.ALLOWED else GatewayTrustDecision.REJECTED
        return true
    }

    @Synchronized
    fun completeTrust(args: JSONObject): JSONObject {
        GatewayTrustProtocolV33.requireVersion(args)
        val challengeId = args.optString("challengeId").trim()
        if (challengeId.isBlank()) throw GatewayProtocolException("PROTOCOL_MISMATCH", "challengeId is required")
        if (challengeId in consumedChallenges) throw GatewayProtocolException("TRUST_REPLAY", "Trust challenge was already consumed")
        val challenge = pendingTrust?.takeIf { it.challengeId == challengeId }
            ?: throw GatewayProtocolException("TRUST_REPLAY", "Trust challenge is not active")
        if (nowMs() > challenge.expiresAtMs) {
            pendingTrust = null
            consume(challenge.challengeId)
            throw GatewayProtocolException("TRUST_EXPIRED", "Trust challenge expired; request a new one")
        }
        when (challenge.decision) {
            GatewayTrustDecision.PENDING -> throw GatewayProtocolException(
                "PHONE_CONFIRMATION_REQUIRED",
                "Confirm Allow this PC on the phone before completing trust.",
            )
            GatewayTrustDecision.REJECTED -> {
                pendingTrust = null
                consume(challenge.challengeId)
                throw GatewayProtocolException("TRUST_REJECTED", "The phone user rejected this PC")
            }
            GatewayTrustDecision.ALLOWED -> Unit
        }
        val signature = args.optString("pcSignature").trim()
        if (!GatewayTrustCrypto.verifyPcSignature(
                challenge.pcPublicKeyBase64,
                GatewayTrustProtocolV33.trustTranscript(challenge),
                signature,
            )
        ) {
            throw GatewayProtocolException("AUTH_SIGNATURE_INVALID", "PC signature did not authenticate the trust transcript")
        }
        val existing = records.all().firstOrNull {
            it.pcId == challenge.pcId && it.revokedAtMs == null && it.phoneId == phoneIdentity.phoneId
        }
        val record = if (existing != null) {
            existing.copy(
                pcLabel = challenge.pcLabel,
                pcPublicKeyBase64 = challenge.pcPublicKeyBase64,
                generation = existing.generation + 1,
            )
        } else {
            GatewayTrustedPc(
                trustId = randomToken(18),
                phoneId = phoneIdentity.phoneId,
                pcId = challenge.pcId,
                pcLabel = challenge.pcLabel,
                pcPublicKeyBase64 = challenge.pcPublicKeyBase64,
                generation = 1L,
                createdAtMs = nowMs(),
                lastSessionAtMs = 0L,
            )
        }
        records.put(record)
        sessionsByToken.entries.removeIf { it.value.pcId == record.pcId }
        pendingTrust = null
        consume(challenge.challengeId)
        val receipt = GatewayTrustProtocolV33.trustReceiptTranscript(record, challenge.challengeId)
        return JSONObject()
            .put("trusted", true)
            .put("trustId", record.trustId)
            .put("phoneId", record.phoneId)
            .put("pcId", record.pcId)
            .put("pcLabel", record.pcLabel)
            .put("generation", record.generation)
            .put("phoneSignature", phoneIdentity.sign(receipt))
            .put("signatureAlgorithm", GatewayTrustProtocolV33.SIGNATURE_ALGORITHM)
            .put("sessionRequired", true)
    }

    @Synchronized
    fun beginSession(args: JSONObject): JSONObject {
        GatewayTrustProtocolV33.requireVersion(args)
        pruneExpired()
        val trustId = args.optString("trustId").trim()
        val record = requireActiveRecord(trustId)
        if (record.phoneId != phoneIdentity.phoneId) {
            throw GatewayProtocolException("TRUST_PHONE_MISMATCH", "Stored trust belongs to a different Cyclone phone identity")
        }
        val requestedGeneration = args.optLong("generation", -1L)
        if (requestedGeneration != record.generation) {
            throw GatewayProtocolException(
                "PROTOCOL_MISMATCH",
                "Trust generation changed; refresh trust status before opening a session.",
                details = JSONObject().put("currentGeneration", record.generation),
            )
        }
        val pcNonce = requireNonce(args.optString("pcNonce"), "pcNonce")
        if (pendingSessions.values.any { it.trustId == trustId && it.pcNonce == pcNonce }) {
            throw GatewayProtocolException("TRUST_REPLAY", "PC nonce is already active")
        }
        val challenge = GatewayPendingSession(
            challengeId = randomToken(18),
            trustId = record.trustId,
            phoneId = record.phoneId,
            pcId = record.pcId,
            generation = record.generation,
            pcNonce = pcNonce,
            phoneNonce = randomToken(24),
            expiresAtMs = nowMs() + GatewayTrustProtocolV33.SESSION_CHALLENGE_LIFETIME_MS,
        )
        pendingSessions[challenge.challengeId] = challenge
        trimPendingSessions()
        return JSONObject()
            .put("protocolVersion", GatewayTrustProtocolV33.VERSION)
            .put("challengeId", challenge.challengeId)
            .put("trustId", challenge.trustId)
            .put("phoneId", challenge.phoneId)
            .put("pcId", challenge.pcId)
            .put("generation", challenge.generation)
            .put("phoneNonce", challenge.phoneNonce)
            .put("expiresAtMs", challenge.expiresAtMs)
            .put("expiresInMs", GatewayTrustProtocolV33.SESSION_CHALLENGE_LIFETIME_MS)
            .put("transcript", GatewayTrustProtocolV33.sessionTranscript(challenge))
    }

    @Synchronized
    fun completeSession(args: JSONObject): JSONObject {
        GatewayTrustProtocolV33.requireVersion(args)
        pruneExpired()
        val challengeId = args.optString("challengeId").trim()
        if (challengeId.isBlank()) throw GatewayProtocolException("PROTOCOL_MISMATCH", "challengeId is required")
        if (challengeId in consumedChallenges) throw GatewayProtocolException("TRUST_REPLAY", "Session challenge was already consumed")
        val challenge = pendingSessions.remove(challengeId)
            ?: throw GatewayProtocolException("TRUST_REPLAY", "Session challenge is not active")
        if (nowMs() > challenge.expiresAtMs) {
            consume(challenge.challengeId)
            throw GatewayProtocolException("TRUST_EXPIRED", "Session challenge expired; request a new one")
        }
        val record = requireActiveRecord(challenge.trustId)
        if (record.generation != challenge.generation || record.pcId != challenge.pcId || record.phoneId != phoneIdentity.phoneId) {
            consume(challenge.challengeId)
            throw GatewayProtocolException("AUTH_REJECTED", "Trusted PC record changed before session completion")
        }
        val signature = args.optString("pcSignature").trim()
        if (!GatewayTrustCrypto.verifyPcSignature(
                record.pcPublicKeyBase64,
                GatewayTrustProtocolV33.sessionTranscript(challenge),
                signature,
            )
        ) {
            consume(challenge.challengeId)
            throw GatewayProtocolException("AUTH_SIGNATURE_INVALID", "PC signature did not authenticate the session transcript")
        }
        val token = randomToken(32)
        val session = GatewayTrustedSession(
            sessionId = randomToken(18),
            trustId = record.trustId,
            phoneId = record.phoneId,
            pcId = record.pcId,
            generation = record.generation,
            expiresAtMs = nowMs() + GatewayTrustProtocolV33.SESSION_LIFETIME_MS,
            token = token,
        )
        sessionsByToken[token] = session
        records.put(record.copy(lastSessionAtMs = nowMs()))
        consume(challenge.challengeId)
        pruneExpired()
        val tokenDigest = GatewayTrustCrypto.sha256Base64Url(token)
        val receipt = GatewayTrustProtocolV33.sessionReceiptTranscript(session, tokenDigest)
        return JSONObject()
            .put("authenticated", true)
            .put("sessionId", session.sessionId)
            .put("sessionToken", token)
            .put("expiresAtMs", session.expiresAtMs)
            .put("expiresInMs", GatewayTrustProtocolV33.SESSION_LIFETIME_MS)
            .put("trustId", session.trustId)
            .put("generation", session.generation)
            .put("phoneSignature", phoneIdentity.sign(receipt))
            .put("signatureAlgorithm", GatewayTrustProtocolV33.SIGNATURE_ALGORITHM)
    }

    @Synchronized
    fun authenticateSession(token: String): GatewayTrustedSession? {
        pruneExpired()
        if (token.isBlank()) return null
        val session = sessionsByToken[token] ?: return null
        val record = records.get(session.trustId) ?: return null
        if (record.revokedAtMs != null || record.generation != session.generation || record.phoneId != phoneIdentity.phoneId) {
            sessionsByToken.remove(token)
            return null
        }
        return session
    }

    @Synchronized
    fun rotateSessionCredential(authToken: String, args: JSONObject): JSONObject {
        GatewayTrustProtocolV33.requireVersion(args)
        val session = authenticateSession(authToken)
            ?: throw GatewayProtocolException("AUTH_REJECTED", "A trusted Cyclone PC session is required")
        val record = requireActiveRecord(session.trustId)
        val rotated = record.copy(generation = record.generation + 1)
        records.put(rotated)
        sessionsByToken.entries.removeIf { it.value.trustId == record.trustId }
        pendingSessions.entries.removeIf { it.value.trustId == record.trustId }
        return JSONObject()
            .put("rotated", true)
            .put("trustId", rotated.trustId)
            .put("generation", rotated.generation)
            .put("sessionRequired", true)
    }

    @Synchronized
    fun revoke(authToken: String, args: JSONObject): JSONObject {
        GatewayTrustProtocolV33.requireVersion(args)
        val session = authenticateSession(authToken)
            ?: throw GatewayProtocolException("AUTH_REJECTED", "A trusted Cyclone PC session is required")
        val requestedTrustId = args.optString("trustId").ifBlank { session.trustId }
        if (requestedTrustId != session.trustId) {
            throw GatewayProtocolException("POLICY_DENIED", "A PC may revoke only its own trust record through this session")
        }
        val revoked = records.revoke(session.trustId, nowMs())
            ?: throw GatewayProtocolException("AUTH_REJECTED", "Trust record is no longer active")
        sessionsByToken.entries.removeIf { it.value.trustId == revoked.trustId }
        pendingSessions.entries.removeIf { it.value.trustId == revoked.trustId }
        return JSONObject()
            .put("revoked", true)
            .put("trustId", revoked.trustId)
            .put("pcId", revoked.pcId)
            .put("revokedAtMs", revoked.revokedAtMs ?: nowMs())
    }

    @Synchronized
    fun disconnectSessions() {
        sessionsByToken.clear()
        pendingSessions.clear()
    }

    /** The PCs this phone trusts now (like WhatsApp's linked devices), most recently used first. */
    @Synchronized
    fun linkedPcs(): List<GatewayTrustedPc> =
        records.all()
            .filter { it.revokedAtMs == null && it.phoneId == phoneIdentity.phoneId }
            .sortedByDescending { maxOf(it.lastSessionAtMs, it.createdAtMs) }

    /** Whether a trusted PC has a live session right now. */
    @Synchronized
    fun isSessionActive(trustId: String): Boolean = sessionsByToken.values.any { it.trustId == trustId }

    /**
     * Local user authority from Cyclone Settings: log one PC out. Its trust record is revoked and only its sessions end;
     * other linked PCs stay connected. Connecting again needs a new code + Allow.
     */
    @Synchronized
    fun revokeLocal(trustId: String): GatewayTrustedPc? {
        val revoked = records.revoke(trustId, nowMs()) ?: return null
        sessionsByToken.entries.removeIf { it.value.trustId == trustId }
        pendingSessions.entries.removeIf { it.value.trustId == trustId }
        return revoked
    }

    @Synchronized
    fun status(): JSONObject {
        expirePendingTrustIfNeeded()
        pruneExpired()
        val allRecords = records.all()
        val activeRecords = allRecords.filter { it.revokedAtMs == null && it.phoneId == phoneIdentity.phoneId }
        val revokedRecords = allRecords.count { it.revokedAtMs != null }
        val pending = pendingTrust
        val trustState = when {
            pending?.decision == GatewayTrustDecision.PENDING -> "CONFIRMATION_REQUIRED"
            pending?.decision == GatewayTrustDecision.REJECTED -> "REJECTED"
            sessionsByToken.isNotEmpty() -> "TRUSTED_SESSION_ACTIVE"
            activeRecords.isNotEmpty() -> "TRUSTED"
            revokedRecords > 0 -> "REVOKED"
            else -> "UNPAIRED"
        }
        return JSONObject()
            .put("protocolVersion", GatewayTrustProtocolV33.VERSION)
            .put("phoneId", phoneIdentity.phoneId)
            .put("trustState", trustState)
            .put("trustedPcCount", activeRecords.size)
            .put("revokedPcCount", revokedRecords)
            .put("activeSessionCount", sessionsByToken.size)
            .put("confirmationRequired", pending?.decision == GatewayTrustDecision.PENDING)
            .put("pendingPcLabel", if (pending?.decision == GatewayTrustDecision.PENDING) pending.pcLabel else JSONObject.NULL)
            .put("pendingPcId", if (pending?.decision == GatewayTrustDecision.PENDING) pending.pcId else JSONObject.NULL)
            .put("pendingExpiresAtMs", if (pending?.decision == GatewayTrustDecision.PENDING) pending.expiresAtMs else JSONObject.NULL)
            .put("reusableSecretExposed", false)
    }

    private fun requireActiveRecord(trustId: String): GatewayTrustedPc {
        if (trustId.isBlank()) throw GatewayProtocolException("PROTOCOL_MISMATCH", "trustId is required")
        val record = records.get(trustId) ?: throw GatewayProtocolException("AUTH_REJECTED", "Trusted PC is unknown")
        if (record.revokedAtMs != null) throw GatewayProtocolException("TRUST_REVOKED", "Trusted PC was revoked on this phone")
        return record
    }

    private fun requireNonce(value: String, name: String): String {
        val nonce = value.trim()
        if (nonce.length !in 16..200 || !Regex("^[A-Za-z0-9._~-]+$").matches(nonce)) {
            throw GatewayProtocolException("PROTOCOL_MISMATCH", "$name must be a 16-200 character URL-safe nonce")
        }
        return nonce
    }

    private fun sanitizeLabel(value: String): String {
        val label = value
            .replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
        return label.ifBlank { "Cyclone PC" }
    }

    private fun expirePendingTrustIfNeeded() {
        val current = pendingTrust ?: return
        if (nowMs() > current.expiresAtMs) {
            pendingTrust = null
            consume(current.challengeId)
        }
    }

    private fun pruneExpired() {
        val now = nowMs()
        pendingSessions.entries.removeIf {
            val expired = now > it.value.expiresAtMs
            if (expired) consume(it.key)
            expired
        }
        sessionsByToken.entries.removeIf { now > it.value.expiresAtMs }
        while (sessionsByToken.size > 16) sessionsByToken.remove(sessionsByToken.entries.first().key)
    }

    private fun trimPendingSessions() {
        while (pendingSessions.size > 16) {
            val first = pendingSessions.entries.first()
            pendingSessions.remove(first.key)
            consume(first.key)
        }
    }

    private fun consume(id: String) {
        if (id.isBlank()) return
        consumedChallenges.add(id)
        while (consumedChallenges.size > 128) consumedChallenges.remove(consumedChallenges.first())
    }

    private fun randomToken(bytes: Int): String {
        val value = ByteArray(bytes).also(random::nextBytes)
        return GatewayTrustCrypto.encodeBase64Url(value)
    }
}
