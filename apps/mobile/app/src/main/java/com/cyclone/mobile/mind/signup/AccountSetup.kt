package com.cyclone.mobile.mind.signup

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Plan 43 (T7): Account Setup mode. One run creates one account with an app's [SignupMap] as the plan and one table
 * row's values. The owner approved creating it when they pressed Create accounts in Glass, so the run does not ask
 * again before the final control. The password never comes as a value: it arrives sealed from the vault and is filled
 * with vault_fill. A step only a person can do still pauses the run for that person. Pure.
 */
class AccountSetupPlan(val map: SignupMap, val values: Map<String, String>) {

    /** The plan in the system prompt: every page, its fields and the value for each. */
    fun promptText(): String = buildString {
        append("This mission is Account Setup: create one new ${map.appLabel} account on this phone. The owner already approved ")
        append("creating it (in Cyclone Glass), so press the final control \"${map.finalLabel ?: "the one that creates the account"}\" ")
        append("without asking again. Follow the sign-up map below page by page with exactly these values; never make one up. ")
        append("For a password field use vault_fill what=password (the owner's vault sent it sealed; you never see it). ")
        append("Before you continue from each page, call setup_page with its number, changed=true when the page differs from ")
        append("the map (then work that page out yourself), and check=… when it is a step only a person can do: then ask the ")
        append("owner with owner_ask or hand over, and wait; never try to solve it. When the account exists, call setup_done ")
        append("created=true with the account's handle as the app shows it; if the app refuses (the name is taken, a limit), ")
        append("call setup_done created=false with why, then finish.\n")
        append("Sign-up map of ${map.appLabel}:\n")
        for (page in map.pages) {
            append("Page ${page.index}: \"${page.title}\"")
            page.check?.let {
                if (it == SignupCheck.SMS_CODE) append(" (${it.label}: call setup_page with check=sms_code; when the code goes to this phone, Cyclone fills it from the text itself, otherwise it is a person's step)")
                else append(" (a person's step: ${it.label})")
            }
            append("\n")
            for (field in page.fields) {
                val value = when {
                    field.kind == SignupFieldKind.PASSWORD -> "use vault_fill what=password"
                    field.kind == SignupFieldKind.PHOTO -> "skip unless required; then ask the owner"
                    else -> values[field.key]?.let { "\"$it\"" } ?: if (field.required) "not given: ask the owner" else "leave empty"
                }
                append("  - ${field.label} (${field.kind.wire}${if (field.required) "" else ", optional"}): $value\n")
            }
            append("  then press \"${page.continueLabel}\"\n")
        }
        map.finalLabel?.let { append("Finally press \"$it\".\n") }
    }

    companion object {
        const val MAX_VALUES = 40
        private val KEY = Regex("^[a-z0-9_]{1,48}$")

        /** The row's values from the wire: field key -> text. Returns null when they are malformed. */
        fun values(json: JSONObject?): Map<String, String>? {
            if (json == null || json.length() > MAX_VALUES) return null
            val out = linkedMapOf<String, String>()
            for (key in json.keys()) {
                val value = json.opt(key) as? String ?: return null
                if (!KEY.matches(key) || value.length > 300 || value.any { it.code < 32 }) return null
                out[key] = value
            }
            return out
        }
    }
}

/** Where a run is, for the PC: the page of the map, a page that changed, a person's step, and the handle once made. */
data class AccountSetupProgress(
    val state: String = FILLING,
    val page: Int? = null,
    val pages: Int = 0,
    val drift: Int? = null,
    val handle: String? = null,
    val note: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("state", state).put("page", page ?: JSONObject.NULL).put("pages", pages)
        .put("drift", drift ?: JSONObject.NULL).put("handle", handle ?: JSONObject.NULL).put("note", note.take(200))

    companion object {
        const val FILLING = "filling"
        const val VERIFICATION = "verification"
        /** Plan 49: waiting for a code sent by text to this phone; Cyclone fills it itself. */
        const val CODE = "code"
        const val CREATED = "created"
        const val FAILED = "failed"

        private val runs = ConcurrentHashMap<String, AccountSetupProgress>()
        fun set(missionId: String, progress: AccountSetupProgress) {
            if (runs.size > 64) runs.keys.take(runs.size - 32).forEach(runs::remove)
            runs[missionId] = progress
        }
        fun of(missionId: String): AccountSetupProgress? = runs[missionId]
    }
}
