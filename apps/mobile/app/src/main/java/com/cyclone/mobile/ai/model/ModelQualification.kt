package com.cyclone.mobile.ai.model

import java.util.concurrent.ConcurrentHashMap

const val MODEL_QUALIFICATION_TTL_MS: Long = 6L * 60L * 60L * 1000L

data class ModelQualificationSnapshot(
    val modelId: String,
    val qualifiedAtEpochMs: Long,
)

class InMemoryModelQualificationCache(
    private val ttlMs: Long = MODEL_QUALIFICATION_TTL_MS,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val successful = ConcurrentHashMap<String, Long>()
    private var accountFingerprint: String? = null
    @Synchronized fun bindAccount(key: String) {
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        if (fingerprint != accountFingerprint) { successful.clear(); accountFingerprint = fingerprint }
    }

    fun isQualified(profile: ModelProfile): Boolean {
        val at = successful[profile.cycloneId] ?: return false
        if (nowEpochMs() - at <= ttlMs) return true
        successful.remove(profile.cycloneId, at)
        return false
    }

    fun markQualified(profile: ModelProfile): ModelQualificationSnapshot {
        val now = nowEpochMs()
        successful[profile.cycloneId] = now
        return ModelQualificationSnapshot(profile.cycloneId, now)
    }

    fun clear(profile: ModelProfile) {
        successful.remove(profile.cycloneId)
    }
}

object ModelQualificationContract {
    const val SYSTEM_PROMPT = "You are qualifying a Cyclone model adapter. Return strict JSON only. Do not request tools, phone state, screenshots, credentials, task history, or personal data."
    const val USER_PROMPT = "Return exactly one Cyclone-compatible result with status=done, answer=qualified, actions=[], pageSummary=qualification, displaySummary=qualified, and an empty reason."

    fun isQualifiedResult(status: String, answer: String?, actionCount: Int): Boolean =
        status.equals("done", ignoreCase = true) && answer == "qualified" && actionCount == 0
}

/** Protects Brain/navigation learning from provider failures that happened before phone mutation. */
object ModelFailureCausality {
    fun mayRecordNegativeNavigation(phoneMutationCount: Int): Boolean = phoneMutationCount > 0
}
