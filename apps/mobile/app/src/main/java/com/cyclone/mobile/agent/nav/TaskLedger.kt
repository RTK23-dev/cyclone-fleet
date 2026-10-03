package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.automation.skill.SkillSecrets
import com.cyclone.mobile.agent.plan.DestinationAuthority
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import org.json.JSONArray
import org.json.JSONObject

/** Run-local facts only. Raw values may enter the active model context, never a durable export. */
class TaskLedger(private val startedAtMs: Long = System.currentTimeMillis()) {
    class Fact(
        val key: String, val value: String, val sourcePlace: String, val sourceRoom: String,
        val persona: AtlasPersona, val readAtMs: Long,
    ) {
        fun maskedValue(): String = EMAIL.replace(value) { DestinationAuthority.maskEmail(it.value) }
            .let { if (EMAIL.containsMatchIn(value)) it else value.take(1) + "***" }
        override fun toString(): String = "$key=${maskedValue()}"
    }

    private val facts = linkedMapOf<String, Fact>()
    fun get(key: String): Fact? = facts[key]
    fun entries(): List<Fact> = facts.values.toList()

    /** Caller supplies evidence from a fresh live observation, never from a model or a saved map. */
    fun record(
        key: String, value: String, sourcePlace: String, sourceRoom: String,
        persona: AtlasPersona, readAtMs: Long,
    ): Boolean {
        if (persona != AtlasPersona.LIVE || readAtMs < startedAtMs || key !in KEYS ||
            value.isBlank() || value.length > 256 || value.any(Char::isISOControl) ||
            SkillSecrets.isSecretKey(key) || SkillSecrets.isSecretValue(value) ||
            PAYMENT.containsMatchIn(value) || !PLACE.matches(sourcePlace) || !ROOM.matches(sourceRoom)) return false
        if (key == "signed-in-email" && !EMAIL.matches(value)) return false
        val previous = facts[key]
        if (previous != null && readAtMs < previous.readAtMs) return false
        facts[key] = Fact(key, value, sourcePlace, sourceRoom, persona, readAtMs)
        return previous == null || previous.value != value || previous.sourcePlace != sourcePlace || previous.sourceRoom != sourceRoom
    }

    fun modelContext(): JSONArray = json(masked = false)
    fun maskedTrace(): JSONArray = json(masked = true)

    private fun json(masked: Boolean) = JSONArray().also { array ->
        facts.values.forEach { fact -> array.put(JSONObject()
            .put("key", fact.key).put("value", if (masked) fact.maskedValue() else fact.value)
            .put("sourcePlace", fact.sourcePlace).put("sourceRoom", fact.sourceRoom)
            .put("persona", fact.persona.wireValue).put("readAtMs", fact.readAtMs)) }
    }

    companion object {
        private val KEYS = setOf("signed-in-email", "found-username", "thread-with", "connected-network")
        private val EMAIL = Regex("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}")
        private val PAYMENT = Regex("(?i)(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)|\\b[A-Z]{2}\\d{2}[A-Z0-9]{10,30}\\b|\\b\\d{3}-\\d{2}-\\d{4}\\b")
        private val PLACE = Regex("(?:package:[A-Za-z][A-Za-z0-9_.]{1,150}|chrome:https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?)")
        private val ROOM = Regex("[A-Za-z0-9][A-Za-z0-9._:/=_-]{0,255}")
    }
}
