package com.cyclone.mobile.market

/** Cyclone's own listings, shipped with the app so the store works without a PC or a network. */
object MarketCatalog {
    private val CYCLONE = Publisher("cyclone", "Cyclone", verified = true)
    private const val CLOCK = "com.google.android.deskclock"
    private const val SETTINGS = "com.android.settings"
    private const val GMAIL = "com.google.android.gm"
    private const val CALENDAR = "com.google.android.calendar"
    private const val WHATSAPP = "com.whatsapp"
    private const val MAPS = "com.google.android.apps.maps"
    private const val SPOTIFY = "com.spotify.music"
    private const val YOUTUBE = "com.google.android.youtube"
    private const val CHROME = "com.android.chrome"
    private const val FILES = "com.google.android.apps.nbu.files"

    private fun recipe(
        id: String, name: String, glyph: String, category: String, summary: String, goal: String,
        does: List<String>, apps: List<String> = emptyList(), inputs: List<MarketInput> = emptyList(),
        asksFirst: List<String> = emptyList(), suggestFor: List<String> = apps, featured: Boolean = false,
    ) = MarketListing("cyclone.$id", ListingKind.RECIPE, "1.0.0", name, CYCLONE, summary, category, glyph, goal, inputs,
        apps, does, asksFirst, suggestFor, featured)

    val LISTINGS: List<MarketListing> = listOf(
        recipe("notification-digest", "Notification digest", "🔔", "Daily",
            "What needs you, from every app, in a few lines.",
            "Read my notifications and give me a short summary of what needs my attention, grouped by app. Don't open, reply to or dismiss anything.",
            does = listOf("Reads your notifications"), featured = true, suggestFor = emptyList()),
        recipe("focus-timer", "Focus timer", "⏱", "Productivity",
            "A timer plus Do Not Disturb, in one go.",
            "Set a timer for {minutes} minutes and turn on Do Not Disturb.",
            does = listOf("Uses the Clock app", "Changes a phone setting"), apps = listOf(CLOCK, SETTINGS),
            inputs = listOf(MarketInput("minutes", "Minutes", InputKind.NUMBER, "25")), featured = true, suggestFor = listOf(CLOCK)),
        recipe("inbox-brief", "Inbox brief", "✉", "Daily",
            "Your most important unread email, one line each.",
            "Open Gmail and tell me the {count} most important unread emails, with the sender and one line each. Don't open links, reply or archive anything.",
            does = listOf("Reads your email"), apps = listOf(GMAIL),
            inputs = listOf(MarketInput("count", "How many", InputKind.NUMBER, "5")), featured = true),
        recipe("whatsapp-catch-up", "WhatsApp catch-up", "💬", "Messages",
            "Who wrote, and what they need, without opening every chat.",
            "Open WhatsApp and summarize my unread chats: who wrote and what they need from me. Don't reply to anyone.",
            does = listOf("Reads your chats"), apps = listOf(WHATSAPP), featured = true),
        recipe("send-whatsapp", "Send a WhatsApp", "↗", "Messages",
            "Write a message to someone; you approve before it is sent.",
            "Send {person} this message on WhatsApp: {message}",
            does = listOf("Writes a message in WhatsApp"), apps = listOf(WHATSAPP),
            inputs = listOf(MarketInput("person", "To"), MarketInput("message", "Message")),
            asksFirst = listOf("Sending the message")),
        recipe("today", "Today at a glance", "📅", "Daily",
            "Your day from the calendar, with times.",
            "Open my calendar and tell me what is on today, with times and places. Don't change anything.",
            does = listOf("Reads your calendar"), apps = listOf(CALENDAR)),
        recipe("directions", "Directions", "🧭", "Travel",
            "Directions to anywhere, the way you travel.",
            "Open Google Maps and show {mode} directions to {place}.",
            does = listOf("Uses Google Maps"), apps = listOf(MAPS),
            inputs = listOf(MarketInput("place", "Where to"), MarketInput("mode", "How", InputKind.CHOICE, "driving",
                listOf("driving", "public transport", "walking", "cycling")))),
        recipe("play-music", "Play music", "♫", "Media",
            "Say what you want to hear; Spotify plays it.",
            "Open Spotify and play {what}.",
            does = listOf("Uses Spotify"), apps = listOf(SPOTIFY), inputs = listOf(MarketInput("what", "What to play"))),
        recipe("youtube-find", "Find a video", "▶", "Media",
            "The most relevant recent YouTube video on a topic.",
            "Search YouTube for {topic} and open the most relevant recent video.",
            does = listOf("Uses YouTube"), apps = listOf(YOUTUBE), inputs = listOf(MarketInput("topic", "Topic"))),
        recipe("quick-answer", "Quick answer", "?", "Web",
            "A short answer from the web, with where it came from.",
            "Search the web for: {question}. Give me a short answer and name the source.",
            does = listOf("Uses the browser"), apps = listOf(CHROME), inputs = listOf(MarketInput("question", "Question"))),
        recipe("wind-down", "Wind down", "🌙", "Settings",
            "Dark theme, lower brightness and Do Not Disturb.",
            "Turn on the dark theme, lower the screen brightness to about a third, and turn on Do Not Disturb.",
            does = listOf("Changes phone settings"), apps = listOf(SETTINGS), suggestFor = emptyList()),
        recipe("battery-check", "Battery check", "🔋", "Phone",
            "Battery level and what used it today.",
            "Check my battery level and which apps used the most battery today, and tell me in two lines. Don't change any setting.",
            does = listOf("Reads battery settings"), apps = listOf(SETTINGS), suggestFor = emptyList()),
        recipe("free-space", "Free up space", "🗂", "Phone",
            "How much you could free, and from what. Nothing is deleted.",
            "Open Files by Google and tell me how much space I can free up and from what. Don't delete anything.",
            does = listOf("Reads your storage"), apps = listOf(FILES)),
        recipe("quiet-until", "Quiet until…", "🔕", "Settings",
            "Do Not Disturb for a meeting, off again by itself.",
            "Turn on Do Not Disturb until {time}.",
            does = listOf("Changes a phone setting"), apps = listOf(SETTINGS),
            inputs = listOf(MarketInput("time", "Until (for example 15:30)")), suggestFor = emptyList()),
    )

    fun byId(id: String): MarketListing? = LISTINGS.firstOrNull { it.id == id }
}
