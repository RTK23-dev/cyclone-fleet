package com.cyclone.mobile.fastpath

data class AppNameTrigger(
    val alias: String,
    val packageName: String,
    val generic: Boolean = false,
)

/**
 * Easy "open X" matching uses the phone's launcher inventory, plus a small nickname list.
 * Local only. No model.
 */
object InstalledAppLexicon {
    private val GENERIC = setOf(
        "app", "apps", "phone", "messages", "message", "sms", "text", "texts",
        "clock", "alarm", "files", "file", "photos", "photo", "camera", "settings",
        "maps", "map", "chrome", "contacts", "contact", "calendar", "mail", "email",
        "gmail", "notes", "note", "drive", "home", "pay", "wallet", "meet", "chat",
        "news", "store", "play", "go", "now", "keep", "fit", "docs", "sheets",
        "slides", "search", "assistant", "gallery", "calculator", "weather", "music",
        "radio", "video", "browser", "launcher", "keyboard", "download", "downloads",
        "security", "health", "shop", "games", "game", "bank", "cash", "post",
        "plus", "one", "time", "today", "help", "info", "setup", "update",
        "play store", "phone app", "my files", "signal",
    )
    private val VENDOR = Regex("^(google|samsung|microsoft|android|meta)\\s+")
    private val EDITION = Regex("\\s+(lite|beta|dev|preview|canary|debug)$")
    private val DOMAIN = Regex("\\.(com|net|org|io|app)$")
    private val VENDOR_WORD = setOf("google", "samsung", "microsoft", "android", "meta")

    fun triggers(
        installed: List<InstalledApp> = InstalledAppInventory.snapshot,
        aliases: Map<String, String> = FastPathLanding.APP_PACKAGE_ALIASES,
    ): List<AppNameTrigger> {
        val out = ArrayList<AppNameTrigger>(aliases.size + installed.size * 3)
        aliases.forEach { (alias, packageName) ->
            val key = normalize(alias)
            if (key.isNotEmpty()) out += AppNameTrigger(key, packageName, generic = false)
        }
        installed.forEach { app ->
            tokensFor(app).forEach { token ->
                out += AppNameTrigger(token, app.packageName, generic = isGeneric(token))
            }
        }
        return out
    }

    fun tokensFor(app: InstalledApp): List<String> {
        val raw = app.label.trim()
        if (raw.isBlank()) return emptyList()
        val tokens = linkedSetOf<String>()
        fun add(value: String) {
            val token = normalize(value)
            if (token.length < 2) return
            if (token.length == 2 && isGeneric(token)) return
            tokens += token
        }
        add(raw)
        add(EDITION.replace(normalize(raw), ""))
        add(DOMAIN.replace(normalize(raw), ""))
        val noVendor = VENDOR.replace(normalize(raw), "")
        if (noVendor.isNotBlank() && noVendor != normalize(raw)) add(noVendor)
        normalize(raw).split(' ').filter { it.isNotBlank() }.forEach { word ->
            if (word.length >= 5 && !isGeneric(word) && word !in VENDOR_WORD) add(word)
        }
        return tokens.toList()
    }

    fun isGeneric(token: String): Boolean = normalize(token) in GENERIC

    fun normalize(raw: String): String =
        raw.lowercase()
            .replace('’', '\'')
            .replace(Regex("[®™©]"), " ")
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
}
