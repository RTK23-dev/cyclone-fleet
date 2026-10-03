package com.cyclone.mobile.ai

import android.content.Context
import com.cyclone.mobile.ai.model.ModelRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class OpenRouterModelAvailability { UNKNOWN, AVAILABLE, UNAVAILABLE }

/** Pure key-scope policy used by the Android store and unit tests. */
object OpenRouterAccessPolicy {
    fun availability(storedFingerprint: String?, currentFingerprint: String?, availableIds: Set<String>?, modelId: String): OpenRouterModelAvailability {
        if (storedFingerprint.isNullOrBlank() || currentFingerprint.isNullOrBlank() || storedFingerprint != currentFingerprint || availableIds == null) {
            return OpenRouterModelAvailability.UNKNOWN
        }
        return if (modelId in availableIds) OpenRouterModelAvailability.AVAILABLE else OpenRouterModelAvailability.UNAVAILABLE
    }
}

/** Only public model metadata, key fingerprints, account model IDs and explicit selections are persisted here. Keys stay in Keystore. */
object OpenRouterCatalogStore {
    private const val PREFS = "cyclone_openrouter_catalog"
    private const val SELECTION = "selected_models"
    private const val AVAILABILITY_FINGERPRINT = "availability_key_fingerprint"
    private const val AVAILABLE_IDS = "available_model_ids"
    private const val REASONING_PREFIX = "reasoning_effort::"
    private const val BACKUP_KEY = "openrouter_backup_model"
    val revision = MutableStateFlow(0)

    @Volatile private var initialized = false
    @Volatile private var catalog: List<CatalogModel> = emptyList()
    @Volatile private var reasoningSelections: Map<String, String> = emptyMap()
    @Volatile private var requestAvailability: Set<String>? = null
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        val preferences = prefs(context)
        catalog = runCatching { OpenRouterCatalog.parse(preferences.getString("catalog", "{\"data\":[]}")!!) }.getOrDefault(emptyList())
        reasoningSelections = preferences.all.mapNotNull { (key, value) ->
            val id = key.takeIf { it.startsWith(REASONING_PREFIX) }?.removePrefix(REASONING_PREFIX) ?: return@mapNotNull null
            (value as? String)?.let { id to it }
        }.toMap()
        if (!preferences.contains(SELECTION)) {
            val old = context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE).getString("openrouter_model", null)
            val id = old?.let { ModelRegistry.resolve(it)?.openRouterSlug ?: it }?.takeIf { OpenRouterCustomModelStore.isValidSlug(it) }
            preferences.edit().putStringSet(SELECTION, setOfNotNull(id) + OpenRouterCustomModelStore.list(context).map { it.id }).apply()
        }
        initialized = true
        refreshRequestAvailability(context)
    }

    fun models(context: Context): List<CatalogModel> { initialize(context); return catalog }
    fun lookup(id: String): CatalogModel? = catalog.firstOrNull { it.id == canonicalId(id) }
    fun canonicalId(id: String): String = ModelRegistry.resolve(id)?.openRouterSlug ?: id.trim()
    fun selectedIds(context: Context): Set<String> { initialize(context); return prefs(context).getStringSet(SELECTION, emptySet()).orEmpty().toSet() }

    fun picker(context: Context): List<OpenRouterModelPreset> {
        initialize(context)
        val key = OpenRouterSecretStore.read(context)
        return selectedIds(context).filter { availability(context, key, it) == OpenRouterModelAvailability.AVAILABLE }
            .sorted().map { preset(context, it) }
    }

    fun preset(context: Context, id: String): OpenRouterModelPreset {
        initialize(context)
        val canonical = canonicalId(id)
        return OpenRouterModelPresets.byId(canonical).copy(reasoningEffort = reasoningForRequest(canonical).orEmpty())
    }

    fun activeId(context: Context): String {
        initialize(context)
        val stored = context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE).getString("openrouter_model", "").orEmpty()
        val id = canonicalId(stored)
        val key = OpenRouterSecretStore.read(context)
        return id.takeIf { it in selectedIds(context) && availability(context, key, it) == OpenRouterModelAvailability.AVAILABLE }.orEmpty()
    }

    @Synchronized fun setActive(context: Context, rawId: String) {
        initialize(context)
        val id = canonicalId(rawId)
        require(id in selectedIds(context)) { "Choose this model in the OpenRouter catalog first." }
        require(availability(context, OpenRouterSecretStore.read(context), id) == OpenRouterModelAvailability.AVAILABLE) {
            "This model is not verified for the current OpenRouter API key. Refresh the catalog first."
        }
        check(context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE).edit().putString("openrouter_model", id).commit()) {
            "Could not update selected model."
        }
        revision.value++
    }

    /**
     * The owner's backup route for when the main model is rate-limited or unavailable during a task. Empty means
     * "stop and tell me". Only a chosen, verified model that differs from the main model qualifies.
     */
    fun backupId(context: Context): String {
        initialize(context)
        val stored = canonicalId(context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE).getString(BACKUP_KEY, "").orEmpty())
        val key = OpenRouterSecretStore.read(context)
        return stored.takeIf {
            it.isNotBlank() && it != activeId(context) && it in selectedIds(context) &&
                availability(context, key, it) == OpenRouterModelAvailability.AVAILABLE
        }.orEmpty()
    }

    @Synchronized fun setBackup(context: Context, rawId: String) {
        initialize(context)
        val id = rawId.trim().takeIf(String::isNotBlank)?.let(::canonicalId).orEmpty()
        if (id.isNotBlank()) {
            require(id in selectedIds(context)) { "Choose this model in the OpenRouter catalog first." }
            require(id != activeId(context)) { "The backup must differ from the main model." }
        }
        check(context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE).edit().putString(BACKUP_KEY, id).commit()) {
            "Could not update the backup model."
        }
        revision.value++
    }

    @Synchronized fun saveCatalog(context: Context, models: List<CatalogModel>) {
        check(prefs(context).edit().putString("catalog", JSONObject().put("data", JSONArray().also { array -> models.forEach { array.put(it.toJson()) } }).toString()).commit()) {
            "Could not save model catalog."
        }
        catalog = models
        revision.value++
    }

    fun fingerprint(apiKey: String): String? = apiKey.trim().takeIf(String::isNotBlank)?.let { clean ->
        MessageDigest.getInstance("SHA-256").digest(clean.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun availability(context: Context, apiKey: String, rawId: String): OpenRouterModelAvailability {
        initialize(context)
        val preferences = prefs(context)
        return OpenRouterAccessPolicy.availability(
            preferences.getString(AVAILABILITY_FINGERPRINT, null),
            fingerprint(apiKey),
            preferences.getStringSet(AVAILABLE_IDS, null)?.toSet(),
            canonicalId(rawId),
        )
    }

    fun requestAvailability(rawId: String): OpenRouterModelAvailability {
        val available = requestAvailability ?: return OpenRouterModelAvailability.UNKNOWN
        return if (canonicalId(rawId) in available) OpenRouterModelAvailability.AVAILABLE else OpenRouterModelAvailability.UNAVAILABLE
    }

    @Synchronized fun saveAvailability(context: Context, apiKey: String, ids: Set<String>) {
        initialize(context)
        val fp = fingerprint(apiKey) ?: return invalidateAvailability(context)
        val canonical = ids.map(::canonicalId).toSet()
        check(prefs(context).edit().putString(AVAILABILITY_FINGERPRINT, fp).putStringSet(AVAILABLE_IDS, canonical).commit()) {
            "Could not save OpenRouter account availability."
        }
        requestAvailability = canonical
        normalizeActive(context, canonical)
        revision.value++
    }

    @Synchronized fun invalidateAvailability(context: Context) {
        if (!initialized) {
            requestAvailability = null
            prefs(context).edit().remove(AVAILABILITY_FINGERPRINT).remove(AVAILABLE_IDS).apply()
            revision.value++
            return
        }
        prefs(context).edit().remove(AVAILABILITY_FINGERPRINT).remove(AVAILABLE_IDS).apply()
        requestAvailability = null
        revision.value++
    }

    private fun refreshRequestAvailability(context: Context) {
        val preferences = prefs(context)
        val current = fingerprint(OpenRouterSecretStore.read(context))
        val stored = preferences.getString(AVAILABILITY_FINGERPRINT, null)
        requestAvailability = if (current != null && current == stored) preferences.getStringSet(AVAILABLE_IDS, null)?.toSet() else null
    }

    private fun normalizeActive(context: Context, available: Set<String>) {
        val selected = selectedIds(context)
        val ai = context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE)
        val current = canonicalId(ai.getString("openrouter_model", "").orEmpty())
        if (current in selected && current in available) return
        val replacement = selected.filter { it in available }.sorted().firstOrNull().orEmpty()
        ai.edit().putString("openrouter_model", replacement).apply()
    }

    @Synchronized fun select(context: Context, rawId: String, selected: Boolean) {
        initialize(context)
        val id = canonicalId(rawId)
        require(OpenRouterCustomModelStore.isValidSlug(id))
        if (selected) {
            val model = catalog.firstOrNull { it.id == id }
            require(model != null && model.textOutput) { "Only listed OpenRouter chat models can be selected." }
            require(availability(context, OpenRouterSecretStore.read(context), id) == OpenRouterModelAvailability.AVAILABLE) {
                "This model is not verified for the current OpenRouter API key. Refresh the catalog first."
            }
        }
        val nextIds = selectedIds(context).toMutableSet().apply { if (selected) add(id) else remove(id) }
        val available = requestAvailability.orEmpty()
        val ai = context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE)
        val current = canonicalId(ai.getString("openrouter_model", "").orEmpty())
        val nextActive = current.takeIf { it in nextIds && it in available }
            ?: nextIds.filter { it in available }.sorted().firstOrNull().orEmpty()
        try {
            check(prefs(context).edit().putStringSet(SELECTION, nextIds).commit()) { "Could not save model selection." }
            check(ai.edit().putString("openrouter_model", nextActive).commit()) { "Could not update selected model." }
        } finally {
            revision.value++
        }
    }

    fun reasoningOptions(context: Context, rawId: String): List<String> {
        initialize(context)
        return lookup(canonicalId(rawId))?.reasoning?.supportedEfforts.orEmpty()
    }

    fun defaultReasoningEffort(context: Context, rawId: String): String? {
        initialize(context)
        return lookup(canonicalId(rawId))?.reasoning?.defaultEffort
    }

    fun reasoningSelection(context: Context, rawId: String): String? {
        initialize(context)
        return reasoningForRequest(canonicalId(rawId))
    }

    /** Context-free request lookup is safe after model resolution initialized this store. */
    fun reasoningForRequest(rawId: String): String? {
        val id = canonicalId(rawId)
        val requested = reasoningSelections[id]
        return OpenRouterReasoningContract.exactEffort(lookup(id)?.reasoning, requested)
    }

    @Synchronized fun setReasoningEffort(context: Context, rawId: String, effort: String?) {
        initialize(context)
        val id = canonicalId(rawId)
        val exact = OpenRouterReasoningContract.exactEffort(lookup(id)?.reasoning, effort)
        require(effort == null || effort.isBlank() || exact != null) { "That intelligence level is not supported by this OpenRouter model." }
        val editor = prefs(context).edit()
        if (exact == null) editor.remove(REASONING_PREFIX + id) else editor.putString(REASONING_PREFIX + id, exact)
        check(editor.commit()) { "Could not save model intelligence." }
        reasoningSelections = reasoningSelections.toMutableMap().apply { if (exact == null) remove(id) else put(id, exact) }
        revision.value++
    }
}
