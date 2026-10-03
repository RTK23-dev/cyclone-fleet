package com.cyclone.mobile.applearner

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ObsidianKnowledgeMirror(context: Context) {
    private val root = File(context.filesDir, "Cyclone Brain/Apps")
    private val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun write(graph: AppGraphSnapshot) {
        val appDir = File(root, safeName(graph.app.label.ifBlank { graph.app.packageName })).apply { mkdirs() }
        val screensDir = File(appDir, "Screens").apply { mkdirs() }
        File(appDir, "Skills").mkdirs()
        File(appDir, "Recovery").mkdirs()
        File(appDir, "app-map.json").writeText(graph.toJson().toString(2))
        File(appDir, "Overview.md").writeText(overview(graph))
        graph.screens.forEach { screen ->
            val actions = graph.actions.filter { it.screenId == screen.id }
            val outgoing = graph.transitions.filter { it.fromScreenId == screen.id }
            File(screensDir, "${safeName(screen.title)}.md").writeText(screenMarkdown(screen, actions, outgoing, graph))
        }
    }

    private fun overview(graph: AppGraphSnapshot): String = buildString {
        appendLine("# ${graph.app.label}")
        appendLine()
        appendLine("Package: `${graph.app.packageName}`")
        graph.app.versionName?.let { appendLine("Version: `$it`") }
        appendLine("Knowledge: **${graph.app.knowledgeState.name.lowercase().replace('_', ' ')}**")
        appendLine("Confidence: ${(graph.app.confidence * 100).toInt()}%")
        appendLine("Last learned: ${date.format(Date(graph.app.lastLearnedAt))}")
        appendLine()
        appendLine("## Known screens")
        graph.screens.sortedByDescending { it.confidence }.forEach { screen ->
            appendLine("- [[Screens/${safeName(screen.title)}|${screen.title}]] — ${screen.knowledgeState.name.lowercase()} · ${(screen.confidence * 100).toInt()}%")
        }
        appendLine()
        appendLine("## Known paths")
        graph.transitions.take(60).forEach { transition ->
            val from = graph.screens.firstOrNull { it.id == transition.fromScreenId }?.title ?: "Unknown"
            val to = graph.screens.firstOrNull { it.id == transition.toScreenId }?.title ?: "Unknown"
            val action = graph.actions.firstOrNull { it.id == transition.actionId }?.safeLabel() ?: "action"
            appendLine("- $from → **$action** → $to (${transition.knowledgeState.name.lowercase()}, ${(transition.confidence * 100).toInt()}%)")
        }
        appendLine()
        appendLine("## Safety boundaries")
        graph.actions.filter { it.risk != ActionRisk.SAFE }.take(40).forEach { action ->
            appendLine("- ${action.safeLabel()} — ${action.risk.name.lowercase().replace('_', ' ')}")
        }
        appendLine()
        appendLine("> This mirror intentionally excludes passwords, tokens, authentication values, payment credentials and sensitive form contents. Cyclone's SQLite knowledge store is the runtime source of truth.")
    }

    private fun screenMarkdown(
        screen: LearnedScreen,
        actions: List<LearnedAction>,
        outgoing: List<LearnedTransition>,
        graph: AppGraphSnapshot,
    ): String = buildString {
        appendLine("# ${screen.title}")
        appendLine()
        appendLine(screen.purpose)
        appendLine()
        appendLine("- Identity: `${screen.identity}`")
        appendLine("- State: **${screen.knowledgeState.name.lowercase()}**")
        appendLine("- Confidence: ${(screen.confidence * 100).toInt()}%")
        appendLine("- Last seen: ${date.format(Date(screen.lastSeenAt))}")
        appendLine()
        appendLine("## Things Cyclone can see/do")
        actions.take(60).forEach { action ->
            appendLine("- ${action.safeLabel()} — ${action.risk.name.lowercase()} · ${(action.confidence * 100).toInt()}%")
        }
        appendLine()
        appendLine("## Navigation")
        outgoing.forEach { transition ->
            val action = actions.firstOrNull { it.id == transition.actionId }?.safeLabel() ?: "action"
            val target = graph.screens.firstOrNull { it.id == transition.toScreenId }?.title ?: "Unknown"
            appendLine("- $action → [[${safeName(target)}|$target]]")
        }
        appendLine()
        appendLine("## Why Cyclone recognizes this")
        screen.recognition.stableAnchors.take(24).forEach { appendLine("- `${it.take(120)}`") }
    }

    private fun LearnedAction.safeLabel(): String {
        if (requiredInput != null || risk == ActionRisk.AUTHENTICATION) return semanticName.replace('_', ' ').ifBlank { "input/action" }
        return label.take(100)
    }

    private fun safeName(raw: String): String = raw
        .replace(Regex("[\\/:*?\"<>|]"), "_")
        .trim()
        .take(80)
        .ifBlank { "Unknown" }
}
