package com.cyclone.mobile.fastpath

import com.cyclone.mobile.agent.contract.NavigationIntent
import org.json.JSONObject

data class FastPathLandingHint(
    val tool: String,
    val packageName: String? = null,
    val uri: String? = null,
    val reason: String,
    val workspaceNamedApp: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tool", tool)
        .put("package", packageName ?: JSONObject.NULL)
        .put("uri", uri ?: JSONObject.NULL)
        .put("reason", reason)
        .put("preferBeforeIconHunt", true)
        .put("workspaceNamedApp", workspaceNamedApp)
}

data class NamedAppHit(
    val alias: String,
    val packageName: String,
    val index: Int,
    val destinationCue: Boolean,
    val instrumentOnly: Boolean,
)

/**
 * Prefer intent / deep-link / open_app before hunting launcher icons.
 * Named-app matches are the same signal 3.9.12 Ask→workspace routing already uses.
 */
object FastPathLanding {
    val APP_PACKAGE_ALIASES = linkedMapOf(
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "youtube" to "com.google.android.youtube",
        "google maps" to "com.google.android.apps.maps",
        "maps" to "com.google.android.apps.maps",
        "play store" to "com.android.vending",
        "settings" to "com.android.settings",
        "photos" to "com.google.android.apps.photos",
        "camera" to "com.android.camera2",
        "clock" to "com.google.android.deskclock",
        "files" to "com.google.android.documentsui",
        "messages" to "com.google.android.apps.messaging",
        "phone" to "com.google.android.dialer",
        "contacts" to "com.google.android.contacts",
        "facebook" to "com.facebook.katana",
        "fb" to "com.facebook.katana",
        "instagram" to "com.instagram.android",
        "insta" to "com.instagram.android",
        "ig" to "com.instagram.android",
        "messenger" to "com.facebook.orca",
        "whatsapp" to "com.whatsapp",
        "reddit" to "com.reddit.frontpage",
    )

    private val PACKAGE_FALLBACKS = mapOf(
        "com.facebook.katana" to listOf("com.facebook.katana", "com.facebook.lite"),
        "com.whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
    )

    private val WEB_FALLBACKS = mapOf(
        "com.facebook.katana" to "https://facebook.com",
        "com.facebook.lite" to "https://facebook.com",
        "com.instagram.android" to "https://instagram.com",
        "com.reddit.frontpage" to "https://reddit.com",
        "com.facebook.orca" to "https://www.messenger.com",
        "com.whatsapp" to "https://web.whatsapp.com",
    )

    private val URL = Regex("(?i)https?://[^\\s]+")
    private val HOST = Regex("(?i)\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b")
    private val EXPLICIT_CHROME_DESTINATION = Regex(
        """(?i)\b(?:open|launch)\s+(?:google\s+)?chrome\b.*?\b(?:open|go\s+to|navigate\s+to)\s+(?:the\s+)?([a-z0-9][a-z0-9.-]*)\b""",
    )
    private val DESTINATION_CUE = Regex("(?i)(?:open|launch|start|go to|navigate to|on|in|then)\\s+(?:the\\s+|my\\s+)?$")
    private val INSTRUMENT_CUE = Regex("(?i)(?:using|with|via)\\s+(?:the\\s+|my\\s+)?$")

    fun resolve(goal: String, installed: List<InstalledApp> = InstalledAppInventory.snapshot): FastPathLandingHint? {
        val trimmed = goal.trim()
        if (trimmed.isBlank()) return null

        explicitChromeDestination(trimmed, installed)?.let { uri ->
            return FastPathLandingHint(
                tool = "phone.launch_intent",
                uri = uri,
                reason = "Goal explicitly says to use Chrome for this destination. Preserve the browser route instead of switching to the native app.",
            )
        }

        NavigationIntent.parse(trimmed, installed)?.let { intent ->
            val host = intent.target.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            val uri = if ('.' in host) "https://$host" else "https://$host.com"
            return FastPathLandingHint(
                tool = "phone.launch_intent",
                uri = sanitizeUri(uri),
                reason = "Goal names a website. Prefer phone.launch_intent over hunting a browser icon.",
            )
        }

        URL.find(trimmed)?.value?.let { raw ->
            val uri = sanitizeUri(raw) ?: return@let
            return FastPathLandingHint(
                tool = "phone.launch_intent",
                uri = uri,
                reason = "Goal contains an http(s) URL. Prefer phone.launch_intent over hunting a launcher icon.",
            )
        }

        namedApp(trimmed, installed)?.let { (alias, packageName) ->
            return FastPathLandingHint(
                tool = "phone.open_app",
                packageName = packageName,
                reason = "Goal names $alias. Prefer phone.open_app before hunting a launcher icon. 3.9.12 Ask→workspace uses the same named-app match.",
                workspaceNamedApp = true,
            )
        }

        HOST.find(trimmed)?.value?.let { host ->
            if (host.equals("android.com", ignoreCase = true)) return@let
            val uri = sanitizeUri("https://${host.removePrefix("www.")}") ?: return@let
            return FastPathLandingHint(
                tool = "phone.launch_intent",
                uri = uri,
                reason = "Goal names a web host. Prefer phone.launch_intent over hunting a browser icon.",
            )
        }

        ImplicitAppRouter.resolve(trimmed, installed)?.landing()?.let { return it }
        com.cyclone.mobile.brain.UserMdRuntime.landing(trimmed)?.let { return it }
        return null
    }

    private fun explicitChromeDestination(goal: String, installed: List<InstalledApp>): String? {
        val raw = EXPLICIT_CHROME_DESTINATION.find(goal)?.groupValues?.getOrNull(1)
            ?.trim()?.trimEnd('.', ',', ';', ':')?.lowercase() ?: return null
        if ('.' in raw) return sanitizeUri("https://${raw.removePrefix("www.")}")
        val packageName = namedApp("open $raw", installed)?.second
        packageName?.let(::webFallback)?.let(::sanitizeUri)?.let { return it }
        return sanitizeUri("https://$raw.com")
    }

    fun namedAppHits(goal: String, installed: List<InstalledApp> = InstalledAppInventory.snapshot): List<NamedAppHit> {
        val lower = goal.lowercase()
        val hits = mutableListOf<NamedAppHit>()
        val packages = mutableSetOf<String>()
        InstalledAppLexicon.triggers(installed)
            .sortedByDescending { it.alias.length }
            .forEach { trigger ->
                if (trigger.packageName in packages) return@forEach
                val match = Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(trigger.alias) + "(?![\\p{L}\\p{N}])").find(lower)
                    ?: return@forEach
                val before = lower.substring(0, match.range.first)
                val destinationCue = DESTINATION_CUE.containsMatchIn(before)
                val instrumentCue = INSTRUMENT_CUE.containsMatchIn(before)
                if (trigger.generic && !destinationCue) return@forEach
                packages += trigger.packageName
                hits += NamedAppHit(
                    alias = trigger.alias,
                    packageName = trigger.packageName,
                    index = match.range.first,
                    destinationCue = destinationCue,
                    instrumentOnly = instrumentCue && !destinationCue,
                )
            }
        return hits.sortedBy { it.index }
    }

    fun namedApp(goal: String, installed: List<InstalledApp> = InstalledAppInventory.snapshot): Pair<String, String>? {
        val hits = namedAppHits(goal, installed)
        if (hits.isEmpty()) return null
        val preferred = hits.filterNot { it.instrumentOnly }.ifEmpty { hits }
        val chosen = preferred.minWithOrNull(
            compareByDescending<NamedAppHit> { if (it.destinationCue) 1 else 0 }
                .thenByDescending { it.alias.length }
                .thenBy { it.index },
        ) ?: return null
        return chosen.alias to chosen.packageName
    }

    fun packageForName(name: String, installed: List<InstalledApp> = InstalledAppInventory.snapshot): String? {
        val clean = name.trim()
        if (clean.isBlank()) return null
        namedApp(clean, installed)?.second?.let { return it }
        return namedApp("open $clean", installed)?.second
    }

    fun sanitizeUri(raw: String): String? {
        val clean = raw.trim().substringBefore('#').substringBefore('?').trimEnd('/')
        if (clean.startsWith("geo:", ignoreCase = true)) {
            return raw.trim().take(240).takeIf { ':' !in it.substringAfter("geo:").substringBefore('?').substringBefore(',') }
                ?: raw.trim().take(240)
        }
        if (!(clean.startsWith("https://", ignoreCase = true) || clean.startsWith("http://", ignoreCase = true))) {
            return null
        }
        if (':' in clean.substringAfter("://").substringBefore('/')) return null
        return clean.take(240)
    }

    fun launchCandidates(packageName: String): List<String> =
        (listOf(packageName) + (PACKAGE_FALLBACKS[packageName] ?: emptyList())).distinct()

    fun webFallback(packageName: String): String? = WEB_FALLBACKS[packageName]
}
