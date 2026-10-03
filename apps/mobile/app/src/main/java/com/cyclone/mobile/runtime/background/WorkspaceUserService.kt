package com.cyclone.mobile.runtime.background

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shizuku UserService, never an exported arbitrary-command service. */
class WorkspaceUserService(context: Context) : IWorkspaceService.Stub() {
    private val shellContext = context.createPackageContext("com.android.shell", 0)
    private val displays = shellContext.getSystemService(DisplayManager::class.java)
    private val readers = Executors.newCachedThreadPool()
    private data class Owned(val display: VirtualDisplay, val width: Int, val height: Int,
        var generation: Long = 1, var agent: Boolean = true, var packageName: String? = null, var handedOffTask: Int? = null,
        /** A second window of an app the owner also has open (plan 26): the owner's task stays on display 0. */
        var sharedWithOwner: Boolean = false)
    private val workspaces = mutableMapOf<String, Owned>()

    @Synchronized override fun create(sessionId: String, surface: Surface, width: Int, height: Int, density: Int): Bundle = result {
        require(sessionId.matches(Regex("workspace-[a-f0-9-]{36}")) && sessionId !in workspaces)
        require(width in 320..2160 && height in 320..3840 && density in 120..640 && surface.isValid)
        // OWN_CONTENT_ONLY prevents mirroring the human display if no app is present.
        check(android.os.Build.VERSION.SDK_INT >= 35) { "BACKGROUND_MODE_UNAVAILABLE: isolated focus requires Android 15 or later" }
        val flags = WorkspaceDisplayPolicy.resolveFlags { name ->
            runCatching { DisplayManager::class.java.getField(name).getInt(null) }.getOrNull()
        }
        val display = displays.createVirtualDisplay("Cyclone workspace", width, height, density, surface, flags)
            ?: error("BACKGROUND_MODE_UNAVAILABLE: virtual display creation rejected")
        if (display.display.displayId <= 0) { display.release(); error("Nonzero display required") }
        workspaces[sessionId] = Owned(display, width, height)
        describe(sessionId, workspaces.getValue(sessionId))
    }

    @Synchronized override fun launch(sessionId: String, component: String): Bundle = result {
        val owned = valid(sessionId)
        require(owned.agent)
        val packageName = component.substringBefore('/')
        val tasks = WorkspaceCommands.tasks(command(listOf("/system/bin/am", "stack", "list")))
        require(tasks.none { it.packageName == packageName && it.displayId == 0 }) {
            "FOREGROUND_REQUIRED: close this app on your main screen before opening its workspace"
        }
        command(WorkspaceDisplayPolicy.launchCommand(owned.display.display.displayId, component))
        owned.packageName = packageName
        requireTask(owned)
        describe(sessionId, owned)
    }

    @Synchronized override fun status(sessionId: String): Bundle = result {
        val owned = valid(sessionId)
        if (owned.packageName != null) requireTask(owned)
        describe(sessionId, owned)
    }

    @Synchronized override fun input(sessionId: String, generation: Long, kind: Int, coordinates: FloatArray, text: String): Bundle = result {
        val owned = valid(sessionId)
        require(owned.agent && generation == owned.generation) { "HUMAN_HAS_CONTROL: stale input authority" }
        requireTask(owned)
        coordinates.take(if (kind == WorkspaceCommands.SWIPE) 4 else 2).forEachIndexed { index, value ->
            require(value < if (index % 2 == 0) owned.width else owned.height)
        }
        // Every injected event has an explicit nonzero display ID. If the task moves or the
        // display disappears after this check, events still cannot be redirected to display 0.
        command(WorkspaceCommands.input(owned.display.display.displayId, kind, coordinates, text))
        describe(sessionId, owned).apply { putBoolean("performed", true); putBoolean("verified", false) }
    }

    @Synchronized override fun revoke(sessionId: String): Bundle = result {
        val owned = valid(sessionId)
        owned.agent = false; owned.generation++
        describe(sessionId, owned)
    }

    @Synchronized override fun resume(sessionId: String): Bundle = result {
        val owned = valid(sessionId)
        owned.handedOffTask?.let { taskId ->
            check(!owned.agent)
            val task = WorkspaceCommands.exactTask(
                WorkspaceCommands.tasks(command(listOf("/system/bin/am", "stack", "list"))),
                taskId, 0, owned.packageName.orEmpty())
            command(listOf("/system/bin/am", "display", "move-stack", task.rootTaskId.toString(),
                owned.display.display.displayId.toString()))
            WorkspaceCommands.exactTask(
                WorkspaceCommands.tasks(command(listOf("/system/bin/am", "stack", "list"))),
                taskId, owned.display.display.displayId, owned.packageName.orEmpty())
            owned.handedOffTask = null
        }
        requireTask(owned)
        owned.generation++; owned.agent = true
        describe(sessionId, owned)
    }

    @Synchronized override fun handoff(sessionId: String): Bundle = result {
        val owned = valid(sessionId)
        require(!owned.agent) { "Revoke agent authority before handoff" }
        val task = requireTask(owned)
        command(listOf("/system/bin/am", "display", "move-stack", task.rootTaskId.toString(), "0"))
        require(WorkspaceCommands.tasks(command(listOf("/system/bin/am", "stack", "list")))
            .any { it.taskId == task.taskId && it.displayId == 0 && it.packageName == task.packageName }) {
            "FOREGROUND_REQUIRED: Android did not preserve and surface the task"
        }
        owned.handedOffTask = task.taskId
        describe(sessionId, owned).apply { putBoolean("handedOff", true) }
    }

    /**
     * Planes (plan 25): move the task Cyclone is working in from the main screen onto this empty background display.
     * The top task of [packageName] on display 0 is moved as a whole (`am display move-stack`), then proven to be on
     * this display with the same task id. Cyclone gets input authority with a fresh generation.
     */
    @Synchronized override fun adopt(sessionId: String, packageName: String): Bundle = result {
        val owned = valid(sessionId)
        require(owned.packageName == null) { "This background screen already has an app" }
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "Invalid package" }
        val task = WorkspaceCommands.mainTask(WorkspaceCommands.tasks(command(listOf("/system/bin/am", "stack", "list"))), packageName)
            ?: error("FOREGROUND_REQUIRED: the app is not on the main screen")
        val displayId = owned.display.display.displayId
        command(listOf("/system/bin/am", "display", "move-stack", task.rootTaskId.toString(), displayId.toString()))
        WorkspaceCommands.exactTask(WorkspaceCommands.tasks(command(listOf("/system/bin/am", "stack", "list"))),
            task.taskId, displayId, packageName)
        owned.packageName = packageName
        owned.handedOffTask = null
        owned.generation++; owned.agent = true
        describe(sessionId, owned).apply { putInt("taskId", task.taskId) }
    }

    /** Plan 26 (A42-2): does the app have a task on the main display (visible or only in Recents)? */
    @Synchronized override fun mainTask(packageName: String): Bundle = result {
        require(packageName.matches(PACKAGE)) { "Invalid package" }
        val present = WorkspaceCommands.tasks(command(listOf("/system/bin/am", "stack", "list")))
            .any { it.packageName == packageName && it.displayId == 0 }
        Bundle().apply { putBoolean("ok", true); putBoolean("present", present) }
    }

    /**
     * Plan 26 (A42-3): open a link in the app this background screen holds, on this display. The caller checked that
     * the app itself handles the link; the scheme is allowlisted and the argument is passed as one list element.
     */
    @Synchronized override fun view(sessionId: String, uri: String): Bundle = result {
        val owned = valid(sessionId)
        require(owned.agent)
        val packageName = owned.packageName ?: error("STALE_SESSION: no app on this background screen")
        require(uri.length <= 2_000 && VIEW_URI.matches(uri)) { "Unsupported link" }
        command(listOf("/system/bin/am", "start", "-W", "--display", owned.display.display.displayId.toString(),
            "-a", "android.intent.action.VIEW", "-d", uri, "-p", packageName))
        requireTask(owned)
        describe(sessionId, owned)
    }

    /**
     * Plan 26 (A42-5): a second window of an app the owner is using, when the app supports several windows. If Android
     * moved the owner's own window instead, it is put straight back and this fails.
     */
    @Synchronized override fun launchSecond(sessionId: String, component: String): Bundle = result {
        val owned = valid(sessionId)
        require(owned.agent && owned.packageName == null)
        val packageName = component.substringBefore('/')
        val before = WorkspaceCommands.tasks(command(listOf("/system/bin/am", "stack", "list")))
            .filter { it.packageName == packageName && it.displayId == 0 }
        val displayId = owned.display.display.displayId
        command(listOf("/system/bin/am", "start", "-W", "--display", displayId.toString(), "-f", "0x18080000", "-n", component))
        val after = WorkspaceCommands.tasks(command(listOf("/system/bin/am", "stack", "list"))).filter { it.packageName == packageName }
        val moved = before.filter { owner -> after.any { it.taskId == owner.taskId && it.displayId == displayId } }
        if (moved.isNotEmpty()) {
            moved.forEach { command(listOf("/system/bin/am", "display", "move-stack", it.rootTaskId.toString(), "0")) }
            error("SECOND_WINDOW_UNSUPPORTED: the app has one window; the owner's window was put back")
        }
        after.singleOrNull { it.displayId == displayId } ?: error("SECOND_WINDOW_UNSUPPORTED: no second window appeared")
        owned.packageName = packageName
        owned.sharedWithOwner = true
        describe(sessionId, owned)
    }

    @Synchronized override fun close(sessionId: String) { workspaces.remove(sessionId)?.display?.release() }
    @Synchronized override fun destroy() {
        workspaces.keys.toList().forEach(::close)
        readers.shutdownNow()
    }

    private companion object {
        val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        val VIEW_URI = Regex("^(https?://|geo:)[^\\s'\"`\\\\]+$")
    }

    private fun valid(id: String): Owned = workspaces[id]?.also {
        check(it.display.display.displayId > 0 && it.display.display.isValid) { "DISPLAY_GONE" }
    } ?: error("STALE_SESSION")

    private fun requireTask(owned: Owned): WorkspaceCommands.Task =
        WorkspaceCommands.ownedTask(WorkspaceCommands.tasks(command(listOf("/system/bin/am", "stack", "list"))),
            owned.packageName, owned.display.display.displayId, owned.sharedWithOwner)

    private fun describe(id: String, owned: Owned) = Bundle().apply {
        putBoolean("ok", true); putString("sessionId", id); putInt("displayId", owned.display.display.displayId)
        putLong("generation", owned.generation); putBoolean("agent", owned.agent); putString("package", owned.packageName)
    }
    private fun result(block: () -> Bundle): Bundle = try { block() } catch (error: Exception) {
        Bundle().apply { putBoolean("ok", false); putString("error", error.message?.take(200) ?: "BACKGROUND_MODE_UNAVAILABLE") }
    }
    private fun command(args: List<String>): String {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = readers.submit<String> { process.inputStream.bufferedReader().use { reader ->
            val out = StringBuilder(); val buffer = CharArray(4096)
            while (true) { val n = reader.read(buffer); if (n < 0) break
                check(out.length + n <= 256_000) { "Shell status exceeded bound" }; out.append(buffer, 0, n) }
            out.toString()
        } }
        try {
            check(process.waitFor(8, TimeUnit.SECONDS)) { "Workspace operation timed out" }
            val value = output.get(1, TimeUnit.SECONDS)
            check(process.exitValue() == 0 && !value.contains("Error:") && !value.contains("Exception")) { "Android rejected workspace operation" }
            return value
        } finally { process.destroyForcibly(); output.cancel(true) }
    }
}
