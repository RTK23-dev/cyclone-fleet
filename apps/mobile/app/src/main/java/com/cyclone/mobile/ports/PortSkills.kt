package com.cyclone.mobile.ports

import org.json.JSONArray
import org.json.JSONObject

/** Bounded, owner-approved guidance from the paired PC, withdrawn when polling stops. No plugin code runs here. */
object PortSkills {
    private val NAME = Regex("[a-z][a-z0-9-]{1,40}")
    fun parse(raw: JSONArray): List<JSONObject> {
        require(raw.length() <= 8 && raw.toString().length <= 32_000) { "Ports skills are too large." }
        return (0 until raw.length()).map { index ->
            val s = raw.getJSONObject(index)
            require(s.keys().asSequence().all { it in setOf("name", "title", "description", "instructions", "ports", "apps", "routines") })
            val name = s.getString("name"); require(NAME.matches(name))
            for ((key, max) in listOf("title" to 100, "description" to 1000, "instructions" to 8000)) {
                val v = s.getString(key); require(v.length <= max && !v.contains('\u0000'))
            }
            for (key in listOf("ports", "apps", "routines")) {
                val a = s.getJSONArray(key); require(a.length() <= 30)
                for (i in 0 until a.length()) require(a.get(i) is String && a.getString(i).length in 1..120)
            }
            JSONObject(s.toString())
        }
    }
    fun matches(s: JSONObject, app: String?, routine: String?): Boolean =
        permits(s.getJSONArray("apps"), app) && permits(s.getJSONArray("routines"), routine)
    private fun permits(a: JSONArray, value: String?): Boolean = a.length() == 0 || (0 until a.length()).any { a.getString(it) == value }
    fun has(s: JSONObject, port: String): Boolean = s.getJSONArray("ports").let { a -> (0 until a.length()).any { a.getString(it) == port } }
}
