package com.cyclone.mobile.fastpath

data class InstalledApp(
    val packageName: String,
    val label: String,
    val openSuccessCount: Int = 0,
)

data class PhoneJob(
    val id: String,
    val label: String,
    val packages: List<String>,
    val labelHints: List<String>,
    val query: String? = null,
    val webFallback: String? = null,
)

data class ImplicitAppChoice(
    val job: PhoneJob,
    val packageName: String,
    val appLabel: String,
    val reason: String,
    val uri: String? = null,
) {
    fun landing(): FastPathLandingHint = if (!uri.isNullOrBlank()) {
        FastPathLandingHint(
            tool = "phone.launch_intent",
            packageName = packageName,
            uri = uri,
            reason = reason,
        )
    } else {
        FastPathLandingHint(
            tool = "phone.open_app",
            packageName = packageName,
            reason = reason,
        )
    }
}

/**
 * When the ask does not name an app, pick the installed app a person would actually open.
 * Local only: job from the sentence, winner from the launcher inventory. No model.
 */
object ImplicitAppRouter {
    private val MAPS = listOf(
        "com.google.android.apps.maps",
        "com.waze",
        "com.google.android.apps.navlite",
    )
    private val LODGING = MAPS + listOf(
        "com.booking",
        "com.airbnb.android",
        "com.hotels.hotelsdotcom",
        "com.tripadvisor.tripadvisor",
        "com.expedia.bookings",
    )
    private val CALENDAR = listOf(
        "com.google.android.calendar",
        "com.samsung.android.calendar",
        "com.microsoft.office.outlook",
        "com.google.android.gm",
    )
    private val INBOX = listOf(
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "com.samsung.android.email.provider",
        "com.yahoo.mobile.client.android.mail",
    )
    private val MESSAGES = listOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.whatsapp",
        "org.telegram.messenger",
        "com.facebook.orca",
    )
    private val CHROME = "com.android.chrome"

    private val LODGING_FIND = Regex(
        "(?i)\\b(?:find|search|look(?:ing)?(?:\\s+(?:for|up))?|show me|where(?:'s| is)|near)\\b.*\\b(hotels?|motels?|airbnbs?|place to stay|where to stay)\\b",
    )
    private val LODGING_NEAR = Regex(
        "(?i)\\b(hotels?|motels?|airbnbs?|place to stay)\\b.*\\b(near(?:by)?|close by|around|in my area|close to me)\\b",
    )
    private val NEARBY = Regex("(?i)\\b(near me|nearby|close by|around here|around me|in my area)\\b")
    private val DIRECTIONS = Regex("(?i)\\b(directions to|navigate to|take me to)\\b")
    private val CALENDAR_PERSONAL = Regex(
        "(?i)\\b(?:my|i have|i've got|look(?:ing)?(?:\\s+(?:at|up|when|what time))?|check|when do i|what(?:'s| is) on my)\\b.*\\b(appointment|meeting|calendar|schedule)\\b",
    )
    private val CALENDAR_WHEN = Regex("(?i)\\b(appointment|meeting|calendar|schedule)\\b.*\\b(today|tomorrow|this week|tonight)\\b")
    private val INBOX_CHECK = Regex("(?i)\\b(?:check|open|read|show)\\b.*\\b(?:my )?(?:e-?mail|inbox|unread mail)\\b")
    private val MESSAGES_CHECK = Regex("(?i)\\b(?:check|open|read|show)\\b.*\\b(?:my )?(?:texts?|sms|messages)\\b")

    fun job(goal: String, installed: List<InstalledApp> = InstalledAppInventory.snapshot): PhoneJob? {
        val clean = goal.trim()
        if (clean.isBlank()) return null
        if (FastPathLanding.namedApp(clean, installed) != null) return null
        if (com.cyclone.mobile.agent.contract.NavigationIntent.parse(clean, installed) != null) return null
        return when {
            LODGING_FIND.containsMatchIn(clean) || LODGING_NEAR.containsMatchIn(clean) -> PhoneJob(
                id = "lodging",
                label = "Maps",
                packages = LODGING,
                labelHints = listOf("maps", "booking", "airbnb", "hotels", "tripadvisor", "expedia", "waze"),
                query = lodgingQuery(clean),
                webFallback = "https://www.google.com/maps/search/${lodgingQuery(clean).replace(' ', '+')}",
            )
            DIRECTIONS.containsMatchIn(clean) || NEARBY.containsMatchIn(clean) -> PhoneJob(
                id = "maps",
                label = "Maps",
                packages = MAPS,
                labelHints = listOf("maps", "waze"),
                query = nearbyQuery(clean),
                webFallback = "https://www.google.com/maps/search/${nearbyQuery(clean).replace(' ', '+')}",
            )
            CALENDAR_PERSONAL.containsMatchIn(clean) || CALENDAR_WHEN.containsMatchIn(clean) -> PhoneJob(
                id = "calendar",
                label = "Calendar",
                packages = CALENDAR,
                labelHints = listOf("calendar", "outlook"),
            )
            INBOX_CHECK.containsMatchIn(clean) -> PhoneJob(
                id = "inbox",
                label = "Gmail",
                packages = INBOX,
                labelHints = listOf("gmail", "outlook", "mail", "email"),
            )
            MESSAGES_CHECK.containsMatchIn(clean) -> PhoneJob(
                id = "messages",
                label = "Messages",
                packages = MESSAGES,
                labelHints = listOf("messages", "messaging", "sms", "whatsapp", "telegram", "messenger"),
            )
            else -> null
        }
    }

    fun resolve(goal: String, installed: List<InstalledApp>): ImplicitAppChoice? {
        val phoneJob = job(goal, installed) ?: return null
        val ranked = installed.mapNotNull { app ->
            val index = phoneJob.packages.indexOf(app.packageName)
            val hintHit = phoneJob.labelHints.any { hint ->
                app.label.contains(hint, ignoreCase = true) || app.packageName.contains(hint, ignoreCase = true)
            }
            val score = when {
                index >= 0 -> 1000 - index * 12
                hintHit -> 400
                else -> return@mapNotNull null
            } + app.openSuccessCount.coerceAtMost(40) * 6
            Triple(score, index.takeIf { it >= 0 } ?: 99, app)
        }.sortedWith(compareByDescending<Triple<Int, Int, InstalledApp>> { it.first }.thenBy { it.second })
        val winner = ranked.firstOrNull()?.third
            ?: installed.firstOrNull { it.packageName == CHROME }
        if (winner == null) return null
        val useWeb = winner.packageName == CHROME && !phoneJob.webFallback.isNullOrBlank()
        val uri = when {
            useWeb -> phoneJob.webFallback
            !phoneJob.query.isNullOrBlank() && winner.packageName in MAPS ->
                "geo:0,0?q=" + phoneJob.query.replace(' ', '+')
            else -> null
        }
        val reason = "You asked to ${phoneJob.id.replace('_', ' ')}. ${winner.label} is installed, so Cyclone opens that instead of waiting for an app name."
        return ImplicitAppChoice(phoneJob, winner.packageName, winner.label, reason, uri)
    }

    private fun lodgingQuery(goal: String): String {
        val place = Regex("(?i)\\b(?:in|near|around)\\s+([\\p{L}0-9][\\p{L}0-9\\s]{1,40})$").find(goal.trim())
            ?.groupValues?.getOrNull(1)?.trim()
        return when {
            !place.isNullOrBlank() && !place.equals("me", true) && !place.equals("here", true) -> "hotels $place"
            else -> "hotels nearby"
        }
    }

    private fun nearbyQuery(goal: String): String {
        val target = Regex("(?i)\\b(?:find|search(?:\\s+for)?|look(?:ing)?(?:\\s+for)?|directions to|navigate to|take me to)\\s+(.+)$")
            .find(goal.trim())?.groupValues?.getOrNull(1)
            ?.replace(Regex("(?i)\\b(near me|nearby|close by|around here|around me|in my area)\\b"), " ")
            ?.trim()
        return when {
            !target.isNullOrBlank() -> target.take(48)
            else -> "nearby"
        }
    }
}

object InstalledAppInventory {
    @Volatile
    var snapshot: List<InstalledApp> = emptyList()
        private set

    fun replace(apps: List<InstalledApp>) {
        snapshot = apps
    }

    fun refresh(context: android.content.Context) {
        replace(runCatching { fromLauncher(context) }.getOrElse { emptyList() })
    }

    fun fromLauncher(context: android.content.Context): List<InstalledApp> {
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        return context.packageManager
            .queryIntentActivities(launcher, android.content.pm.PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                val label = info.loadLabel(context.packageManager)?.toString().orEmpty().ifBlank { packageName }
                InstalledApp(packageName, label.take(80))
            }
            .distinctBy { it.packageName }
    }
}
