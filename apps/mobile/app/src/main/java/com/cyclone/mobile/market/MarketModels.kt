package com.cyclone.mobile.market

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cyclone Marketplace: everything Cyclone can use, in one place. Listings are data, never code: a recipe is a goal
 * sentence with typed inputs that the Mind runs with the same boundaries as a typed Ask (GATE, approvals, Secrets
 * Card). See `Cyclone V5 plan/19-marketplace.md`.
 */
enum class ListingKind { RECIPE, CONNECTION }

enum class InputKind { TEXT, NUMBER, APP, CHOICE }

data class Publisher(val id: String, val name: String, val verified: Boolean)

data class MarketInput(
    val name: String,
    val label: String,
    val kind: InputKind = InputKind.TEXT,
    val default: String = "",
    val choices: List<String> = emptyList(),
    val required: Boolean = true,
)

data class MarketListing(
    val id: String,
    val kind: ListingKind,
    val version: String,
    val name: String,
    val publisher: Publisher,
    val summary: String,
    val category: String,
    /** One emoji-sized symbol for the card; listings carry no images or code. */
    val glyph: String,
    /** Recipe: the goal sent to the Mind, with `{input}` placeholders. */
    val goal: String = "",
    val inputs: List<MarketInput> = emptyList(),
    /** Android packages the recipe uses; shown before install. */
    val apps: List<String> = emptyList(),
    /** Plain-language summary of what it may do on the phone. */
    val does: List<String> = emptyList(),
    /** Consequential actions it will stop for. Shown before install; never a way to skip the approval. */
    val asksFirst: List<String> = emptyList(),
    /** Suggested ("for you") when one of these packages is installed. */
    val suggestFor: List<String> = emptyList(),
    val featured: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind.name.lowercase()).put("version", version).put("name", name)
        .put("publisher", JSONObject().put("id", publisher.id).put("name", publisher.name).put("verified", publisher.verified))
        .put("summary", summary).put("category", category).put("glyph", glyph).put("goal", goal)
        .put("inputs", JSONArray().also { out ->
            inputs.forEach {
                out.put(JSONObject().put("name", it.name).put("label", it.label).put("kind", it.kind.name.lowercase())
                    .put("default", it.default).put("choices", JSONArray(it.choices)).put("required", it.required))
            }
        })
        .put("apps", JSONArray(apps)).put("does", JSONArray(does)).put("asksFirst", JSONArray(asksFirst))
        .put("suggestFor", JSONArray(suggestFor)).put("featured", featured)
}

/** What the owner has added: saved inputs and how its runs went. Never holds a secret. */
data class InstalledListing(
    val id: String,
    val version: String,
    val inputs: Map<String, String>,
    val installedAtMs: Long,
    val source: String,
    val runs: Int = 0,
    val lastRunAtMs: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("version", version)
        .put("inputs", JSONObject(inputs as Map<*, *>)).put("installedAt", installedAtMs).put("source", source)
        .put("runs", runs).put("lastRunAt", lastRunAtMs ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): InstalledListing? = runCatching {
            val inputs = json.optJSONObject("inputs")?.let { obj -> obj.keys().asSequence().associateWith { obj.optString(it) } }.orEmpty()
            InstalledListing(json.getString("id"), json.optString("version"), inputs, json.optLong("installedAt"),
                json.optString("source", "catalog"), json.optInt("runs"), json.optLong("lastRunAt").takeIf { !json.isNull("lastRunAt") && it > 0 })
        }.getOrNull()
    }
}

class MarketError(message: String) : IllegalArgumentException(message)

object MarketRules {
    val LISTING_ID = Regex("^[a-z0-9][a-z0-9.-]{2,63}$")
    private val INPUT_NAME = Regex("^[a-z][a-zA-Z0-9_]{0,31}$")
    private val PLACEHOLDER = Regex("\\{([a-zA-Z0-9_]+)\\}")
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    /** Nothing that looks like a credential may live in a listing or a saved input. */
    val SECRET_SHAPE = Regex("(?i)(password|passcode|passwd|\\bpin\\b|otp|token|secret|api[_-]?key|cvv|iban|credit card)")
    const val MAX_INPUT = 200

    /** A listing either passes every rule or is not shown. First-party listings are checked by a unit test too. */
    fun validate(listing: MarketListing) {
        fun fail(message: String): Nothing = throw MarketError("${listing.id}: $message")
        if (!LISTING_ID.matches(listing.id)) fail("id is malformed")
        if (listing.name.isBlank() || listing.name.length > 60) fail("name must be 1..60 characters")
        if (listing.summary.isBlank() || listing.summary.length > 160) fail("summary must be 1..160 characters")
        if (listing.glyph.isBlank() || listing.glyph.length > 4) fail("glyph is one symbol")
        if (!listing.apps.all(PACKAGE::matches) || !listing.suggestFor.all(PACKAGE::matches)) fail("apps are package names")
        val names = listing.inputs.map { it.name }
        if (names.size != names.toSet().size || !names.all(INPUT_NAME::matches)) fail("input names must be unique identifiers")
        if (listing.inputs.any { SECRET_SHAPE.containsMatchIn(it.name + " " + it.label) }) fail("inputs may not ask for secrets")
        if (listing.inputs.any { it.kind == InputKind.CHOICE && it.choices.isEmpty() }) fail("choice inputs need choices")
        if (listing.kind == ListingKind.RECIPE) {
            if (listing.goal.isBlank() || listing.goal.length > 600) fail("a recipe needs a goal of 1..600 characters")
            if (SECRET_SHAPE.containsMatchIn(listing.goal)) fail("a goal may not mention secrets")
            val used = PLACEHOLDER.findAll(listing.goal).map { it.groupValues[1] }.toSet()
            if (!used.all { it in names }) fail("every {placeholder} must be a declared input")
            if (!names.all { it in used }) fail("every input must be used in the goal")
            if (listing.does.isEmpty()) fail("a recipe says what it does")
        }
    }

    /** The goal with the owner's values filled in; values are checked before they reach the Mind. */
    fun fill(listing: MarketListing, values: Map<String, String>): String {
        val resolved = listing.inputs.associate { input ->
            val value = (values[input.name] ?: input.default).trim()
            if (input.required && value.isEmpty()) throw MarketError("${input.label} is required.")
            if (value.length > MAX_INPUT) throw MarketError("${input.label} is too long.")
            if (SECRET_SHAPE.containsMatchIn(value)) throw MarketError("Secrets never go into a recipe. Cyclone will ask on the phone.")
            when (input.kind) {
                InputKind.NUMBER -> if (value.isNotEmpty() && value.toDoubleOrNull() == null) throw MarketError("${input.label} must be a number.")
                InputKind.CHOICE -> if (value.isNotEmpty() && value !in input.choices) throw MarketError("${input.label} must be one of ${input.choices.joinToString()}.")
                else -> Unit
            }
            input.name to value
        }
        return PLACEHOLDER.replace(listing.goal) { resolved[it.groupValues[1]].orEmpty() }.replace(Regex("\\s+"), " ").trim()
    }

    /** Only values for declared inputs are kept, checked like a run would check them. */
    fun cleanInputs(listing: MarketListing, values: Map<String, String>): Map<String, String> {
        val declared = listing.inputs.associateBy { it.name }
        val kept = values.filterKeys { it in declared }.mapValues { it.value.trim() }
        kept.forEach { (name, value) ->
            if (value.length > MAX_INPUT) throw MarketError("${declared.getValue(name).label} is too long.")
            if (SECRET_SHAPE.containsMatchIn(value)) throw MarketError("Secrets never go into a recipe. Cyclone will ask on the phone.")
        }
        return kept
    }

    /** Suggested listings with the reason ("Because you use Clock"), best first, excluding what is already added. */
    fun suggestions(catalog: List<MarketListing>, installedPackages: Map<String, String>, added: Set<String>): List<Pair<MarketListing, String>> =
        catalog.filter { it.id !in added }.mapNotNull { listing ->
            listing.suggestFor.firstOrNull { it in installedPackages }?.let { pkg -> listing to "Because you use ${installedPackages.getValue(pkg)}" }
        }
}
