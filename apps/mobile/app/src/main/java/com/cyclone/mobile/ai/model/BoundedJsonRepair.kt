package com.cyclone.mobile.ai.model

/** Single-pass wrapper repair only. It never retries semantic model content. */
object BoundedJsonRepair {
    fun extractSingleObject(raw: String): String? {
        val text = raw.trim()
        if (text.isBlank()) return null
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> quoted = false
                }
                continue
            }
            when (ch) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                    if (depth < 0) return null
                }
            }
        }
        return null
    }
}
