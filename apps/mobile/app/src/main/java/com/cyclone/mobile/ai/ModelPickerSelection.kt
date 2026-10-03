package com.cyclone.mobile.ai

/** Removing the active model chooses only from the user's remaining checks, never a preset. */
internal data class ModelPickerSelection(val ids: Set<String>, val activeId: String) {
    fun toggle(id: String, selected: Boolean): ModelPickerSelection {
        val next = ids.toMutableSet().apply { if (selected) add(id) else remove(id) }
        return ModelPickerSelection(next, activeId.takeIf { it in next } ?: next.sorted().firstOrNull().orEmpty())
    }
}
