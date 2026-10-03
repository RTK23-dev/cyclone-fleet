package com.cyclone.mobile.ui.overlay

/**
 * Plan 27: the apps a task has worked in, in order, so the card header can show them. Only the display remembers
 * this (never stored); the last [LIMIT] distinct apps, the current one last.
 */
object TaskAppTrail {
    const val LIMIT = 3
    private val trails = LinkedHashMap<String, List<String>>()

    @Synchronized
    fun record(taskId: String, packageName: String?): List<String> {
        val current = trails[taskId].orEmpty()
        if (packageName.isNullOrBlank() || packageName == "com.cyclone.mobile") return current
        val next = (current.filter { it != packageName } + packageName).takeLast(LIMIT)
        trails[taskId] = next
        while (trails.size > 8) trails.remove(trails.keys.first())
        return next
    }

    @Synchronized
    fun of(taskId: String): List<String> = trails[taskId].orEmpty()
}
