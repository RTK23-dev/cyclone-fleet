package com.cyclone.mobile.mind.workspace

import com.cyclone.mobile.mind.MindToolSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 37 (D9): the workspace adds no tools. It gives existing tools optional arguments; a model that never uses them
 * works exactly as in a classic run. Nothing here is required.
 */
object WorkspaceSpecs {
    /** Tools that change the screen: they may say which plan step they serve and what they expect. */
    val EXPECT_TOOLS = setOf("tap", "tap_sequence", "long_press", "swipe", "tap_point", "type_text", "press_enter", "scroll", "back", "home", "wait",
        "open_app", "open_link", "open_settings", "open_notification", "go_to")

    /** Tools that move the mission to another app on purpose: the next app in front opens a stay at once. */
    val MOVE_TOOLS = setOf("open_app", "home", "open_link", "open_settings", "open_notification")

    fun extend(specs: List<MindToolSpec>): List<MindToolSpec> = specs.map { spec ->
        val parameters = JSONObject(spec.parameters.toString())
        val properties = parameters.optJSONObject("properties") ?: JSONObject().also { parameters.put("properties", it) }
        if (spec.name in EXPECT_TOOLS) {
            properties.put("step", MindToolSpec.integer("Optional: the plan step number this action serves.", 1, 20))
            properties.put("expect", MindToolSpec.string("Optional: what should be true after this action, in plain words; " +
                "put exact words or handles in quotes, e.g. chat with \"lo.06\" opens. Cyclone checks it on the new screen."))
        }
        when (spec.name) {
            "open_app" -> {
                properties.put("why", MindToolSpec.string("Optional: why you are going to this app, for the owner."))
                properties.put("carry", MindToolSpec.string("Optional: what you take along from the app you are leaving, e.g. reply with the ETA."))
                properties.put("resume", MindToolSpec.boolean("Optional: come back to where this mission left the app, and see what it showed then."))
            }
            "recall" -> {
                properties.put("turn", MindToolSpec.integer("Optional: show a turn of this mission again in full (older screens are folded to one line).", 1, 400))
                properties.put("stay", MindToolSpec.integer("Optional: show an earlier app stay of this mission again (its journal block and last screen).", 1, 200))
                parameters.put("required", JSONArray())
            }
            "note" -> properties.put("key", MindToolSpec.string("Optional: a short name for the fact, like address or ETA; a later note with the same key replaces it."))
            "plan_update" -> {
                // Plan 38: app, why and divert are part of plan_update in every run; the workspace adds done checks.
                properties.put("done", MindToolSpec.array("Optional: how you and the owner will know the mission is done (1-5 checks).",
                    MindToolSpec.objectSchema(
                        "kind" to MindToolSpec.string("What to check.", DoneCheck.KINDS.toList()),
                        "value" to MindToolSpec.string("The words that show it, the recipient (for sent_to), or the answer."),
                        required = listOf("value"))))
            }
        }
        MindToolSpec(spec.name, spec.description, parameters)
    }
}
