package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.mind.MemoryCarry
import com.cyclone.mobile.mind.mission.MindMissions
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 40 P2, Cyclone Carry: on every profile switch the Cyclone you leave packs what it knows, and the Cyclone you
 * open takes it in (see [CarryRules] for what crosses and how it merges). Packing and taking in happen inside each
 * profile's own Cyclone, so people memory is opened with one profile's Keystore key and sealed again with the other's;
 * in between it only exists inside an encrypted bundle ([ProfileTransferCipher.seal]).
 *
 * Trusted local switching only, like the rest of profiles: no model tool, no gateway call.
 */
internal object ProfileCarry {
    private const val PREFS = "cyclone_carry"
    private const val KEY_LAST = "last"
    private const val KEY_SENT_OK = "sent_ok"

    /** This profile's carry identity: [CarryRules.MAIN] for the main profile, else its `Cyclone_…` id; and its name. */
    fun me(context: Context): Pair<String, String> {
        val origin = context.createDeviceProtectedStorageContext().getSharedPreferences("cyclone_profile_origin", Context.MODE_PRIVATE)
            .getString("profile", null)?.takeIf(ProfileSetupPlan::validProfileName)
            ?: return CarryRules.MAIN to CarryRules.MAIN_LABEL
        val label = runCatching { ProfileRegistryStore.records(context).firstOrNull { it.id == origin }?.label }.getOrNull()
        return origin to (label?.takeIf { it.isNotBlank() } ?: "Another profile")
    }

    /** Everything this profile carries, as plain JSON: it is sealed before it leaves this process. */
    fun pack(context: Context): JSONObject {
        AdaptiveBrainRuntime.initialize(context)
        val (me, label) = me(context)
        return JSONObject().put("v", CarryRules.VERSION).put("from", me).put("from_label", label).put("at", System.currentTimeMillis())
            .put("memory", MindMissions.memory(context).export(me, label).toJson())
            .put("brain", AdaptiveBrainRuntime.store.carryRows())
            .put("settings", settings(context))
    }

    /** Takes in a bundle from another profile, and remembers what came for Profiles to show. */
    fun absorb(context: Context, bundle: JSONObject): CarryReport {
        require(bundle.optInt("v") == CarryRules.VERSION) { "Unknown carry bundle." }
        val (me, _) = me(context)
        val from = bundle.getString("from")
        require(from != me && (from == CarryRules.MAIN || ProfileSetupPlan.validProfileName(from))) { "Carry identity mismatch." }
        AdaptiveBrainRuntime.initialize(context)
        val memory = MindMissions.memory(context).absorb(MemoryCarry.Parcel.fromJson(bundle.optJSONObject("memory") ?: JSONObject()), me)
        val skills = AdaptiveBrainRuntime.store.absorbRows(bundle.optJSONObject("brain") ?: JSONObject())
        runCatching { AdaptiveBrainRuntime.store.writeMirror() }
        val settings = applySettings(context, bundle.optJSONArray("settings") ?: JSONArray())
        val report = CarryReport(System.currentTimeMillis(), bundle.optString("from_label").take(60).ifBlank { "another profile" },
            memory.added + memory.updated, memory.removed, skills, settings)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST, report.toJson().toString()).apply()
        return report
    }

    /** Whether the last carry out of this profile arrived, for Profiles to say so when it didn't. */
    fun noteSent(context: Context, ok: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SENT_OK, ok).apply()
    }

    fun lastSentFailed(context: Context): Boolean = !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SENT_OK, true)

    fun lastReport(context: Context): CarryReport? = runCatching {
        CarryReport.fromJson(JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)!!))
    }.getOrNull()

    private fun settings(context: Context): JSONArray {
        val out = JSONArray()
        CarryRules.settings.keys.forEach { file ->
            context.getSharedPreferences(file, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (!CarryRules.carriesSetting(file, key, value)) return@forEach
                val type = when (value) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; else -> "s" }
                out.put(JSONObject().put("file", file).put("key", key).put("type", type).put("value", value))
            }
        }
        return out
    }

    private fun applySettings(context: Context, settings: JSONArray): Int {
        val current = CarryRules.settings.keys.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all }
        val edits = CarryRules.settings.keys.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit() }
        var count = 0
        for (index in 0 until settings.length()) {
            val entry = settings.optJSONObject(index) ?: continue
            val file = entry.optString("file")
            val key = entry.optString("key")
            val edit = edits[file] ?: continue
            val value: Any = when (entry.optString("type")) {
                "b" -> entry.optBoolean("value")
                "i" -> entry.optInt("value")
                "l" -> entry.optLong("value")
                "f" -> entry.optDouble("value").toFloat()
                "s" -> entry.optString("value")
                else -> continue
            }
            if (!CarryRules.carriesSetting(file, key, value) || current[file]?.get(key) == value) continue
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
            }
            count++
        }
        edits.values.forEach { it.apply() }
        return count
    }
}
