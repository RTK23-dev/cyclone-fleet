package com.cyclone.mobile.runtime.background

/** Only typed display-targeted input can reach the privileged process. No shell source strings. */
object WorkspaceCommands {
    const val TAP = 1
    const val SWIPE = 2
    const val BACK = 3
    const val TEXT = 4
    fun input(displayId: Int, kind: Int, coordinates: FloatArray, text: String): List<String> {
        require(displayId > 0) { "Background input requires a nonzero display" }
        require(coordinates.all { it.isFinite() && it >= 0 })
        val prefix = listOf("/system/bin/input", "-d", displayId.toString())
        return prefix + when (kind) {
            TAP -> { require(coordinates.size == 2); listOf("tap") + coordinates.map { it.toString() } }
            SWIPE -> { require(coordinates.size == 5 && coordinates[4] in 100f..3000f); listOf("swipe") + coordinates.take(4).map { it.toString() } + coordinates[4].toInt().toString() }
            BACK -> { require(coordinates.isEmpty()); listOf("keyevent", "KEYCODE_BACK") }
            TEXT -> {
                require(coordinates.isEmpty())
                require(text.isNotEmpty() && text.length <= 500 && text.all { it in ' '..'~' && it != '%' }) {
                    "UNSUPPORTED: input text must be printable ASCII without percent escapes"
                }
                listOf("text", text.replace(" ", "%s"))
            }
            else -> throw IllegalArgumentException("UNSUPPORTED input operation")
        }
    }

    data class Task(val rootTaskId: Int, val taskId: Int, val displayId: Int, val packageName: String) {
        /** Shown on its display right now. Unknown counts as visible (fail closed: it may be the owner's). */
        var visible: Boolean = true
            internal set
    }

    /**
     * Plan 28: the task Cyclone works in on [displayId], or why it cannot. The owner wins: a visible task of the app on
     * the main screen means they opened it. Tasks only in Recents do not count, and an app may have several tasks on
     * Cyclone's own display (a compose window, a second document): all of them are Cyclone's, the top one is used.
     */
    fun ownedTask(tasks: List<Task>, packageName: String?, displayId: Int, sharedWithOwner: Boolean): Task {
        val app = tasks.filter { it.packageName == packageName }
        check(sharedWithOwner || app.none { it.displayId == 0 && it.visible }) { "FOREGROUND_REQUIRED: app moved to the human display" }
        return app.firstOrNull { it.displayId == displayId }
            ?: error("TASK_GONE: the app is no longer on its background screen")
    }

    /** The task of [packageName] to move off the main screen: the one the owner sees, else the newest in Recents. */
    fun mainTask(tasks: List<Task>, packageName: String): Task? =
        tasks.filter { it.packageName == packageName && it.displayId == 0 }.let { main -> main.firstOrNull { it.visible } ?: main.firstOrNull() }

    fun exactTask(tasks: List<Task>, taskId: Int, displayId: Int, packageName: String): Task =
        tasks.singleOrNull { it.taskId == taskId && it.displayId == displayId && it.packageName == packageName }
            ?: error("The original app page is no longer available")

    private val VISIBLE = Regex("\\bvisible=(true|false)\\b")

    /** Fail closed when an OEM changes the shell output instead of guessing task ownership. */
    fun tasks(output: String): List<Task> {
        val tasks = mutableListOf<Task>()
        var root: Int? = null
        var display: Int? = null
        for (line in output.lineSequence()) {
            Regex("(?:RootTask|Stack) id=(\\d+).*displayId=(\\d+)").find(line)?.let {
                root = it.groupValues[1].toInt(); display = it.groupValues[2].toInt()
            }
            val match = Regex("taskId=(\\d+): ([A-Za-z0-9_.]+)/(?:[^ ]+)").find(line)
            if (match != null && root != null && display != null) {
                tasks += Task(root!!, match.groupValues[1].toInt(), display!!, match.groupValues[2]).apply {
                    visible = VISIBLE.find(line)?.groupValues?.get(1) != "false"
                }
            }
        }
        return tasks
    }
}
