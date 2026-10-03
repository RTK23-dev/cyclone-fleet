package com.cyclone.mobile.secrets

import android.content.Context
import java.net.URI
import java.security.interfaces.ECPublicKey
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Plan 33 C2: sealed delivery. The owner's browser seals one vault secret to this phone's device key, for one Command
 * Center task, one slot, one app or site, until a time. The PC relays the envelope and cannot open it.
 *
 * Here the phone:
 * - checks the envelope's bound data (its own key, the task, the slot, the place, the time, a lease never seen);
 * - opens it with the device key inside Android Keystore;
 * - holds the value in memory for that one mission only (never on disk, never in the model's context);
 * - fills it once through [OneShotSecretLease] when the Mind asks for that slot on that app or site;
 * - reports only the lease's outcome (used, failed, expired, unused) and forgets the value.
 *
 * A one-time code slot ("otp") carries an authenticator seed; the phone computes the code at fill time.
 *
 * Plan 48 run 4: a verification code a Cyclone Ports plugin delivered (`code.in`) arrives the same way, sealed by the
 * PC's Port Hub to this key for one run and one app or site ([openCode]). It is held as the "code" slot and filled
 * once, before the "otp" slot, when the Mind asks for a one-time code.
 */
internal object SealedDelivery {
    const val INFO = "cyclone-sealed-delivery/v1"
    const val CODE_INFO = "cyclone-port-code/v1"
    const val CODE_SLOT = "code"
    private val RUN_ID = Regex("^[A-Za-z0-9_-]{4,80}$")
    val SLOTS = setOf("password", "otp")
    private val LEASE_ID = Regex("^ls_[A-Za-z0-9_-]{12,40}$")
    private val TASK_ID = Regex("^tsk_[A-Za-z0-9_-]{6,40}$")

    class Envelope(val leaseId: String, val slot: String, val enc: ByteArray, val ct: ByteArray, val aad: String)

    class Rejected(val leaseId: String, val code: String) : Exception(code)

    private class Held(val leaseId: String, val place: String, val value: CharArray, val expiresAtMs: Long)

    /** Delivered and not yet used, per mission and slot. Memory only. */
    private val held = mutableMapOf<String, MutableMap<String, Held>>()
    private val outcomes = LinkedHashMap<String, String>()
    private val lock = Any()

    /** Seams: production uses the Keystore device key, the wall clock and SharedPreferences. */
    internal var ownFingerprint: () -> String = { DeviceKey.ensure().fingerprint }
    internal var ownPublic: () -> ByteArray = { DeviceKey.ensure().raw }
    internal var decap: (ECPublicKey) -> ByteArray = { DeviceKey.decap(it) }
    internal var params: () -> java.security.spec.ECParameterSpec = { DeviceKey.params() }
    internal var clock: () -> Long = System::currentTimeMillis
    internal var usedLeases: UsedLeases = UsedLeases.Memory()

    fun install(context: Context) {
        if (usedLeases is UsedLeases.Memory) usedLeases = UsedLeases.Prefs(context.applicationContext)
    }

    fun parse(json: JSONObject): Envelope {
        val leaseId = json.optString("leaseId")
        if (!LEASE_ID.matches(leaseId)) throw Rejected(leaseId.take(40), "LEASE_ID")
        if (json.keys().asSequence().any { it !in setOf("leaseId", "slot", "enc", "ct", "aad") }) throw Rejected(leaseId, "ENVELOPE_FIELDS")
        val slot = json.optString("slot")
        if (slot !in SLOTS) throw Rejected(leaseId, "SLOT")
        val enc = runCatching { Base64.getDecoder().decode(json.optString("enc")) }.getOrNull()
        val ct = runCatching { Base64.getDecoder().decode(json.optString("ct")) }.getOrNull()
        val aad = json.optString("aad")
        if (enc == null || enc.size != 65 || ct == null || ct.size !in 17..8_192 || aad.isEmpty() || aad.length > 1_000) throw Rejected(leaseId, "ENVELOPE_SHAPE")
        return Envelope(leaseId, slot, enc, ct, aad)
    }

    /**
     * Checks and opens every envelope for [taskId], all or nothing. Returns the opened values keyed by slot; the caller
     * attaches them to the mission with [hold] or wipes them with [wipe].
     */
    fun open(taskId: String, envelopes: List<Envelope>): Map<String, Pair<Envelope, CharArray>> {
        if (!TASK_ID.matches(taskId)) throw Rejected("", "TASK_ID")
        val opened = linkedMapOf<String, Pair<Envelope, CharArray>>()
        try {
            for (envelope in envelopes) {
                val bound = runCatching { JSONObject(envelope.aad) }.getOrNull() ?: throw Rejected(envelope.leaseId, "AAD")
                val place = bound.optString("place")
                when {
                    bound.keys().asSequence().toSet() != setOf("deviceKey", "expiresAt", "leaseId", "place", "slot", "taskId") -> throw Rejected(envelope.leaseId, "AAD_FIELDS")
                    bound.optString("leaseId") != envelope.leaseId -> throw Rejected(envelope.leaseId, "AAD_LEASE")
                    bound.optString("slot") != envelope.slot -> throw Rejected(envelope.leaseId, "AAD_SLOT")
                    bound.optString("taskId") != taskId -> throw Rejected(envelope.leaseId, "AAD_TASK")
                    bound.optString("deviceKey") != ownFingerprint() -> throw Rejected(envelope.leaseId, "NOT_FOR_THIS_PHONE")
                    bound.optLong("expiresAt") <= clock() -> throw Rejected(envelope.leaseId, "EXPIRED")
                    !validPlace(place) -> throw Rejected(envelope.leaseId, "AAD_PLACE")
                    envelope.slot in opened -> throw Rejected(envelope.leaseId, "DUPLICATE_SLOT")
                    !usedLeases.claim(envelope.leaseId) -> throw Rejected(envelope.leaseId, "REPLAYED")
                }
                val plain = try {
                    Hpke.open(envelope.enc, envelope.ct, INFO.toByteArray(), envelope.aad.toByteArray(), ownPublic(), decap, params())
                } catch (_: Exception) {
                    throw Rejected(envelope.leaseId, "OPEN_FAILED")
                }
                val chars = String(plain, Charsets.UTF_8).toCharArray()
                plain.fill(0)
                if (chars.isEmpty() || chars.size > 4_096) {
                    chars.fill('\u0000')
                    throw Rejected(envelope.leaseId, "VALUE_SIZE")
                }
                opened[envelope.slot] = envelope to chars
            }
            return opened
        } catch (error: Exception) {
            wipe(opened)
            throw error
        }
    }

    /**
     * Plan 48 run 4: opens a verification code the PC's Port Hub sealed to this phone for [runId] and [place], and holds
     * it for [missionId] as the "code" slot. Returns its length (all the run learns). Throws [Rejected] otherwise.
     */
    fun openCode(missionId: String, runId: String, place: String, json: JSONObject): Int {
        val leaseId = json.optString("leaseId")
        if (!LEASE_ID.matches(leaseId)) throw Rejected(leaseId.take(40), "LEASE_ID")
        if (json.keys().asSequence().toSet() != setOf("leaseId", "enc", "ct", "aad")) throw Rejected(leaseId, "ENVELOPE_FIELDS")
        val enc = runCatching { Base64.getDecoder().decode(json.optString("enc")) }.getOrNull()
        val ct = runCatching { Base64.getDecoder().decode(json.optString("ct")) }.getOrNull()
        val aad = json.optString("aad")
        if (enc == null || enc.size != 65 || ct == null || ct.size !in 17..64 || aad.isEmpty() || aad.length > 600) throw Rejected(leaseId, "ENVELOPE_SHAPE")
        if (!RUN_ID.matches(runId) || !validPlace(place)) throw Rejected(leaseId, "RUN")
        val bound = runCatching { JSONObject(aad) }.getOrNull() ?: throw Rejected(leaseId, "AAD")
        when {
            bound.keys().asSequence().toSet() != setOf("deviceKey", "expiresAt", "leaseId", "place", "runId", "slot") -> throw Rejected(leaseId, "AAD_FIELDS")
            bound.optString("leaseId") != leaseId -> throw Rejected(leaseId, "AAD_LEASE")
            bound.optString("slot") != CODE_SLOT -> throw Rejected(leaseId, "AAD_SLOT")
            bound.optString("runId") != runId -> throw Rejected(leaseId, "AAD_RUN")
            bound.optString("place") != place -> throw Rejected(leaseId, "AAD_PLACE")
            bound.optString("deviceKey") != ownFingerprint() -> throw Rejected(leaseId, "NOT_FOR_THIS_PHONE")
            bound.optLong("expiresAt") <= clock() -> throw Rejected(leaseId, "EXPIRED")
            !usedLeases.claim(leaseId) -> throw Rejected(leaseId, "REPLAYED")
        }
        val plain = try {
            Hpke.open(enc, ct, CODE_INFO.toByteArray(), aad.toByteArray(), ownPublic(), decap, params())
        } catch (_: Exception) {
            throw Rejected(leaseId, "OPEN_FAILED")
        }
        val chars = String(plain, Charsets.UTF_8).toCharArray()
        plain.fill(0)
        if (chars.size !in 3..12 || chars.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-') }) {
            chars.fill('\u0000')
            throw Rejected(leaseId, "VALUE_SIZE")
        }
        synchronized(lock) {
            val slots = held.getOrPut(missionId) { mutableMapOf() }
            slots.remove(CODE_SLOT)?.let { old ->
                old.value.fill('\u0000')
                outcomes[old.leaseId] = "unused"
            }
            slots[CODE_SLOT] = Held(leaseId, place, chars, bound.optLong("expiresAt"))
            outcomes[leaseId] = "delivered"
            trim()
        }
        return chars.size
    }

    fun hold(missionId: String, opened: Map<String, Pair<Envelope, CharArray>>) = synchronized(lock) {
        val slots = held.getOrPut(missionId) { mutableMapOf() }
        for ((slot, pair) in opened) {
            val bound = JSONObject(pair.first.aad)
            slots[slot] = Held(pair.first.leaseId, bound.optString("place"), pair.second, bound.optLong("expiresAt"))
            outcomes[pair.first.leaseId] = "delivered"
        }
        trim()
    }

    fun wipe(opened: Map<String, Pair<Envelope, CharArray>>) {
        opened.values.forEach { it.second.fill('\u0000') }
    }

    /** Whether a delivered value waits for [slot] in [missionId] (for the Mind's prompt; never the value). */
    fun has(missionId: String, slot: String): Boolean = synchronized(lock) { held[missionId]?.containsKey(slot) == true }

    class Taken(val leaseId: String, val lease: OneShotSecretLease)

    /**
     * The delivered value for [slot], once, as a one-shot lease; only on the app or site it was sealed for. A one-time
     * code slot turns its seed into the current code here. Null when nothing (valid) waits.
     */
    fun take(missionId: String, slot: String, currentPlace: String): Taken? = synchronized(lock) {
        val slots = held[missionId] ?: return null
        val entry = slots[slot] ?: return null
        if (entry.expiresAtMs <= clock()) {
            slots.remove(slot)
            entry.value.fill('\u0000')
            outcomes[entry.leaseId] = "expired"
            return null
        }
        if (!placeMatches(currentPlace, entry.place)) return null
        slots.remove(slot)
        val value = if (slot == "otp") {
            val code = runCatching { totp(entry.value, clock()) }.getOrNull()
            entry.value.fill('\u0000')
            code ?: run {
                outcomes[entry.leaseId] = "failed"
                return null
            }
        } else entry.value
        Taken(entry.leaseId, OneShotSecretLease(value))
    }

    fun report(leaseId: String, state: String) = synchronized(lock) { outcomes[leaseId] = state }

    /** A mission ended: anything unused is wiped and reported unused. */
    fun finish(missionId: String) = synchronized(lock) {
        held.remove(missionId)?.values?.forEach {
            it.value.fill('\u0000')
            outcomes[it.leaseId] = "unused"
        }
    }

    fun outcomes(leaseIds: Collection<String>): Map<String, String> = synchronized(lock) {
        leaseIds.associateWith { outcomes[it] ?: "unknown" }
    }

    private fun trim() {
        while (outcomes.size > 500) outcomes.remove(outcomes.keys.first())
    }

    internal fun resetForTests() = synchronized(lock) {
        held.values.forEach { slots -> slots.values.forEach { it.value.fill('\u0000') } }
        held.clear()
        outcomes.clear()
        usedLeases = UsedLeases.Memory()
    }

    // ---------------------------------------------------------------------------------------------------------- places

    fun validPlace(place: String): Boolean = when {
        place.startsWith("package:") -> PACKAGE.matches(place.removePrefix("package:"))
        place.startsWith("chrome:https://") -> HOST.matches(place.removePrefix("chrome:https://"))
        else -> false
    }

    /** An app matches its package exactly; a site matches its host or any subdomain of it, over https only. */
    fun placeMatches(current: String, bound: String): Boolean {
        if (bound.startsWith("package:")) return current == bound
        val host = bound.removePrefix("chrome:https://")
        val origin = current.removePrefix("chrome:")
        if (!current.startsWith("chrome:https://")) return false
        val currentHost = runCatching { URI(origin).host }.getOrNull()?.lowercase() ?: return false
        return currentHost == host || currentHost.endsWith(".$host")
    }

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val HOST = Regex("^[a-z0-9-]+(?:\\.[a-z0-9-]+)+$")

    // ---------------------------------------------------------------------------------------------------------- TOTP

    /** RFC 6238 (SHA-1, 6 digits, 30 s) from a base32 seed. */
    fun totp(seed: CharArray, nowMs: Long): CharArray {
        val clean = String(seed).uppercase().filter { it in 'A'..'Z' || it in '2'..'7' }
        require(clean.length >= 16) { "not a seed" }
        val key = base32(clean)
        val counter = ByteArray(8)
        var step = nowMs / 1000 / 30
        for (i in 7 downTo 0) {
            counter[i] = (step and 0xff).toByte()
            step = step shr 8
        }
        val mac = Mac.getInstance("HmacSHA1").run { init(SecretKeySpec(key, "HmacSHA1")); doFinal(counter) }
        key.fill(0)
        val offset = mac.last().toInt() and 0x0f
        val bin = ((mac[offset].toInt() and 0x7f) shl 24) or ((mac[offset + 1].toInt() and 0xff) shl 16) or
            ((mac[offset + 2].toInt() and 0xff) shl 8) or (mac[offset + 3].toInt() and 0xff)
        return (bin % 1_000_000).toString().padStart(6, '0').toCharArray()
    }

    private fun base32(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in text) {
            buffer = (buffer shl 5) or alphabet.indexOf(c)
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}

/** Lease ids this phone has accepted, so an envelope can never be used twice. Ids only; never a value. */
internal interface UsedLeases {
    /** True the first time [leaseId] is claimed, false ever after. */
    fun claim(leaseId: String): Boolean

    class Memory : UsedLeases {
        private val seen = mutableSetOf<String>()
        @Synchronized override fun claim(leaseId: String): Boolean = seen.add(leaseId)
    }

    class Prefs(context: Context) : UsedLeases {
        private val prefs = context.getSharedPreferences("cyclone_sealed_leases", Context.MODE_PRIVATE)
        @Synchronized override fun claim(leaseId: String): Boolean {
            val seen = prefs.getStringSet("used", emptySet()).orEmpty()
            if (leaseId in seen) return false
            // Keep the newest 2,000 ids; lease ids carry their creation time, so old ones are also expired.
            val next = (seen + leaseId).let { if (it.size > 2_000) it.sorted().takeLast(2_000).toSet() else it }
            prefs.edit().putStringSet("used", next).commit()
            return true
        }
    }
}
