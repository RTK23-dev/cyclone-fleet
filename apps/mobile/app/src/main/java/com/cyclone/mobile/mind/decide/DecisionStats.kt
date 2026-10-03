package com.cyclone.mobile.mind.decide

import org.json.JSONArray
import org.json.JSONObject

/**
 * The numbers behind Cyclone's routing (alpha 89), from the lessons on this phone: who decides and how fast, how often
 * Instant's results are verified, and how well the phone model agrees with JEV per action. The phone's health report
 * carries them to the PC, where Glass shows them and the Lab reads them. Counts and times only: no request text. Pure.
 */
object DecisionStats {
    fun summary(lessons: List<Lesson>, bar: Double, provider: String, phoneModel: String, nowMs: Long): JSONObject {
        val day = lessons.filter { nowMs - it.atMs <= DAY_MS }
        val byDecider = JSONObject().also { o -> Decider.entries.forEach { d -> o.put(d.name.lowercase(), lessons.count { it.decider == d }) } }
        val remote = lessons.filter { it.decider == Decider.DECISIONS }.map { it.decideMs }
        val local = lessons.filter { it.decider == Decider.PHONE }.map { it.decideMs }
        val instant = lessons.filter { it.decision.mode == "instant" && it.outcome in setOf(Outcome.VERIFIED, Outcome.FAILED, Outcome.PROMOTED) }
        val records = Earning.records(lessons, bar)
        val shadow = lessons.filter { l -> l.shadow != null && l.teaches(bar) }
        return JSONObject()
            .put("provider", provider)
            .put("phoneModel", phoneModel)
            .put("lessons", lessons.size)
            .put("lastDay", day.size)
            .put("byDecider", byDecider)
            .put("onPhoneShare", share(lessons.count { it.decider in LOCAL }, lessons.size))
            .put("decisionsMs", JSONObject().put("p50", percentile(remote, 50)).put("p95", percentile(remote, 95)).put("count", remote.size))
            .put("phoneModelMs", JSONObject().put("p50", percentile(local, 50)).put("p95", percentile(local, 95)).put("count", local.size))
            .put("instantVerified", share(instant.count { it.outcome == Outcome.VERIFIED }, instant.size))
            .put("instantChecked", instant.size)
            .put("shadowAgreement", share(shadow.count { it.shadow!!.agrees(it.decision) }, shadow.size))
            .put("shadowChecked", shadow.size)
            .put("teaching", lessons.count { it.teaches(bar) })
            .put("earned", JSONArray(records.filter { it.earned }.map { it.intent }))
            .put("actions", JSONArray(records.map { r ->
                JSONObject().put("intent", r.intent).put("samples", r.samples).put("agreement", round(r.agreement))
                    .put("phoneDecisions", r.phoneDecisions).put("phoneFailures", r.phoneFailures).put("earned", r.earned)
            }))
    }

    /** Settled without a network call: the grammar, the phone's own answers, the rules and the phone model. */
    private val LOCAL = setOf(Decider.GRAMMAR, Decider.ANSWER, Decider.RULES, Decider.PHONE, Decider.SETTING)
    private const val DAY_MS = 24 * 3600 * 1000L

    fun percentile(values: List<Long>, p: Int): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[((p / 100.0) * (sorted.size - 1)).let { Math.round(it).toInt() }.coerceIn(0, sorted.lastIndex)]
    }

    private fun share(part: Int, whole: Int): Any = if (whole == 0) JSONObject.NULL else round(part.toDouble() / whole)
    private fun round(value: Double): Double = Math.round(value * 1000) / 1000.0
}
