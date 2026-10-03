package com.cyclone.mobile.gateway

import android.content.Context
import java.security.SecureRandom
import java.util.Base64

internal enum class GatewaySessionAuthMode {
    V33_TRUSTED,
    LEGACY_READ_ONLY,
}

internal data class GatewaySessionAuth(
    val mode: GatewaySessionAuthMode,
    val trustId: String? = null,
    val sessionId: String? = null,
    val expiresAtMs: Long? = null,
)

/**
 * Request-local authenticated identity. The dispatcher installs this only after validating the
 * supplied V3.3 session credential. User-controlled action JSON can neither set nor persist it.
 */
internal object GatewaySessionExecutionContext {
    private val current = ThreadLocal<GatewaySessionAuth?>()

    fun currentTrusted(): GatewaySessionAuth? = current.get()?.takeIf { it.mode == GatewaySessionAuthMode.V33_TRUSTED }

    fun <T> withAuth(auth: GatewaySessionAuth?, block: () -> T): T {
        val previous = current.get()
        current.set(auth)
        return try {
            block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}

internal object GatewaySessionStore {
    private const val PREFS = "cyclone_pc_gateway_v293"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ROTATED_AT = "rotated_at"
    private val random = SecureRandom()

    /**
     * Transitional V3.2 pairing credential. It remains memory-only and is restricted to the
     * explicit legacy read-only operation set by GatewayDispatcher. Normal V3.3 USB trust never
     * displays, copies or depends on this value.
     */
    @Volatile private var legacyToken: String? = null

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun token(context: Context): String? = if (enabled(context)) legacyToken else null

    /** Enable the V3.3 gateway after visible phone trust without creating a legacy bearer. */
    @Synchronized
    fun enableTrusted(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putLong(KEY_ROTATED_AT, System.currentTimeMillis())
            .apply()
    }

    /** Explicit transition helper used only by the old four-letter compatibility flow. */
    @Synchronized
    fun enable(context: Context): String {
        val token = newToken()
        legacyToken = token
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putLong(KEY_ROTATED_AT, System.currentTimeMillis())
            .apply()
        return token
    }

    @Synchronized
    fun disable(context: Context) {
        legacyToken = null
        prefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
    }

    @Synchronized
    fun rotate(context: Context): String {
        if (!enabled(context)) return enable(context)
        val token = newToken()
        legacyToken = token
        prefs(context).edit().putLong(KEY_ROTATED_AT, System.currentTimeMillis()).apply()
        return token
    }

    fun rotatedAt(context: Context): Long = prefs(context).getLong(KEY_ROTATED_AT, 0L)

    fun resolveAuth(context: Context, supplied: String): GatewaySessionAuth? {
        if (supplied.isBlank() || !enabled(context)) return null
        GatewayV33TrustManager.authenticateSession(context, supplied)?.let { session ->
            return GatewaySessionAuth(
                mode = GatewaySessionAuthMode.V33_TRUSTED,
                trustId = session.trustId,
                sessionId = session.sessionId,
                expiresAtMs = session.expiresAtMs,
            )
        }
        if (GatewayAuth.matches(token(context), supplied)) {
            return GatewaySessionAuth(GatewaySessionAuthMode.LEGACY_READ_ONLY)
        }
        return null
    }

    fun authenticate(context: Context, supplied: String): Boolean = resolveAuth(context, supplied) != null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun newToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
