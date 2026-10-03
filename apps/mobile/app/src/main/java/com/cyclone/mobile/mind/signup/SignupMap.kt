package com.cyclone.mobile.mind.signup

import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 43 (T6): an app's sign-up flow as Cyclone learned it, page by page: which fields each page asks for, which
 * control continues, where a person must verify, and which control finally creates the account. It is a schema: a
 * map never holds a value the owner typed (names, dates, addresses, passwords). The PC turns it into a table whose
 * rows are the next accounts to create. Pure.
 */
enum class SignupFieldKind(val wire: String) {
    TEXT("text"), FIRST_NAME("first_name"), LAST_NAME("last_name"), FULL_NAME("full_name"), EMAIL("email"), PHONE("phone"),
    USERNAME("username"), PASSWORD("password"), BIRTHDAY("birthday"), DATE("date"), GENDER("gender"), CHOICE("choice"),
    CHECKBOX("checkbox"), NUMBER("number"), PHOTO("photo");

    companion object { fun of(wire: String?): SignupFieldKind? = entries.firstOrNull { it.wire == wire } }
}

/** A step only a person can do (plan 43 §7). Cyclone never gets around one. */
enum class SignupCheck(val wire: String, val label: String) {
    EMAIL_CODE("email_code", "a code sent by email"), SMS_CODE("sms_code", "a code sent by SMS"), CAPTCHA("captcha", "a CAPTCHA"),
    SELFIE("selfie", "a selfie or face check"), ID_DOCUMENT("id_document", "an ID document"), PHONE_CALL("phone_call", "a phone call"),
    OTHER("other", "another check");

    companion object { fun of(wire: String?): SignupCheck? = entries.firstOrNull { it.wire == wire } }
}

data class SignupField(
    /** Stable within the map (first_name, date_of_birth, …): the PC's column key. */
    val key: String,
    val label: String,
    val kind: SignupFieldKind,
    val required: Boolean,
    /** What the app says about the format ("At least 8 characters"), never an example value. */
    val hint: String = "",
    /** A picker's options as the app shows them (gender, country), never the one picked. */
    val choices: List<String> = emptyList(),
)

data class SignupPage(val index: Int, val title: String, val fields: List<SignupField>, val continueLabel: String, val check: SignupCheck? = null)

data class SignupMap(
    val packageName: String,
    val appLabel: String,
    val appVersion: String,
    val mappedAt: Long,
    val pages: List<SignupPage>,
    /** The control that creates the account; null when the mapping stopped before it. */
    val finalLabel: String?,
    /** True when the whole flow was walked to the account being created. */
    val complete: Boolean,
) {
    val fields: List<SignupField> get() = pages.flatMap { it.fields }
    val checks: List<SignupCheck> get() = pages.mapNotNull { it.check }.distinct()

    fun toJson(): JSONObject = JSONObject()
        .put("package", packageName).put("app", appLabel).put("appVersion", appVersion).put("mappedAt", mappedAt)
        .put("finalLabel", finalLabel ?: JSONObject.NULL).put("complete", complete)
        .put("pages", JSONArray(pages.map { page ->
            JSONObject().put("index", page.index).put("title", page.title).put("continue", page.continueLabel)
                .put("check", page.check?.wire ?: JSONObject.NULL)
                .put("fields", JSONArray(page.fields.map { f ->
                    JSONObject().put("key", f.key).put("label", f.label).put("kind", f.kind.wire).put("required", f.required)
                        .put("hint", f.hint).put("choices", JSONArray(f.choices))
                }))
        }))

    companion object {
        fun fromJson(json: JSONObject): SignupMap? = runCatching {
            val pages = json.getJSONArray("pages")
            SignupMap(
                packageName = json.getString("package"), appLabel = json.getString("app"), appVersion = json.optString("appVersion"),
                mappedAt = json.getLong("mappedAt"), finalLabel = json.optString("finalLabel").takeIf { !json.isNull("finalLabel") && it.isNotBlank() },
                complete = json.optBoolean("complete"),
                pages = (0 until pages.length()).map { i ->
                    val p = pages.getJSONObject(i)
                    val fields = p.getJSONArray("fields")
                    SignupPage(p.getInt("index"), p.getString("title"), (0 until fields.length()).map { j ->
                        val f = fields.getJSONObject(j)
                        val choices = f.optJSONArray("choices") ?: JSONArray()
                        SignupField(f.getString("key"), f.getString("label"), SignupFieldKind.of(f.getString("kind")) ?: SignupFieldKind.TEXT,
                            f.getBoolean("required"), f.optString("hint"), (0 until choices.length()).map { choices.getString(it) })
                    }, p.getString("continue"), SignupCheck.of(p.optString("check").takeIf { !p.isNull("check") }))
                },
            )
        }.getOrNull()
    }
}

/**
 * Records a sign-up while the Mind walks it (the `signup_page`, `signup_final` and `signup_done` tools). Every string
 * is checked: short, no control characters, nothing that looks like a value (an email address, a long number, a
 * secret), and nothing the Mind typed during this mission, so the owner's first-account values never end up in the
 * map.
 */
class SignupRecorder(val packageName: String, private val appLabel: String, private val appVersion: String, private val clock: () -> Long) {
    private val pages = mutableListOf<SignupPage>()
    private val typed = mutableListOf<String>()
    private var finalLabel: String? = null

    val pageCount: Int get() = pages.size

    /** The toolbox reports everything the Mind types in this mission; none of it may appear in the map. */
    fun typed(text: String) {
        text.trim().takeIf { it.length >= 3 }?.let { typed += it.lowercase() }
    }

    /** Records the page on screen. Returns the page, or throws [Refused] with a message for the model. */
    fun page(title: String, fields: List<Map<String, Any?>>, continueLabel: String, check: String?): SignupPage {
        if (pages.size >= MAX_PAGES) throw Refused("A sign-up is mapped in at most $MAX_PAGES pages.")
        if (fields.size > MAX_FIELDS) throw Refused("A page has at most $MAX_FIELDS fields.")
        val keys = pages.flatMap { it.fields }.map { it.key }.toMutableSet()
        val recorded = fields.map { raw ->
            val extra = raw.keys - FIELD_KEYS
            if (extra.isNotEmpty()) throw Refused("A field is {label, kind, required, hint, choices}; ${extra.first()} is not recorded (never values).")
            val label = clean(raw["label"] as? String, "A field's label", 60, required = true)
            val kind = SignupFieldKind.of(raw["kind"] as? String)
                ?: throw Refused("A field's kind is one of: ${SignupFieldKind.entries.joinToString { it.wire }}.")
            val hint = clean(raw["hint"] as? String, "A hint", 120)
            val choices = (raw["choices"] as? List<*>).orEmpty().take(MAX_CHOICES).map { clean(it as? String, "A choice", 60, required = true) }.distinct()
            SignupField(uniqueKey(label, kind, keys).also { keys += it }, label, kind, raw["required"] != false, hint, choices)
        }
        val page = SignupPage(pages.size + 1, clean(title, "The page title", 80, required = true), recorded,
            clean(continueLabel, "The continue control", 60, required = true),
            check?.let { SignupCheck.of(it) ?: throw Refused("A check is one of: ${SignupCheck.entries.joinToString { c -> c.wire }}.") })
        pages += page
        return page
    }

    fun final(control: String) {
        if (pages.isEmpty()) throw Refused("Record the sign-up's pages with signup_page first.")
        finalLabel = clean(control, "The final control", 60, required = true)
    }

    fun finish(complete: Boolean): SignupMap {
        if (pages.isEmpty()) throw Refused("Nothing was recorded: call signup_page on each page first.")
        return SignupMap(packageName, appLabel, appVersion, clock(), pages.toList(), finalLabel, complete && finalLabel != null)
    }

    private fun clean(value: String?, what: String, max: Int, required: Boolean = false): String {
        val text = value?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (text.isEmpty()) {
            if (required) throw Refused("$what is required.")
            return ""
        }
        if (text.length > max) throw Refused("$what is at most $max characters.")
        if (VALUE_LIKE.containsMatchIn(text) || SECRET_LIKE.containsMatchIn(text)) {
            throw Refused("$what looks like a value (an address, a number or a secret). Record labels and formats, never what was typed.")
        }
        val low = text.lowercase()
        if (typed.any { it in low }) throw Refused("$what contains something typed in this mission. Record the field's label, never its value.")
        return text
    }

    class Refused(message: String) : IllegalArgumentException(message)

    companion object {
        const val MAX_PAGES = 15
        const val MAX_FIELDS = 20
        const val MAX_CHOICES = 40
        val FIELD_KEYS = setOf("label", "kind", "required", "hint", "choices")
        private val VALUE_LIKE = Regex("@[^\\s]+\\.[a-z]{2,}|\\d{5,}|\\+\\d{6,}", RegexOption.IGNORE_CASE)
        private val SECRET_LIKE = Regex("(?i)(password|passcode|pin|otp|token|secret|api[_-]?key)\\s*[:=]")

        fun uniqueKey(label: String, kind: SignupFieldKind, taken: Set<String>): String {
            val base = when (kind) {
                SignupFieldKind.FIRST_NAME, SignupFieldKind.LAST_NAME, SignupFieldKind.FULL_NAME, SignupFieldKind.EMAIL, SignupFieldKind.PHONE,
                SignupFieldKind.USERNAME, SignupFieldKind.PASSWORD, SignupFieldKind.BIRTHDAY, SignupFieldKind.GENDER -> kind.wire
                else -> label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifBlank { kind.wire }
            }
            if (base !in taken) return base
            var n = 2
            while ("${base}_$n" in taken) n++
            return "${base}_$n"
        }
    }
}
