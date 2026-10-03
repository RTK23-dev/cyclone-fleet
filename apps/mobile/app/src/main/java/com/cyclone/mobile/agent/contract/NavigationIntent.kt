package com.cyclone.mobile.agent.contract

import java.net.URI
import com.cyclone.mobile.fastpath.FastPathLanding
import com.cyclone.mobile.fastpath.InstalledApp
import com.cyclone.mobile.fastpath.InstalledAppInventory

/** Narrow intent captured from the user's task, never from model-provided authorization flags. */
data class NavigationIntent(val target: String, val chrome: Boolean) {
    fun accepts(value: String): Boolean {
        val uri = parseUri(value) ?: return false
        if (uri.query != null || uri.fragment != null) return false
        if (!uri.path.isNullOrBlank() && uri.path != "/") return false
        return hostMatches(uri)
    }

    fun acceptsLoaded(value: String): Boolean {
        val uri = parseUri(value) ?: return false
        return hostMatches(uri)
    }

    private fun parseUri(value: String): URI? {
        val uri = runCatching { URI(if ("://" in value) value else "https://$value") }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1) return null
        return uri
    }

    private fun hostMatches(uri: URI): Boolean {
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        return if ('.' in target) {
            val wanted = target.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
            host == wanted || host.endsWith(".$wanted")
        } else {
            host.split('.').let { it.size == 2 && it.first() == target }
        }
    }

    companion object {
        private val SIMPLE = Regex(
            "(?i)^(?:please\\s+)?(?:open|go to|navigate to)\\s+(?:the\\s+)?([a-z0-9./:-]+)(?:\\s+(?:website|site))?(?:\\s+(?:in|on|using|with)\\s+(?:google\\s+)?(chrome))?[.!]?$",
        )
        private val CHROME_THEN_SITE = Regex(
            "(?i)^(?:please\\s+)?(?:open|launch)\\s+(?:google\\s+)?chrome\\s+(?:and\\s+)?(?:then\\s+)?(?:open|go to|navigate to)\\s+(?:the\\s+)?([a-z0-9.-]+)(?:\\s+(?:website|site))?[.!]?$",
        )

        fun parse(
            goal: String,
            installed: List<InstalledApp> = InstalledAppInventory.snapshot,
        ): NavigationIntent? {
            val clean = goal.trim()
            CHROME_THEN_SITE.matchEntire(clean)?.let { match ->
                return candidate(match.groupValues[1], chrome = true, installed = installed)
            }
            val match = SIMPLE.matchEntire(clean) ?: return null
            return candidate(match.groupValues[1], match.groupValues[2].isNotBlank(), installed)
        }

        private fun candidate(raw: String, chrome: Boolean, installed: List<InstalledApp>): NavigationIntent? {
            val target = raw.lowercase().trimEnd('.')
            val intent = NavigationIntent(target, chrome)
            if ('.' in target) return intent.takeIf { it.accepts(target) }
            if (!Regex("[a-z][a-z0-9-]*").matches(target)) return null
            if (target in NAMED_APPS) return null
            if (FastPathLanding.packageForName(target, installed) != null) return null
            return intent
        }

        private val NAMED_APPS = setOf(
            "camera", "chrome", "photos", "settings", "gmail", "youtube",
            "facebook", "fb", "instagram", "insta", "ig", "messenger", "whatsapp", "reddit",
            "maps", "phone", "contacts", "messages", "files", "clock",
        )
    }
}
