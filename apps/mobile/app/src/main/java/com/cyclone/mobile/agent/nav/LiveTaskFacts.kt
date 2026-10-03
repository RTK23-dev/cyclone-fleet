package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.automation.skill.SkillSecrets

/** Conservative readers: a sender address in an inbox is not proof of the signed-in account. */
object LiveTaskFacts {
    fun signedInEmails(page: PageContext): List<String> {
        if (page.packageName != "com.google.android.gm") return emptyList()
        val controls = page.controls.filterNot {
            it.selector.optBoolean("editable") || it.selector.optBoolean("password") ||
                it.role.contains("password", true) || SkillSecrets.isSecretValue(it.label)
        }
        val marked = controls.filter { control ->
            val marker = control.label + " " + control.semanticName + " " + control.selector.optString("resourceId")
            CURRENT.containsMatchIn(marker) || control.selector.optBoolean("selected")
        }.flatMap { EMAIL.findAll(it.label + " " + it.semanticName).map { match -> match.value }.toList() }
            .distinctBy { it.lowercase() }
        if (marked.size == 1) return marked
        val accountScreen = controls.any { ACCOUNT_SCREEN.containsMatchIn(it.label + " " + it.semanticName) }
        if (!accountScreen) return emptyList()
        return controls.flatMap { EMAIL.findAll(it.label).map { match -> match.value }.toList() }
            .distinctBy { it.lowercase() }
            .filterNot { it.substringAfter('@').lowercase() in setOf("example.com", "test.com", "email.com") }
    }

    private val EMAIL = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val CURRENT = Regex("(?i)\\b(?:current|signed[- ]in|selected) account\\b|(?:^|[:/_])(?:current_account|selected_account|account_email|account_name)(?:$|[/_])")
    private val ACCOUNT_SCREEN = Regex("(?i)manage your google account|your google account|add another account|manage accounts on this device|google-account beheren|account toevoegen")
}
