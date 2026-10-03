package com.cyclone.mobile.secrets

import java.net.URI
import java.security.MessageDigest

enum class SecretPersona(val wireValue: String) {
    LIVE("live"),
    MAPPING("mapping");

    companion object {
        fun parse(value: String): SecretPersona =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("persona must be live or mapping")
    }
}

data class SecretSlotKey private constructor(
    val placeId: String,
    val persona: SecretPersona,
    val slotName: String,
) {
    companion object {
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val SLOT_NAME = Regex("^[A-Za-z][A-Za-z0-9._-]{0,63}$")

        fun of(placeId: String, persona: SecretPersona, slotName: String): SecretSlotKey {
            require(placeId.length in 9..512 && placeId.none(Char::isISOControl)) { "invalid placeId" }
            when {
                placeId.startsWith("package:") -> {
                    require(PACKAGE_NAME.matches(placeId.removePrefix("package:"))) { "invalid package placeId" }
                }
                placeId.startsWith("chrome:") -> {
                    val origin = placeId.removePrefix("chrome:")
                    val uri = runCatching { URI(origin) }.getOrNull()
                    require(
                        uri != null &&
                            uri.scheme in setOf("http", "https") &&
                            !uri.host.isNullOrBlank() &&
                            uri.path.orEmpty().isEmpty() &&
                            uri.query == null &&
                            uri.fragment == null
                    ) { "invalid Chrome origin placeId" }
                    val normalized = buildString {
                        append(uri!!.scheme).append("://").append(uri.host)
                        if (uri.port != -1) append(':').append(uri.port)
                    }
                    require(normalized == origin) { "Chrome placeId must contain an origin only" }
                }
                else -> error("placeId must use package: or chrome:")
            }
            require(SLOT_NAME.matches(slotName)) { "invalid slot name" }
            return SecretSlotKey(placeId, persona, slotName)
        }
    }

    internal fun canonicalMetadata(): String =
        placeId + "|" + persona.wireValue + "|" + slotName

    internal fun storageId(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalMetadata().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

data class SecretSlotMetadata(
    val key: SecretSlotKey,
    val present: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

data class SecretRequestMetadata(
    val placeId: String,
    val persona: SecretPersona,
    val slot: String,
    val reason: String,
) {
    init {
        SecretSlotKey.of(placeId, persona, slot)
        require(reason.length in 1..120 && SAFE_REASON.matches(reason)) {
            "reason must be a bounded safe label"
        }
        require(!SECRET_BEARING_REASON.containsMatchIn(reason)) { "reason must not contain a secret value" }
    }

    fun key(): SecretSlotKey = SecretSlotKey.of(placeId, persona, slot)

    private companion object {
        val SAFE_REASON = Regex("^[A-Za-z0-9][A-Za-z0-9 ._/-]{0,119}$")
        val SECRET_BEARING_REASON = Regex(
            "(?i)(?:bearer\\s+\\S{8,}|sk-[A-Za-z0-9_-]{8,}|" +
                "(?:password|passcode|passwd|otp|token|secret|api[_ -]?key|cookie)\\s*[:=]\\s*\\S+)",
        )
    }
}

data class SecretWallRequest(
    val request: SecretRequestMetadata,
    val target: SecretFillTarget,
)

data class SecretFillTarget(
    val elementId: String,
    val observationId: String,
    val sessionId: String = "default-foreground",
    val displayId: Int = 0,
) {
    init {
        require(elementId.isNotBlank() && elementId.length <= 256) { "invalid elementId" }
        require(observationId.isNotBlank() && observationId.length <= 160) { "invalid observationId" }
        require(sessionId.isNotBlank() && sessionId.length <= 160) { "invalid sessionId" }
        require(displayId >= 0) { "invalid displayId" }
    }
}

enum class SecretUseStatus {
    FILLED,
    STORED,
    SKIPPED,
    CANCELLED,
    MISSING,
    FAILED,
    ALREADY_USED,
}

data class SecretUseResult(
    val status: SecretUseStatus,
    val verified: Boolean = false,
    val errorCode: String? = null,
) {
    val taskMayResume: Boolean get() = status == SecretUseStatus.FILLED && verified
    val taskTerminal: Boolean get() = false
}

interface SecretSlotCatalog {
    fun metadata(key: SecretSlotKey): SecretSlotMetadata?
    fun slots(placeId: String, persona: SecretPersona): List<SecretSlotMetadata>
    fun allSlots(): List<SecretSlotMetadata>
    fun hasSlot(key: SecretSlotKey): Boolean = metadata(key)?.present == true
}

internal data class SecretFillExecution(
    val performed: Boolean,
    val verified: Boolean,
    val errorCode: String? = null,
)

internal object SecretDiagnostics {
    fun line(key: SecretSlotKey, result: SecretUseResult): String =
        "secret place=" + key.placeId +
            " persona=" + key.persona.wireValue +
            " slot=" + key.slotName +
            " status=" + result.status.name.lowercase() +
            " verified=" + result.verified +
            " error=" + (result.errorCode ?: "none")
}
