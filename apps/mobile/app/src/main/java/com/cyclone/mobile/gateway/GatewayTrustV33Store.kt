package com.cyclone.mobile.gateway

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

internal class AndroidGatewayPhoneIdentity(
    private val context: Context,
) : GatewayPhoneIdentity {
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "cyclone_gateway_v33_phone_identity"
    }

    private val keyStore: KeyStore
        get() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun ensureKey() {
        val store = keyStore
        if (store.containsAlias(ALIAS)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    private fun publicKeyBytes(): ByteArray {
        ensureKey()
        return keyStore.getCertificate(ALIAS)?.publicKey?.encoded
            ?: throw GatewayProtocolException("PHONE_LOCKED_OR_UNAVAILABLE", "Cyclone phone identity is unavailable")
    }

    override val publicKeyBase64: String
        get() = GatewayTrustCrypto.encodeBase64Url(publicKeyBytes())

    override val phoneId: String
        get() = GatewayTrustCrypto.sha256Base64Url(publicKeyBytes())

    override fun sign(payload: String): String {
        ensureKey()
        val privateKey = keyStore.getKey(ALIAS, null) as? PrivateKey
            ?: throw GatewayProtocolException("PHONE_LOCKED_OR_UNAVAILABLE", "Cyclone phone identity key is unavailable")
        return try {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(privateKey)
            signature.update(payload.toByteArray(Charsets.UTF_8))
            GatewayTrustCrypto.encodeBase64Url(signature.sign())
        } catch (_: Exception) {
            throw GatewayProtocolException("PHONE_LOCKED_OR_UNAVAILABLE", "Cyclone phone identity could not sign the trust transcript")
        }
    }
}

internal class AndroidGatewayTrustRepository(
    context: Context,
) : GatewayTrustRecordRepository {
    companion object {
        private const val PREFS = "cyclone_gateway_trust_v33"
        private const val KEY_RECORDS = "trusted_pc_records"
        private const val MAX_RECORDS = 24
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun all(): List<GatewayTrustedPc> = readRecords()

    @Synchronized
    override fun get(trustId: String): GatewayTrustedPc? = readRecords().firstOrNull { it.trustId == trustId }

    @Synchronized
    override fun put(record: GatewayTrustedPc) {
        val records = readRecords().toMutableList()
        val index = records.indexOfFirst { it.trustId == record.trustId }
        if (index >= 0) records[index] = record else records += record
        writeRecords(records.sortedByDescending { maxOf(it.lastSessionAtMs, it.createdAtMs) }.take(MAX_RECORDS))
    }

    @Synchronized
    override fun revoke(trustId: String, revokedAtMs: Long): GatewayTrustedPc? {
        val records = readRecords().toMutableList()
        val index = records.indexOfFirst { it.trustId == trustId }
        if (index < 0) return null
        val current = records[index]
        val revoked = current.copy(revokedAtMs = current.revokedAtMs ?: revokedAtMs)
        records[index] = revoked
        writeRecords(records)
        return revoked
    }

    private fun readRecords(): List<GatewayTrustedPc> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val trustId = item.optString("trustId").trim()
                val phoneId = item.optString("phoneId").trim()
                val pcId = item.optString("pcId").trim()
                val publicKey = item.optString("pcPublicKey").trim()
                if (trustId.isBlank() || phoneId.isBlank() || pcId.isBlank() || publicKey.isBlank()) continue
                add(
                    GatewayTrustedPc(
                        trustId = trustId,
                        phoneId = phoneId,
                        pcId = pcId,
                        pcLabel = item.optString("pcLabel", "Cyclone PC").take(80),
                        pcPublicKeyBase64 = publicKey,
                        generation = item.optLong("generation", 1L).coerceAtLeast(1L),
                        createdAtMs = item.optLong("createdAtMs", 0L),
                        lastSessionAtMs = item.optLong("lastSessionAtMs", 0L),
                        revokedAtMs = item.optLong("revokedAtMs", 0L).takeIf { it > 0L },
                    ),
                )
            }
        }
    }

    private fun writeRecords(records: List<GatewayTrustedPc>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("trustId", record.trustId)
                    .put("phoneId", record.phoneId)
                    .put("pcId", record.pcId)
                    .put("pcLabel", record.pcLabel)
                    .put("pcPublicKey", record.pcPublicKeyBase64)
                    .put("generation", record.generation)
                    .put("createdAtMs", record.createdAtMs)
                    .put("lastSessionAtMs", record.lastSessionAtMs)
                    .put("revokedAtMs", record.revokedAtMs ?: JSONObject.NULL),
            )
        }
        prefs.edit().putString(KEY_RECORDS, array.toString()).apply()
    }
}

internal object GatewayV33TrustManager {
    @Volatile private var engine: GatewayTrustEngine? = null

    @Synchronized
    private fun engine(context: Context): GatewayTrustEngine {
        engine?.let { return it }
        val app = context.applicationContext
        return GatewayTrustEngine(
            records = AndroidGatewayTrustRepository(app),
            phoneIdentity = AndroidGatewayPhoneIdentity(app),
        ).also { engine = it }
    }

    fun negotiate(context: Context, args: JSONObject): JSONObject = engine(context).negotiate(args)

    fun beginTrust(context: Context, args: JSONObject): JSONObject {
        requirePhoneAvailable(context)
        val engine = engine(context)
        val response = engine.beginTrust(args)
        engine.pendingForUser()?.let { GatewayTrustPrompt.show(context, it) }
        return response
    }

    fun completeTrust(context: Context, args: JSONObject): JSONObject {
        requirePhoneAvailable(context)
        return engine(context).completeTrust(args)
    }

    fun beginSession(context: Context, args: JSONObject): JSONObject {
        requirePhoneAvailable(context)
        return engine(context).beginSession(args)
    }

    fun completeSession(context: Context, args: JSONObject): JSONObject {
        requirePhoneAvailable(context)
        val engine = engine(context)
        val before = runCatching { engine.linkedPcs().associateBy { it.trustId } }.getOrDefault(emptyMap())
        val result = engine.completeSession(args)
        // Like a chat app's "new login" notice: tell the owner when a linked PC comes back after a while.
        runCatching {
            val trustId = result.optString("trustId")
            val previous = before[trustId] ?: engine.linkedPcs().firstOrNull { it.trustId == trustId }
            if (previous != null && GatewayPcConnectedNotice.shouldAnnounce(previous, System.currentTimeMillis())) {
                GatewayPcConnectedNotice.show(context, previous.pcLabel)
            }
        }
        return result
    }

    fun rotate(context: Context, authToken: String, args: JSONObject): JSONObject =
        engine(context).rotateSessionCredential(authToken, args)

    fun revoke(context: Context, authToken: String, args: JSONObject): JSONObject =
        engine(context).revoke(authToken, args)

    fun linkedPcs(context: Context): List<GatewayTrustedPc> = engine(context).linkedPcs()

    fun isSessionActive(context: Context, trustId: String): Boolean = engine(context).isSessionActive(trustId)

    /** Local user authority in Cyclone Settings: log out one PC; the others stay linked. */
    fun revokeOneLocal(context: Context, trustId: String): Boolean = engine(context).revokeLocal(trustId) != null

    /** Local user authority in Cyclone Settings; no PC credential is accepted or required. */
    fun revokeAllLocal(context: Context): Int {
        val app = context.applicationContext
        val repository = AndroidGatewayTrustRepository(app)
        val active = repository.all().filter { it.revokedAtMs == null }
        val now = System.currentTimeMillis()
        active.forEach { repository.revoke(it.trustId, now) }
        engine(app).disconnectSessions()
        return active.size
    }

    fun authenticateSession(context: Context, token: String): GatewayTrustedSession? =
        engine(context).authenticateSession(token)

    fun pendingForUser(context: Context): GatewayPendingTrust? = engine(context).pendingForUser()

    fun decideTrust(context: Context, challengeId: String, allow: Boolean): Boolean {
        GatewayTrustPrompt.clear(context)
        return engine(context).decideTrust(challengeId, allow)
    }

    fun disconnectSessions(context: Context) {
        engine(context).disconnectSessions()
    }

    fun status(context: Context): JSONObject = runCatching { engine(context).status() }
        .getOrElse {
            JSONObject()
                .put("protocolVersion", GatewayTrustProtocolV33.VERSION)
                .put("trustState", "DEGRADED")
                .put("trustedPcCount", 0)
                .put("activeSessionCount", 0)
                .put("confirmationRequired", false)
                .put("reusableSecretExposed", false)
        }

    private fun requirePhoneAvailable(context: Context) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceLocked == true) {
            throw GatewayProtocolException(
                "PHONE_LOCKED_OR_UNAVAILABLE",
                "Unlock the phone before creating or restoring Cyclone AI trust.",
            )
        }
    }

    @Synchronized
    internal fun resetForTests() {
        engine = null
    }
}
