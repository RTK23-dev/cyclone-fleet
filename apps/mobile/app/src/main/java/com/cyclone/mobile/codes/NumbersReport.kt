package com.cyclone.mobile.codes

import org.json.JSONArray
import org.json.JSONObject

/**
 * `numbers.list` (alpha.102): what the PC's Numbers page shows for this phone. Pure.
 *
 * - **Numbers only:** each SIM's own number (with its slot) and the numbers the owner confirmed in Settings → Codes.
 *   Never a text, a sender or a code.
 * - **One row per number:** the same number written two ways counts once; the SIM's version wins.
 */
object NumbersReport {
    const val MAX = 8

    fun build(enabled: Boolean, canRead: Boolean, sims: List<Pair<Int, String>>, confirmed: List<String>): JSONObject {
        val seen = mutableSetOf<String>()
        val rows = JSONArray()
        fun add(number: String, source: String, slot: Int?) {
            val digits = AutoCodePolicy.digits(number)
            if (digits.length !in 6..15 || rows.length() >= MAX || !seen.add(digits.takeLast(9))) return
            val clean = (if (number.trim().startsWith("+")) "+" else "") + digits
            rows.put(JSONObject().put("number", clean).put("source", source).put("slot", slot ?: JSONObject.NULL))
        }
        sims.forEach { (slot, number) -> add(number, "sim", slot) }
        confirmed.forEach { add(it, "confirmed", null) }
        return JSONObject().put("enabled", enabled).put("canRead", canRead).put("numbers", rows)
    }
}
