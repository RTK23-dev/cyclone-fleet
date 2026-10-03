package com.cyclone.mobile.ai

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class CatalogReasoning(
    val supportedEfforts: List<String>,
    val defaultEffort: String?,
    val mandatory: Boolean,
    val defaultEnabled: Boolean?,
    val supportsMaxTokens: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mandatory", mandatory)
        .put("supported_efforts", JSONArray(supportedEfforts))
        .put("default_effort", defaultEffort ?: JSONObject.NULL)
        .put("supports_max_tokens", supportsMaxTokens)
        .also { json -> defaultEnabled?.let { json.put("default_enabled", it) } }
}

data class CatalogModel(
    val id: String,
    val name: String,
    val imageInput: Boolean,
    val textOutput: Boolean,
    val contextLength: Int,
    val maxOutputTokens: Int?,
    val reasoning: CatalogReasoning? = null,
    /** From `supported_parameters`: true when native tool calling is advertised, null when the catalog did not say. */
    val nativeTools: Boolean? = null,
) {
    fun preset() = OpenRouterModelPreset(id, name, imageInput)
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("architecture", JSONObject().put("input_modalities", JSONArray(if (imageInput) listOf("text", "image") else listOf("text")))
            .put("output_modalities", JSONArray(if (textOutput) listOf("text") else listOf("other"))))
        .put("context_length", contextLength)
        .put("top_provider", JSONObject().put("max_completion_tokens", maxOutputTokens ?: JSONObject.NULL))
        .also { json -> reasoning?.let { json.put("reasoning", it.toJson()) } }
        .also { json -> nativeTools?.let { json.put("supported_parameters", JSONArray(if (it) listOf("tools", "tool_choice") else emptyList<String>())) } }
}

object OpenRouterCatalog {
    fun parse(body: String): List<CatalogModel> {
        val rows = JSONObject(body).getJSONArray("data")
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id").trim()
            if (!OpenRouterCustomModelStore.isValidSlug(id)) return@mapNotNull null
            val architecture = row.optJSONObject("architecture")
            fun has(array: JSONArray?, value: String): Boolean = array != null && (0 until array.length()).any { array.optString(it) == value }
            val reasoningJson = row.optJSONObject("reasoning")
            val reasoning = reasoningJson?.let { json ->
                val efforts = json.optJSONArray("supported_efforts")?.let { values ->
                    (0 until values.length()).mapNotNull { i -> values.optString(i).trim().takeIf(String::isNotBlank) }.distinct()
                }.orEmpty()
                CatalogReasoning(
                    supportedEfforts = efforts,
                    defaultEffort = json.optString("default_effort").trim().takeIf(String::isNotBlank),
                    mandatory = json.optBoolean("mandatory", false),
                    defaultEnabled = json.takeIf { it.has("default_enabled") }?.optBoolean("default_enabled"),
                    supportsMaxTokens = json.optBoolean("supports_max_tokens", false),
                )
            }
            CatalogModel(id, row.optString("name").ifBlank { id }.take(200),
                has(architecture?.optJSONArray("input_modalities"), "image"),
                architecture?.optJSONArray("output_modalities")?.let { has(it, "text") } ?: true,
                row.optInt("context_length", 0),
                row.optJSONObject("top_provider")?.optInt("max_completion_tokens", 0)?.takeIf { it > 0 },
                reasoning,
                row.optJSONArray("supported_parameters")?.let { has(it, "tools") })
        }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }
    fun search(models: List<CatalogModel>, query: String): List<CatalogModel> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        return models.filter { model -> terms.all { it in "${model.name} ${model.id}".lowercase() } }
    }
}

/** Never invents an effort name: only an exact token advertised for this exact model can be sent. */
object OpenRouterReasoningContract {
    fun exactEffort(reasoning: CatalogReasoning?, requested: String?): String? =
        requested?.takeIf { value -> reasoning?.supportedEfforts?.contains(value) == true }

    fun apply(body: JSONObject, reasoning: CatalogReasoning?, requested: String?): JSONObject {
        exactEffort(reasoning, requested)?.let { effort ->
            body.put("reasoning", JSONObject().put("effort", effort))
        }
        return body
    }
}

data class CatalogFetch(val models: List<CatalogModel>, val availableIds: Set<String>?, val warning: String? = null)

/** Fixed OpenRouter origins only; no redirects carrying the user's bearer token. */
class OpenRouterCatalogClient(private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {
    fun fetch(apiKey: String): CatalogFetch {
        require(apiKey.isNotBlank()) { "Add your OpenRouter API key first." }
        fun get(path: String): List<CatalogModel> {
            val request = Request.Builder().url("https://openrouter.ai/api/v1/$path")
                .header("Authorization", "Bearer $apiKey").header("X-Title", "Cyclone Mobile").build()
            return http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || runCatching { JSONObject(body).has("error") }.getOrDefault(false)) {
                    val failure = ProviderFailure.classify(response.code, body)
                    throw IOException("OpenRouter HTTP ${failure.httpStatus}: ${failure.userMessage}")
                }
                try { OpenRouterCatalog.parse(body) } catch (_: Exception) { throw IOException("OpenRouter returned an invalid model catalog. Refresh to try again.") }
            }
        }
        // Omitting offset and limit requests the complete catalog, including non-text models.
        val all = get("models?output_modalities=all")
        require(all.isNotEmpty()) { "OpenRouter returned an empty catalog. Refresh to try again." }
        return try {
            CatalogFetch(all, get("models/user?output_modalities=all").map { it.id }.toSet())
        } catch (error: IOException) { CatalogFetch(all, null, error.message) }
    }
}
