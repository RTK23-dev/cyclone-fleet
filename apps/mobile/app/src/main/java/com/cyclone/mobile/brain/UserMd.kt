package com.cyclone.mobile.brain

import com.cyclone.mobile.fastpath.FastPathLanding

data class UserPerson(
    val name: String,
    val apps: List<String>,
)

data class UserMdDocument(
    val me: String = "",
    val people: List<UserPerson> = emptyList(),
    val apps: List<Pair<String, String>> = emptyList(),
    val cues: List<String> = listOf(DEFAULT_CUE),
) {
    fun render(): String = buildString {
        appendLine("# Me")
        if (me.isNotBlank()) appendLine(me.trim()).appendLine() else appendLine()
        appendLine("# People")
        if (people.isEmpty()) appendLine()
        people.forEach { person -> appendLine("- ${person.name}: ${person.apps.joinToString(", ")}") }
        if (people.isNotEmpty()) appendLine()
        appendLine("# Apps")
        if (apps.isEmpty()) appendLine()
        apps.forEach { (job, app) -> appendLine("- $job: $app") }
        if (apps.isNotEmpty()) appendLine()
        appendLine("# Cues")
        val cueLines = cues.ifEmpty { listOf(DEFAULT_CUE) }
        cueLines.forEach { appendLine("- $it") }
    }.trimEnd().plus("\n")

    fun slice(goal: String, limitChars: Int = 800): UserMdSlice {
        val lower = goal.lowercase()
        val matchedPeople = people.filter { person ->
            Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(person.name) + "(?![\\p{L}\\p{N}])").containsMatchIn(goal)
        }
        val matchedApps = apps.filter { (job, app) ->
            lower.contains(job.lowercase()) || lower.contains(app.lowercase())
        }
        val askWhich = matchedPeople.filter { it.apps.size >= 2 }.map { it.name }
        val lines = mutableListOf<String>()
        if (matchedPeople.isNotEmpty() && me.isNotBlank()) lines += me.trim().lines().first().take(120)
        matchedPeople.forEach { person ->
            lines += if (person.apps.size >= 2) {
                "${person.name} is on ${person.apps.joinToString(" and ")}. Ask which app."
            } else {
                "${person.name} is on ${person.apps.joinToString()}."
            }
        }
        matchedApps.forEach { (job, app) -> lines += "For $job, use $app." }
        if (askWhich.isNotEmpty()) lines += cues.take(2)
        val text = lines.distinct().joinToString("\n").take(limitChars)
        val landingApp = matchedPeople.singleOrNull { it.apps.size == 1 }?.apps?.singleOrNull()
        return UserMdSlice(text = text, askWhich = askWhich, preferredApp = landingApp.takeIf { askWhich.isEmpty() })
    }

    fun upsertPerson(name: String, apps: List<String>): UserMdDocument {
        val cleanName = name.trim().replaceFirstChar { it.titlecase() }
        if (cleanName.isBlank() || cleanName.lowercase() in NAME_BLOCK) return this
        val labels = apps.map(::canonicalApp).filter { it.isNotBlank() }.distinct()
        if (labels.isEmpty()) return this
        val next = people.toMutableList()
        val index = next.indexOfFirst { it.name.equals(cleanName, ignoreCase = true) }
        if (index < 0) next += UserPerson(cleanName, labels)
        else next[index] = UserPerson(next[index].name, (next[index].apps + labels).distinct())
        return copy(people = next.take(24))
    }

    fun upsertApp(job: String, app: String): UserMdDocument {
        val key = job.trim().lowercase()
        val label = canonicalApp(app)
        if (key.isBlank() || label.isBlank()) return this
        val next = apps.toMutableList()
        val index = next.indexOfFirst { it.first.equals(key, ignoreCase = true) }
        if (index < 0) next += key to label else next[index] = key to label
        return copy(apps = next.take(16))
    }

    companion object {
        const val DEFAULT_CUE = "If someone is on more than one app, ask which one."
        val NAME_BLOCK = setOf(
            "open", "please", "hey", "hi", "can", "could", "would", "check", "find", "look",
            "search", "show", "the", "my", "me", "you", "cyclone", "chrome", "maps", "calendar",
            "instagram", "whatsapp", "facebook", "gmail", "settings", "google", "android", "message",
        )
        val APP_LABELS = linkedMapOf(
            "instagram" to "Instagram", "insta" to "Instagram", "ig" to "Instagram",
            "whatsapp" to "WhatsApp", "messenger" to "Messenger",
            "facebook" to "Facebook", "fb" to "Facebook",
            "gmail" to "Gmail", "messages" to "Messages", "sms" to "Messages",
            "maps" to "Maps", "calendar" to "Calendar", "booking" to "Booking",
            "airbnb" to "Airbnb", "chrome" to "Chrome",
        )
        val PACKAGE_LABELS = mapOf(
            "com.instagram.android" to "Instagram",
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp",
            "com.facebook.orca" to "Messenger",
            "com.facebook.katana" to "Facebook",
            "com.google.android.gm" to "Gmail",
            "com.google.android.apps.messaging" to "Messages",
            "com.google.android.apps.maps" to "Maps",
            "com.google.android.calendar" to "Calendar",
            "com.booking" to "Booking",
            "com.airbnb.android" to "Airbnb",
            "com.android.chrome" to "Chrome",
        )

        fun empty() = UserMdDocument()

        fun parse(raw: String): UserMdDocument {
            val sections = linkedMapOf<String, StringBuilder>()
            var current = "me"
            sections[current] = StringBuilder()
            raw.replace("\r\n", "\n").lines().forEach { line ->
                val heading = Regex("^#\\s+(me|people|apps|cues)\\s*$", RegexOption.IGNORE_CASE).find(line)
                if (heading != null) {
                    current = heading.groupValues[1].lowercase()
                    sections.putIfAbsent(current, StringBuilder())
                } else {
                    sections.getOrPut(current) { StringBuilder() }.appendLine(line)
                }
            }
            val me = sections["me"]?.toString()?.trim().orEmpty()
            val people = bullets(sections["people"]?.toString()).mapNotNull { bullet ->
                val (name, rest) = splitKv(bullet) ?: return@mapNotNull null
                val apps = rest.split(',', '/', '&', '|').map { canonicalApp(it) }.filter { it.isNotBlank() }
                if (name.lowercase() in NAME_BLOCK || apps.isEmpty()) null else UserPerson(name.replaceFirstChar { it.titlecase() }, apps.distinct())
            }
            val apps = bullets(sections["apps"]?.toString()).mapNotNull { bullet ->
                val (job, app) = splitKv(bullet) ?: return@mapNotNull null
                job.lowercase() to canonicalApp(app)
            }.filter { it.first.isNotBlank() && it.second.isNotBlank() }
            val cues = bullets(sections["cues"]?.toString()).ifEmpty { listOf(DEFAULT_CUE) }
            return UserMdDocument(me, people.take(24), apps.take(16), cues.take(8))
        }

        fun extract(goal: String, packages: Set<String> = emptySet()): UserMdDocument {
            var doc = empty()
            NAME_ON_APP.findAll(goal).forEach { match ->
                doc = doc.upsertPerson(match.groupValues[1], listOf(match.groupValues[2]))
            }
            NAME_AFTER_VERB.findAll(goal).forEach { match ->
                val apps = packages.mapNotNull(PACKAGE_LABELS::get).ifEmpty {
                    FastPathLanding.namedApp(goal)?.first?.let { listOf(canonicalApp(it)) } ?: emptyList()
                }
                doc = doc.upsertPerson(match.groupValues[1], apps)
            }
            return doc
        }

        fun canonicalApp(raw: String): String {
            val clean = raw.trim().trimStart('@')
            if (clean.isBlank()) return ""
            PACKAGE_LABELS[clean.lowercase()]?.let { return it }
            val key = clean.lowercase().replace(" ", "")
            APP_LABELS.entries.firstOrNull { key.contains(it.key) }?.let { return it.value }
            return clean.replaceFirstChar { it.titlecase() }.take(24)
        }

        private fun bullets(section: String?): List<String> = section.orEmpty().lines()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotBlank() }

        private fun splitKv(line: String): Pair<String, String>? {
            val index = line.indexOf(':')
            if (index <= 0) return null
            val key = line.take(index).trim()
            val value = line.substring(index + 1).trim()
            if (key.isBlank() || value.isBlank()) return null
            return key to value
        }

        private val NAME_AFTER_VERB = Regex("(?i)\\b(?:dm|message|text|tell|call|ping)\\s+([\\p{Lu}][\\p{L}'\\-]{1,24})")
        private val NAME_ON_APP = Regex(
            "(?i)\\b([\\p{Lu}][\\p{L}'\\-]{1,24})\\s+on\\s+(instagram|insta|ig|whatsapp|messenger|facebook|fb|gmail|messages)\\b",
        )
    }
}

data class UserMdSlice(
    val text: String,
    val askWhich: List<String> = emptyList(),
    val preferredApp: String? = null,
) {
    val useful: Boolean get() = text.isNotBlank()
}
