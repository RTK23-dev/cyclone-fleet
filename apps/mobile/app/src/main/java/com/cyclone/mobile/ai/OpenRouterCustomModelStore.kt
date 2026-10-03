package com.cyclone.mobile.ai

import android.content.Context

/**
 * Cyclone 2.9.1 user-managed OpenRouter models.
 *
 * Custom entries are intentionally only model slugs. API keys remain in OpenRouterSecretStore and
 * capability claims such as vision are never guessed for user-added models.
 */
object OpenRouterCustomModelStore {
    private const val PREFS = "cyclone_ai"
    private const val KEY_MODELS = "custom_openrouter_models"

    fun list(context: Context): List<OpenRouterModelPreset> {
        val builtInIds = OpenRouterModelPresets.all.map { it.id }.toSet()
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_MODELS, emptySet())
            .orEmpty()
            .asSequence()
            .map(String::trim)
            .filter { isValidSlug(it) && it !in builtInIds }
            .distinct()
            .sorted()
            .map { slug -> OpenRouterModelPreset(slug, friendlyLabel(slug), vision = false, reasoningEffort = "medium") }
            .toList()
    }

    fun all(context: Context): List<OpenRouterModelPreset> = OpenRouterCatalogStore.picker(context)

    fun add(context: Context, rawSlug: String): Result<OpenRouterModelPreset> = runCatching {
        val slug = rawSlug.trim()
        require(isValidSlug(slug)) { "Use an OpenRouter slug like provider/model-name." }
        OpenRouterCatalogStore.select(context, slug, true)
        OpenRouterModelPresets.all.firstOrNull { it.id == slug }?.let { return@runCatching it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = prefs.getStringSet(KEY_MODELS, emptySet()).orEmpty().toMutableSet().apply { add(slug) }
        prefs.edit().putStringSet(KEY_MODELS, updated).apply()
        OpenRouterModelPreset(slug, friendlyLabel(slug), vision = false, reasoningEffort = "medium")
    }

    fun remove(context: Context, slug: String) {
        OpenRouterCatalogStore.select(context, slug, false)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = prefs.getStringSet(KEY_MODELS, emptySet()).orEmpty().toMutableSet().apply { remove(slug) }
        prefs.edit().putStringSet(KEY_MODELS, updated).apply()
    }

    fun isCustom(context: Context, slug: String): Boolean = list(context).any { it.id == slug }

    internal fun isValidSlug(slug: String): Boolean =
        slug.length in 3..180 && '/' in slug && !slug.any(Char::isWhitespace) && !slug.startsWith('/') && !slug.endsWith('/')

    private fun friendlyLabel(slug: String): String = slug.substringAfter('/').ifBlank { slug }
}
