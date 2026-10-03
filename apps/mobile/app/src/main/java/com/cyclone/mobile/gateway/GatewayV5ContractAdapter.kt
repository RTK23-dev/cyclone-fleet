package com.cyclone.mobile.gateway

import com.cyclone.mobile.automation.skill.SkillSecrets
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal data class GatewayV5SecretRequestMetadata(
    val placeId: String,
    val persona: String,
    val slot: String,
    val reason: String,
)

internal interface GatewayV5AtlasSource {
    /** Schema-shaped place summaries; never execution instructions or secret-bearing dynamic data. */
    fun places(): List<JSONObject>

    /** Return null when no map exists so the adapter can emit the canonical unmapped document. */
    fun get(placeId: String, persona: String): JSONObject?
}

internal interface GatewayV5SecretsSource {
    /** Slot names are metadata. Values are presence booleans only. */
    fun slots(placeId: String, persona: String): Map<String, Boolean>

    /** Coordination hook only. There is intentionally no secret-value parameter. */
    fun request(metadata: GatewayV5SecretRequestMetadata)
}

/**
 * Installation seam for Agent 002/003 phone-owned implementations.
 *
 * The defaults preserve Run-1 empty-but-valid behavior. PC/Glass never install these sources.
 */
internal object GatewayV5ContractSources {
    private object EmptyAtlas : GatewayV5AtlasSource {
        override fun places(): List<JSONObject> = emptyList()
        override fun get(placeId: String, persona: String): JSONObject? = null
    }

    private object EmptySecrets : GatewayV5SecretsSource {
        override fun slots(placeId: String, persona: String): Map<String, Boolean> = emptyMap()
        override fun request(metadata: GatewayV5SecretRequestMetadata) = Unit
    }

    @Volatile private var atlas: GatewayV5AtlasSource = EmptyAtlas
    @Volatile private var secrets: GatewayV5SecretsSource = EmptySecrets

    fun atlas(): GatewayV5AtlasSource = atlas
    fun secrets(): GatewayV5SecretsSource = secrets

    @Synchronized fun installAtlas(source: GatewayV5AtlasSource) {
        atlas = source
    }

    @Synchronized fun installSecrets(source: GatewayV5SecretsSource) {
        secrets = source
    }

    @Synchronized internal fun resetForTests() {
        atlas = EmptyAtlas
        secrets = EmptySecrets
    }
}

/**
 * Run-1 V5 contract adapter.
 *
 * Phone remains authoritative. Until Vault/Atlas owners land their stores, these operations return
 * empty-but-valid phone-owned data. This adapter never accepts or emits a secret value.
 */
internal object GatewayV5ContractAdapter {
    private val packageName = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val slotName = Regex("^[A-Za-z][A-Za-z0-9._-]{0,63}$")
    private val safeReason = Regex("^[A-Za-z0-9][A-Za-z0-9 ._/-]{0,119}$")
    private val inlineSecret = Regex(
        "(?i)(password|passcode|passwd|pin|otp|token|secret|api[_-]?key|authorization|cookie|cvv|credential|typed[_-]?(text|value))\\s*[:=]"
    )

    fun dispatch(op: String, args: JSONObject): JSONObject = when (op) {
        "atlas.places" -> atlasPlaces(args)
        "atlas.get" -> atlasGet(args)
        "secrets.slots" -> secretSlots(args)
        "secrets.request" -> secretRequest(args)
        else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported V5 contract operation: $op")
    }

    private fun atlasPlaces(args: JSONObject): JSONObject {
        requireOnly(args, emptySet())
        val places = JSONArray()
        GatewayV5ContractSources.atlas().places().forEach { summary ->
            validateAtlasSummary(summary)
            places.put(JSONObject(summary.toString()))
        }
        return JSONObject().put("places", places)
    }

    private fun atlasGet(args: JSONObject): JSONObject {
        requireOnly(args, setOf("placeId", "persona"))
        val placeId = requiredString(args, "placeId", 512)
        val persona = persona(args)
        val place = place(placeId)
        GatewayV5ContractSources.atlas().get(placeId, persona)?.let { document ->
            validateAtlasDocument(document, placeId, persona)
            return JSONObject(document.toString())
        }
        return JSONObject()
            .put("place", place)
            .put("persona", persona)
            .put("mapStatus", "unmapped")
            .put("screens", JSONArray())
            .put("edges", JSONArray())
            .put("capabilities", JSONArray())
            .put("confidence", 0.0)
            .put("lastObservedAt", JSONObject.NULL)
            .put("lastVerifiedAt", JSONObject.NULL)
    }

    private fun secretSlots(args: JSONObject): JSONObject {
        requireOnly(args, setOf("placeId", "persona"))
        val placeId = requiredString(args, "placeId", 512)
        place(placeId)
        val persona = persona(args)
        val slots = JSONObject()
        GatewayV5ContractSources.secrets().slots(placeId, persona).toSortedMap().forEach { (slot, present) ->
            if (!slotName.matches(slot)) fail("PROTOCOL_MISMATCH", "Vault source returned an invalid slot name.")
            slots.put(slot, present)
        }
        return JSONObject()
            .put("placeId", placeId)
            .put("persona", persona)
            .put("slots", slots)
    }

    private fun secretRequest(args: JSONObject): JSONObject {
        requireOnly(args, setOf("placeId", "persona", "slot", "reason"))
        val placeId = requiredString(args, "placeId", 512)
        place(placeId)
        val persona = persona(args)
        val slot = requiredString(args, "slot", 64)
        if (!slotName.matches(slot)) fail("INVALID_REQUEST", "slot must be metadata only.")
        val reason = requiredString(args, "reason", 120)
        if (!safeReason.matches(reason) || inlineSecret.containsMatchIn(reason)) {
            fail("SECRET_PAYLOAD_REJECTED", "Secret-bearing request payload rejected.")
        }
        val metadata = GatewayV5SecretRequestMetadata(placeId, persona, slot, reason)
        GatewayV5ContractSources.secrets().request(metadata)
        val request = JSONObject()
            .put("placeId", metadata.placeId)
            .put("persona", metadata.persona)
            .put("slot", metadata.slot)
            .put("reason", metadata.reason)
        return JSONObject()
            .put("state", "needs-secret")
            .put("request", request)
    }

    private val atlasDocumentKeys = setOf(
        "place", "persona", "mapStatus", "screens", "edges", "capabilities",
        "confidence", "lastObservedAt", "lastVerifiedAt",
    )
    private val atlasSummaryKeys = setOf(
        "place", "persona", "mapStatus", "confidence", "lastObservedAt", "lastVerifiedAt",
    )
    private val mapStatuses = setOf("unmapped", "partial", "mapped", "stale", "blocked")

    private fun validateAtlasSummary(summary: JSONObject) {
        requireExactKeys(summary, atlasSummaryKeys, "Atlas place summary")
        rejectSecretBearing(summary)
        validateAtlasPlace(summary.getJSONObject("place"))
        personaValue(summary.getString("persona"))
        mapStatus(summary.getString("mapStatus"))
    }

    private fun validateAtlasDocument(document: JSONObject, expectedPlaceId: String, expectedPersona: String) {
        requireExactKeys(document, atlasDocumentKeys, "Atlas document")
        rejectSecretBearing(document)
        val sourcePlace = document.getJSONObject("place")
        validateAtlasPlace(sourcePlace)
        if (sourcePlace.getString("placeId") != expectedPlaceId) {
            fail("PROTOCOL_MISMATCH", "Atlas source returned the wrong placeId.")
        }
        if (document.getString("persona") != expectedPersona) {
            fail("PROTOCOL_MISMATCH", "Atlas source returned the wrong persona.")
        }
        mapStatus(document.getString("mapStatus"))
        document.getJSONArray("screens")
        document.getJSONArray("edges")
        document.getJSONArray("capabilities")
        rejectSecretFactSlots(document.getJSONArray("screens"))
    }

    private fun validateAtlasPlace(source: JSONObject) {
        val placeId = requiredString(source, "placeId", 512)
        val canonical = place(placeId)
        if (source.optString("kind") != canonical.getString("kind")) {
            fail("PROTOCOL_MISMATCH", "Atlas source place kind does not match placeId.")
        }
        val label = requiredString(source, "label", 120)
        if (label.isBlank()) fail("PROTOCOL_MISMATCH", "Atlas source place label is required.")
        if (canonical.getString("kind") == "package" &&
            source.optString("packageName") != canonical.getString("packageName")
        ) {
            fail("PROTOCOL_MISMATCH", "Atlas source packageName does not match placeId.")
        }
        if (canonical.getString("kind") == "chrome-origin" &&
            source.optString("origin").removeSuffix("/") != canonical.getString("origin")
        ) {
            fail("PROTOCOL_MISMATCH", "Atlas source origin does not match placeId.")
        }
    }

    private fun rejectSecretFactSlots(screens: JSONArray) {
        for (index in 0 until screens.length()) {
            val screen = screens.optJSONObject(index) ?: continue
            val slots = screen.optJSONArray("factSlots") ?: continue
            for (slotIndex in 0 until slots.length()) {
                val slot = slots.optJSONObject(slotIndex) ?: continue
                val name = slot.optString("name")
                if (name.isNotBlank() && SkillSecrets.isSecretKey(name)) {
                    fail("SECRET_PAYLOAD_REJECTED", "Atlas source attempted to expose a secret fact slot.")
                }
            }
        }
    }

    private fun rejectSecretBearing(value: Any?) {
        when (value) {
            null, JSONObject.NULL -> Unit
            is JSONObject -> value.keys().asSequence().forEach { key ->
                if (SkillSecrets.isSecretKey(key)) {
                    fail("SECRET_PAYLOAD_REJECTED", "Atlas source attempted to expose a secret-bearing field.")
                }
                rejectSecretBearing(value.get(key))
            }
            is JSONArray -> repeat(value.length()) { index -> rejectSecretBearing(value.get(index)) }
            is String -> if (SkillSecrets.isSecretValue(value)) {
                fail("SECRET_PAYLOAD_REJECTED", "Atlas source attempted to expose a secret-bearing value.")
            }
        }
    }

    private fun requireExactKeys(json: JSONObject, allowed: Set<String>, label: String) {
        val keys = json.keys().asSequence().toSet()
        if (keys != allowed) fail("PROTOCOL_MISMATCH", "$label does not match the Run-1 schema.")
    }

    private fun mapStatus(value: String) {
        if (value !in mapStatuses) fail("PROTOCOL_MISMATCH", "Atlas source returned an invalid mapStatus.")
    }

    private fun personaValue(value: String) {
        if (value != "live" && value != "mapping") fail("PROTOCOL_MISMATCH", "Atlas source returned an invalid persona.")
    }

    private fun persona(args: JSONObject): String {
        val persona = requiredString(args, "persona", 16)
        if (persona != "live" && persona != "mapping") {
            fail("INVALID_REQUEST", "persona must be live or mapping.")
        }
        return persona
    }

    private fun place(placeId: String): JSONObject {
        return when {
            placeId.startsWith("package:") -> {
                val value = placeId.removePrefix("package:")
                if (!packageName.matches(value)) fail("INVALID_REQUEST", "Invalid package placeId.")
                JSONObject()
                    .put("placeId", placeId)
                    .put("kind", "package")
                    .put("label", value.substringAfterLast('.').take(120).ifBlank { value.take(120) })
                    .put("packageName", value)
            }
            placeId.startsWith("chrome:") -> {
                val origin = placeId.removePrefix("chrome:")
                val uri = runCatching { URI(origin) }.getOrNull()
                if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() ||
                    uri.path.orEmpty().let { it.isNotEmpty() && it != "/" } || uri.query != null || uri.fragment != null
                ) {
                    fail("INVALID_REQUEST", "Invalid Chrome origin placeId.")
                }
                val normalizedOrigin = buildString {
                    append(uri.scheme).append("://").append(uri.host)
                    if (uri.port != -1) append(':').append(uri.port)
                }
                if (normalizedOrigin != origin.removeSuffix("/")) {
                    fail("INVALID_REQUEST", "Chrome placeId must contain an origin only.")
                }
                JSONObject()
                    .put("placeId", placeId)
                    .put("kind", "chrome-origin")
                    .put("label", uri.host.take(120))
                    .put("origin", normalizedOrigin)
            }
            else -> fail("INVALID_REQUEST", "placeId must use package: or chrome: identity.")
        }
    }

    private fun requireOnly(args: JSONObject, allowed: Set<String>) {
        val extras = args.keys().asSequence().filter { it !in allowed }.toList()
        if (extras.isNotEmpty()) {
            val secretKey = extras.firstOrNull { key ->
                key.lowercase() in setOf(
                    "password", "passcode", "passwd", "pin", "otp", "token", "secret", "api_key",
                    "authorization", "cookie", "cvv", "credential", "typed_text", "typed_value",
                )
            }
            if (secretKey != null) fail("SECRET_PAYLOAD_REJECTED", "Secret-bearing request payload rejected.")
            fail("INVALID_REQUEST", "Unexpected V5 contract field.")
        }
    }

    private fun requiredString(args: JSONObject, key: String, max: Int): String {
        val value = args.opt(key)
        if (value !is String || value.isBlank() || value.length > max) {
            fail("INVALID_REQUEST", "$key is required.")
        }
        return value
    }

    private fun fail(code: String, message: String): Nothing =
        throw GatewayProtocolException(code, message)
}
