package com.cyclone.mobile.mind

import com.cyclone.mobile.manual.Ability
import com.cyclone.mobile.manual.AbilityIndex
import com.cyclone.mobile.manual.ManualRenderer
import com.cyclone.mobile.manual.ManualView
import com.cyclone.mobile.manual.ManualWalk

/** What the Mind reads about the App Manual (plan 36 §8): short lines with handles, and how a walk ended. */
object ManualTexts {
    fun excerpt(view: ManualView, hits: List<AbilityIndex.Hit>, handle: (Ability) -> String): String? =
        ManualRenderer.excerpt(view.appLabel, hits.map { handle(it.ability) to it }, AbilityIndex.clear(hits))

    fun walkHeader(ability: Ability, outcome: ManualWalk): String {
        val n = outcome.moves
        val moves = "$n move${if (n == 1) "" else "s"}"
        return when (outcome) {
            is ManualWalk.Arrived -> buildString {
                append(if (n == 0) "Already there: ${outcome.at ?: ability.placeName ?: "the place"}." else "Walked “${ability.name}” from the manual in $moves; now on ${outcome.at ?: "the place"}.")
                outcome.pick?.let { pick ->
                    append(" The walk stops here: choosing “$pick” is yours")
                    if (ability.effect == "asks") append(", and it asks the owner first")
                    append(".")
                }
                ability.note?.let { append(" How to find one: $it.") }
            }
            is ManualWalk.NotInApp -> "The phone is not in the app. Open it first, then try again."
            is ManualWalk.Lost -> "The manual cannot tell which screen this is. Find the way yourself, or go to a screen it knows."
            is ManualWalk.NoRoute -> "The manual knows no walked way from ${outcome.from ?: "here"} to ${outcome.to ?: "there"}. Find the way yourself."
            is ManualWalk.Stopped -> "The manual walk stopped after $moves: ${outcome.reason}. Here is the real screen; carry on yourself."
        }
    }
}
