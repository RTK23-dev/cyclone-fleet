package com.cyclone.mobile.manual

/**
 * The fixed core of the App Manual (plan 36 §7.2): the few kinds of thing every app shares. Every set in an app's
 * dictionary sits under exactly one core kind. The core changes only with a release (with fixtures), never at run
 * time, so "a person" means the same thing in every app.
 *
 * [hints] are generic words (developer resource ids and UI words, a few languages) that suggest a kind. They are
 * hints only: a set whose kind they cannot settle goes to the organizer's question as [OTHER].
 */
enum class CoreKind(val wire: String, val label: String, private val hints: List<String>) {
    PERSON("person", "Person", listOf("user", "users", "people", "person", "profile", "contact", "contacts", "follower",
        "followers", "following", "friend", "friends", "member", "members", "account", "accounts", "subscriber",
        "subscribers", "blocked", "muted", "restricted", "close friends", "vrienden", "volgers", "freunde", "amis", "amigos")),
    CONVERSATION("conversation", "Conversation", listOf("thread", "threads", "conversation", "conversations", "chat", "chats",
        "inbox", "direct", "dm", "dms", "messages", "message requests", "gesprekken", "berichten")),
    MESSAGE("message", "Message", listOf("message_bubble", "bubble", "reply", "replies", "comment", "comments")),
    POST("post", "Post", listOf("post", "posts", "feed", "reel", "reels", "story", "stories", "tweet", "tweets", "article",
        "articles", "tagged", "saved posts")),
    MEDIA("media", "Media", listOf("photo", "photos", "video", "videos", "image", "images", "gallery", "album", "albums",
        "media", "camera roll", "music", "song", "songs", "playlist", "playlists", "podcast", "podcasts")),
    FILE("file", "File", listOf("file", "files", "document", "documents", "attachment", "attachments", "download", "downloads")),
    LINK("link", "Link", listOf("link", "links", "bookmark", "bookmarks", "url")),
    PLACE("place", "Place", listOf("place", "places", "location", "locations", "map", "address", "addresses", "nearby")),
    EVENT("event", "Event", listOf("event", "events", "calendar", "meeting", "meetings", "reminder", "reminders", "agenda")),
    TASK("task", "Task", listOf("task", "tasks", "todo", "to-do", "checklist")),
    PRODUCT("product", "Product", listOf("product", "products", "item", "items", "listing", "listings", "shop", "store", "catalog", "wishlist")),
    ORDER("order", "Order", listOf("order", "orders", "purchase", "purchases", "cart", "basket", "delivery", "deliveries")),
    PAYMENT("payment", "Payment", listOf("payment", "payments", "transaction", "transactions", "card", "cards", "wallet", "invoice", "invoices")),
    ACCOUNT("account", "Account", listOf("login", "sign in", "switch account", "accounts center")),
    SETTING("setting", "Setting", listOf("setting", "settings", "preference", "preferences", "privacy", "notifications settings", "instellingen", "einstellungen")),
    NOTIFICATION("notification", "Notification", listOf("notification", "notifications", "activity", "alert", "alerts", "meldingen")),
    GROUP("group", "Group", listOf("group", "groups", "community", "communities", "team", "teams", "server", "servers")),
    CHANNEL("channel", "Page or channel", listOf("channel", "channels", "page", "pages", "subscription", "subscriptions", "creator", "creators")),
    SEARCH_RESULT("search_result", "Search result", listOf("search result", "results", "suggestion", "suggestions", "recent searches")),
    DRAFT("draft", "Draft", listOf("draft", "drafts", "outbox", "scheduled")),
    COLLECTION("collection", "Collection", listOf("collection", "collections", "folder", "folders", "board", "boards", "label", "labels", "list", "lists")),
    TOOL("tool", "Tool or connector", listOf("tool", "tools", "connector", "connectors", "app", "apps", "plugin", "plugins", "extension", "extensions", "integration", "integrations")),
    ASSISTANT("assistant", "Assistant or model", listOf("model", "models", "assistant", "assistants", "gpt", "gpts", "agent", "agents", "bot", "bots")),
    OTHER("other", "Other", emptyList());

    companion object {
        fun fromWire(value: String?): CoreKind? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }

        /**
         * The kind suggested by the given evidence (a set's name, the screen title, resource ids of rows), or [OTHER]
         * when no kind is clearly ahead. Resource ids are split on `_`, `/` and `:` so `row_user_name` counts as "user".
         */
        fun guess(evidence: List<String>): CoreKind {
            val text = evidence.joinToString(" ") { it.lowercase().replace(Regex("[/_:.]+"), " ") }
            if (text.isBlank()) return OTHER
            val words = " ${text.replace(Regex("[^a-z0-9 -]+"), " ").replace(Regex("\\s+"), " ").trim()} "
            val scores = entries.filter { it != OTHER }.associateWith { kind ->
                kind.hints.count { hint -> words.contains(" $hint ") }
            }
            val best = scores.maxByOrNull { it.value } ?: return OTHER
            if (best.value == 0) return OTHER
            val tied = scores.count { it.value == best.value }
            return if (tied > 1) OTHER else best.key
        }
    }
}
