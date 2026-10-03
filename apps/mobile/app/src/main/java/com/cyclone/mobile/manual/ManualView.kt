package com.cyclone.mobile.manual

import com.cyclone.mobile.manual.dictionary.AppDictionary

/** An app's manual as the Mind, Glass and agents use it: the dictionary, its abilities and the index over them. */
class ManualView(val dictionary: AppDictionary, val appLabel: String, val abilities: List<Ability>) {
    val index: AbilityIndex by lazy { AbilityIndex(abilities) }

    fun ability(id: String): Ability? = abilities.firstOrNull { it.id == id }

    /** The lists of the app and how to find one item in each, for `how_to_find`. Best match for [name] first. */
    fun howToFind(name: String): List<Ability> {
        val finds = abilities.filter { it.kind == AbilityKind.FIND }
        if (name.isBlank()) return finds
        val ranked = AbilityIndex(finds).search(name, finds.size.coerceAtLeast(1)).map { it.ability }
        return ranked + finds.filter { it !in ranked }
    }

    companion object {
        fun of(dictionary: AppDictionary, appLabel: String): ManualView = ManualView(dictionary, appLabel, Abilities.derive(dictionary))
    }
}

/** How the Mind reaches the manual of an app. Production: [ManualRuntime]. */
interface MindManualPort {
    fun view(packageName: String): ManualView?
    /** A walk of an ability ended; runs teach the manual. */
    fun walked(packageName: String, abilityId: String, ok: Boolean)
}
