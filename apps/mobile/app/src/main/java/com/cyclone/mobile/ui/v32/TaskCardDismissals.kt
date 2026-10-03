package com.cyclone.mobile.ui.v32

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*

/** Presentation only: never deletes reports, releases a workspace, or cancels a request. */
internal object TaskCardDismissals {
    private const val KEY = "cleared_cards"
    private fun prefs(context: Context) = context.getSharedPreferences("task_card_presentation", Context.MODE_PRIVATE)
    fun clear(context: Context, id: String) {
        val preferences = prefs(context)
        preferences.edit().putStringSet(KEY, preferences.getStringSet(KEY, emptySet()).orEmpty() + id).apply()
    }
    fun restore(context: Context) { prefs(context).edit().remove(KEY).apply() }

    @Composable
    fun cleared(context: Context): Set<String> {
        val preferences = remember(context) { prefs(context) }
        var ids by remember(preferences) { mutableStateOf(preferences.getStringSet(KEY, emptySet()).orEmpty().toSet()) }
        DisposableEffect(preferences) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                if (key == KEY) ids = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return ids
    }
}
