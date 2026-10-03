package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import com.cyclone.mobile.*
import com.cyclone.mobile.gateway.GatewayObservationStore
import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.OverlayChromeState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Setup compatibility: never guess a secondary profile ID when Android does not expose it publicly. */
val UserHandle.identifier: Int
    get() = if (this == Process.myUserHandle()) Layer2Workspaces.currentAndroidUserId() else -1

/** Narrow root probes only. There is no model-supplied shell command or auto-root path. */
object RootProbe {
    @Volatile var status = RootStatus.UNKNOWN
        private set
    private fun run(command: String): Pair<Int?, String> {
        val process = try { ProcessBuilder("su", "-c", command).redirectErrorStream(true).start() }
        catch (_: java.io.IOException) { return -127 to "" }
        return try {
            // Drain into a bounded buffer on a worker so dumpsys cannot block on a full pipe.
            val output = StringBuilder()
            val reader = Thread {
                runCatching { process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> synchronized(output) { if (output.length < 256_000) output.appendLine(line) } }
                } }
            }.apply { isDaemon = true; start() }
            if (!process.waitFor(4, TimeUnit.SECONDS)) { process.destroyForcibly(); null to "" }
            else { reader.join(500); process.exitValue() to synchronized(output) { output.toString() } }
        } finally { process.destroy(); runCatching { process.inputStream.close() } }
    }
    fun check(): RootStatus = runCatching {
        val (exit, output) = run("id")
        RootStatusMapping.from(exit, output, exit == -127)
    }.getOrDefault(RootStatus.UNKNOWN).also { status = it }
    fun resumedTarget(): WorkspaceTarget? {
        if (status != RootStatus.ROOTED) return null
        val (exit, output) = run("/system/bin/dumpsys activity activities")
        if (exit != 0) return null
        return RootTargetParser.parse(output)
    }
}

object Layer2Workspaces {
    private const val ANDROID_UIDS_PER_USER = 100_000
    private var context: Context? = null
    private var loadFailure: String? = null
    val engine = WorkspaceEngine { entries ->
        val prefs = checkNotNull(context).getSharedPreferences("cyclone_workspaces", Context.MODE_PRIVATE)
        check(prefs.edit().putString("registry", JSONArray(entries.map(::json)).toString()).commit()) { "Workspace registry could not be saved" }
    }
    private val goals = mutableMapOf<String, String>() // Jobs are ephemeral; no model input is persisted.

    /**
     * Android application UIDs encode the owning user in groups of 100000. This avoids linking to
     * hidden UserHandle.identifier APIs. For secondary profiles we derive the same value from a
     * launcher-visible application's uid; profiles with no visible app remain unknown/fail-closed.
     */
    fun currentAndroidUserId(): Int = Process.myUid() / ANDROID_UIDS_PER_USER

    fun profileUserId(ctx: Context, user: UserHandle): Int? {
        if (user == Process.myUserHandle()) return currentAndroidUserId()
        ProfileSetupRuntime.existingUser(ctx)?.let { saved ->
            if (android.os.UserHandle.getUserHandleForUid(saved * ANDROID_UIDS_PER_USER) == user) return saved
        }
        val launcher = ctx.getSystemService(LauncherApps::class.java)
        return runCatching {
            launcher.getActivityList(null, user).firstOrNull()?.applicationInfo?.uid
                ?.let { it / ANDROID_UIDS_PER_USER }
        }.getOrNull()
    }

    fun visibleProfile(ctx: Context, androidUserId: Int): UserHandle? =
        ctx.getSystemService(UserManager::class.java).userProfiles.singleOrNull { profileUserId(ctx, it) == androidUserId }

    fun initialize(ctx: Context) = synchronized(engine.mutationLock) {
        if (context == null) {
            context = ctx.applicationContext
            runCatching {
                val raw = ctx.getSharedPreferences("cyclone_workspaces", Context.MODE_PRIVATE).getString("registry", "[]")
                val array = JSONArray(raw)
                engine.restore((0 until array.length()).map { fromJson(array.getJSONObject(it)) })
            }.onFailure { loadFailure = "Workspace registry is unreadable; input is disabled until its data is recovered" }
        }
        check(loadFailure == null) { loadFailure.orEmpty() }
    }
    fun gated(): Boolean = OverlayChromeRuntime.snapshot().state == OverlayChromeState.GATE ||
        com.cyclone.mobile.runtime.background.WorkspaceTasks.state.value?.confirmation != null
    fun json(w: Workspace) = JSONObject().put("id", w.id).put("label", w.label).put("appPackage", w.appPackage)
        .put("androidUserId", w.androidUserId).put("displayId", w.displayId).put("state", w.state.name)
    private fun fromJson(p: JSONObject) = Workspace(p.getString("id"), p.getString("label"),
        p.getString("appPackage"), p.optInt("androidUserId", 0), p.optInt("displayId", 0))
    fun status(ctx: Context): JSONObject = synchronized(engine.mutationLock) {
        initialize(ctx)
        val holder = engine.holder()
        attachPlane(
            JSONObject().put("workspaces", JSONArray(engine.snapshot().map(::json)))
                .put("holder", holder?.workspaceId ?: JSONObject.NULL)
                .put("workspaceGeneration", holder?.generation ?: JSONObject.NULL)
                .put("armed", JSONArray(engine.queue())).put("gated", gated())
                .put("root", RootProbe.status.label).put("displayId", 0),
            holder?.workspaceId,
            holder?.generation,
        )
    }
    private fun launch(w: Workspace) {
        check(!gated()) { "GATE: human review required" }
        val ctx = checkNotNull(context)
        check(w.appPackage != ctx.packageName) { "Choose a target app, not Cyclone" }
        GatewayObservationStore.clear("default-foreground")
        if (w.androidUserId == currentAndroidUserId()) {
            val intent = ctx.packageManager.getLaunchIntentForPackage(w.appPackage) ?: error("APP_NOT_FOUND: no launcher activity")
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            check(RootProbe.status == RootStatus.ROOTED) { "USER_UNVERIFIED: check root before a cross-profile switch" }
            val user = visibleProfile(ctx, w.androidUserId)
                ?: error("PROFILE_UNAVAILABLE: this profile is not visible to Cyclone")
            val launcher = ctx.getSystemService(LauncherApps::class.java)
            val activity = launcher.getActivityList(w.appPackage, user).firstOrNull()
                ?: error("APP_NOT_FOUND: install this app in the selected profile")
            launcher.startMainActivity(activity.componentName, user, null, null)
        }
    }
    fun observe(w: Workspace): WorkspaceTarget {
        val service = CycloneAccessibilityService.instance ?: error("ACCESSIBILITY_NOT_CONNECTED")
        val packageName = service.observe().packageName ?: error("TARGET_UNVERIFIED: no foreground package")
        val ownUser = currentAndroidUserId()
        if (w.androidUserId != ownUser) {
            val target = RootProbe.resumedTarget() ?: error("USER_UNVERIFIED: Android profile identity unavailable")
            check(target.appPackage == packageName) { "TARGET_MISMATCH: observation and activity disagree" }
            return target
        }
        // When root is available also prove the user, preventing same-package profile confusion.
        if (RootProbe.status == RootStatus.ROOTED) {
            val target = RootProbe.resumedTarget() ?: error("USER_UNVERIFIED: no unique resumed activity")
            check(target.appPackage == packageName) { "TARGET_MISMATCH: observation and activity disagree" }
            return target
        }
        val ctx = checkNotNull(context)
        val profiles = ctx.getSystemService(UserManager::class.java).userProfiles
        val launcher = ctx.getSystemService(LauncherApps::class.java)
        check(profiles.none { user ->
            val profileId = profileUserId(ctx, user)
            profileId != ownUser && launcher.getActivityList(w.appPackage, user).isNotEmpty()
        }) { "USER_UNVERIFIED: the same package exists in multiple profiles; check root first" }
        return WorkspaceTarget(packageName, ownUser)
    }
    fun switch(ctx: Context, id: String): JSONObject = synchronized(engine.mutationLock) {
        initialize(ctx)
        val lease = engine.switch(id, ::gated, ::launch) observeTarget@{ target ->
            var seen: WorkspaceTarget? = null
            repeat(10) {
                if (gated()) error("GATE: review required")
                seen = runCatching { observe(target) }.getOrNull()
                if (seen == WorkspaceTarget(target.appPackage, target.androidUserId)) return@observeTarget seen!!
                Thread.sleep(200)
            }
            seen ?: error("TARGET_UNVERIFIED: could not bind observation to the selected profile")
        }
        // Old frame references are invalid; the agent must obtain a new Page Card after switch.
        GatewayObservationStore.clear("default-foreground")
        attachPlane(
            JSONObject().put("workspaceId", lease.workspaceId).put("workspaceGeneration", lease.generation)
                .put("sessionId", "default-foreground").put("displayId", 0).put("verified", true)
                .put("next", "phone.observe; include workspaceId and workspaceGeneration on every mutation"),
            lease.workspaceId,
            lease.generation,
        )
    }
    private fun attachPlane(payload: JSONObject, workspaceId: String? = null, workspaceGeneration: Long? = null): JSONObject {
        val identity = JSONObject().put("sessionId", "default-foreground").put("displayId", 0)
        if (workspaceId != null) {
            identity.put("workspaceId", workspaceId)
            if (workspaceGeneration != null) identity.put("workspaceGeneration", workspaceGeneration)
        }
        return SessionContract.attach(payload, SessionContract.classify(identity))
    }

    fun requireMutation(ctx: Context, request: PhoneToolRequest) {
        initialize(ctx)
        val id = request.params.optString("workspaceId").takeIf { it.isNotBlank() }
        if (id == null) {
            engine.releaseForForegroundTask()
            return
        }
        engine.requireMutation(id, request.params.optLong("workspaceGeneration", -1), ::gated, ::observe)
    }
    fun command(ctx: Context, request: PhoneToolRequest): PhoneToolResult = synchronized(engine.mutationLock) {
        val now = System.currentTimeMillis()
        try {
            initialize(ctx)
            val p = request.params
            val payload = when (request.tool) {
                "workspace.list" -> status(ctx)
                "workspace.register" -> { check(!gated()) { "GATE: review required" }; engine.register(fromJson(p)); status(ctx) }
                "workspace.switch", "phone.workspace_switch" -> switch(ctx, p.getString("id"))
                "workspace.close_task" -> {
                    val id = p.getString("workspaceId")
                    engine.closeTask(id, p.getLong("workspaceGeneration"))
                    goals.remove(id)
                    status(ctx)
                }
                "workspace.pause" -> { engine.pause(); status(ctx) }
                "workspace.release" -> { check(!gated()) { "GATE: resolve review before clearing selection" }; engine.clearSelection(); goals.clear(); status(ctx) }
                "workspace.arm" -> { check(!gated()) { "GATE: review required" }; val id = p.getString("id"); engine.arm(id); goals[id] = p.optString("goal").take(500); status(ctx) }
                "workspace.next" -> {
                    check(!gated()) { engine.pause(); "GATE: queue paused" }
                    val id = engine.queue().firstOrNull() ?: error("QUEUE_EMPTY: arm a workspace job first")
                    engine.disarm(id)
                    switch(ctx, id).also { payload ->
                        engine.arm(id)
                        payload.put("goal", goals[id].orEmpty())
                        val workspace = engine.snapshot().firstOrNull { it.id == id }
                        val generation = payload.optLong("workspaceGeneration", -1L)
                        if (workspace != null && generation >= 0L) {
                            com.cyclone.mobile.runtime.background.WorkspaceTasks.observeLayer2Slice(
                                workspace, generation, goals[id].orEmpty(),
                            )
                        }
                    }
                }
                else -> error("Unknown workspace operation")
            }
            PhoneToolResult(request.commandId, request.tool, true, now, System.currentTimeMillis(), payload = payload)
        } catch (error: Exception) {
            PhoneToolResult(request.commandId, request.tool, false, now, System.currentTimeMillis(),
                error = PhoneToolError(PhoneToolErrorCode.CAPABILITY_UNAVAILABLE, error.message?.take(240) ?: "Workspace operation failed"))
        }
    }
}
