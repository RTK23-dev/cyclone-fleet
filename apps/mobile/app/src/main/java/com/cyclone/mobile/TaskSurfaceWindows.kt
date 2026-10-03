package com.cyclone.mobile

/** Android-independent selection policy shared by observation and live target lookup. */
object TaskSurfaceWindows {
    data class NodePath(val windowId: Int?, val children: List<Int>)

    fun parseNodePath(path: String): NodePath? {
        val parts = path.split('/')
        val explicitWindow = parts.firstOrNull()?.startsWith("w") == true
        val windowId = if (explicitWindow) parts.first().drop(1).toIntOrNull() ?: return null else null
        val rootIndex = if (explicitWindow) 1 else 0
        if (parts.getOrNull(rootIndex) != "0") return null
        val children = parts.drop(rootIndex + 1).map { it.toIntOrNull()?.takeIf { index -> index >= 0 } ?: return null }
        return NodePath(windowId, children)
    }
    data class Window(val id: Int, val type: Int, val packageName: String, val layer: Int,
        val active: Boolean = false, val focused: Boolean = false)

    fun primary(windows: List<Window>, activeRootId: Int?): Window? = ordered(windows, activeRootId).firstOrNull()

    /**
     * The application windows in the order to try them as the task's page: the active root's window, then the active or
     * focused one, then the highest layer (a dialog or bottom sheet sits above its activity). Alpha 91: the caller takes
     * the first one whose root it can actually read, so a dialog whose root is briefly unavailable no longer turns the
     * whole page into "null".
     */
    fun ordered(windows: List<Window>, activeRootId: Int?): List<Window> {
        val applications = windows.filter { it.type == 1 && it.packageName.isNotBlank() && it.packageName != "com.android.systemui" }
        val active = applications.filter { it.id == activeRootId }
        return active + applications.filter { it.id != activeRootId }
            .sortedWith(compareByDescending<Window> { it.active || it.focused }.thenByDescending { it.layer })
    }

    fun includeSibling(type: Int, packageName: String, hostPackage: String): Boolean =
        type != 4 && type != 2 && packageName.isNotBlank() && packageName != "com.android.systemui" &&
            (packageName != "com.cyclone.mobile" || hostPackage == "com.cyclone.mobile")

    fun eventBelongsToTask(type: Int?, packageName: String, hostPackage: String?): Boolean =
        type != 4 && type != 2 &&
            (packageName != "com.cyclone.mobile" || hostPackage == "com.cyclone.mobile")
}
