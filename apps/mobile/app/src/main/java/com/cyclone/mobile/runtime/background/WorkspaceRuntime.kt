package com.cyclone.mobile.runtime.background

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.UiSnapshot
import com.cyclone.mobile.ai.vision.live.FrameSourceType
import com.cyclone.mobile.ai.vision.live.LiveVisionRuntime
import com.cyclone.mobile.runtime.session.ExecutionContext
import com.cyclone.mobile.runtime.session.ExecutionSession
import com.cyclone.mobile.runtime.session.InputOwner
import com.cyclone.mobile.gesture.GestureBounds
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object WorkspaceRuntime {
    private val lock = Any()
    private var appContext: Context? = null
    @Volatile private var backend: IWorkspaceService? = null
    private data class Entry(val session: ExecutionSession, val lifecycle: WorkspaceLifecycle,
        val reader: ImageReader, val thread: HandlerThread, var remoteGeneration: Long, @Volatile var sourceRevision: Long, val consent: WorkspaceConsent = WorkspaceConsent())
    private val entries = mutableMapOf<String, Entry>()

    fun connect(context: Context) {
        if (backend?.asBinder()?.isBinderAlive == true) return
        check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            "BACKGROUND_MODE_UNAVAILABLE: enable and authorize Shizuku first"
        }
        val ready = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                backend = IWorkspaceService.Stub.asInterface(service)
                service?.linkToDeath({ backend = null; invalidateAll() }, 0)
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { backend = null; invalidateAll() }
        }
        Shizuku.bindUserService(Shizuku.UserServiceArgs(ComponentName(context, WorkspaceUserService::class.java))
            .daemon(false).processNameSuffix("workspace").version(2), connection)
        check(ready.await(8, TimeUnit.SECONDS) && backend != null) { "BACKGROUND_MODE_UNAVAILABLE: workspace service did not connect" }
    }

    fun create(context: Context, packageName: String): ExecutionSession = synchronized(lock) {
        appContext = context.applicationContext
        connect(context)
        require(packageName != context.packageName && packageName != DeviceState.currentPackage) { "Choose an app that is not on your main screen" }
        // Plan 26 (A42-2): an app that is only in Recents keeps its task and state: adopt it instead of refusing.
        if (mainTaskPresent(packageName)) return@synchronized open(packageName) { id -> checked(backend!!.adopt(id, packageName)) }
        val component = context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToString()
            ?: error("No launchable app for that package")
        open(packageName) { id -> checked(backend!!.launch(id, component)) }
    }

    /** Plan 26: where the app is now: on the owner's screen, only in Recents, or nowhere. */
    fun holder(context: Context, packageName: String): com.cyclone.mobile.runtime.plane.TargetHolder = synchronized(lock) {
        if (packageName == DeviceState.currentPackage || packageName == BackgroundSetup.foregroundPackage()) {
            return@synchronized com.cyclone.mobile.runtime.plane.TargetHolder.OWNER
        }
        runCatching { connect(context) }
        if (runCatching { mainTaskPresent(packageName) }.getOrDefault(false)) com.cyclone.mobile.runtime.plane.TargetHolder.RECENTS
        else com.cyclone.mobile.runtime.plane.TargetHolder.NOBODY
    }

    private fun mainTaskPresent(packageName: String): Boolean =
        backend?.mainTask(packageName)?.let { it.getBoolean("ok") && it.getBoolean("present") } == true

    /** Plan 26 (A42-5): a second window of an app the owner is using; fails (and restores) for single-window apps. */
    fun openSecond(context: Context, packageName: String): ExecutionSession = synchronized(lock) {
        appContext = context.applicationContext
        connect(context)
        val component = context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToString()
            ?: error("No launchable app for that package")
        open(packageName) { id -> checked(backend!!.launchSecond(id, component)) }
    }

    /** Plan 26 (A42-3): open a link in the app this background screen holds. */
    fun view(sessionId: String, uri: String) = synchronized(lock) {
        entries[sessionId] ?: error("STALE_SESSION")
        checked(backend?.view(sessionId, uri) ?: error("BACKEND_DISCONNECTED"))
        com.cyclone.mobile.gateway.GatewayObservationStore.clear(sessionId)
    }

    /**
     * Planes (plan 25): move the app Cyclone is working in from the main screen into a new background screen, keeping
     * its task (and so its page and state). The main screen shows whatever was underneath.
     */
    fun adopt(context: Context, packageName: String): ExecutionSession = synchronized(lock) {
        appContext = context.applicationContext
        connect(context)
        require(packageName != context.packageName) { "Cyclone itself cannot move to the background" }
        open(packageName) { id -> checked(backend!!.adopt(id, packageName)) }
    }

    /** The app a background session holds; null when unknown. */
    fun packageOf(sessionId: String): String? = synchronized(lock) { entries[sessionId]?.session?.targetPackage }

    /** True after [handoff]: the task is on the main screen and this session waits to take it back. */
    fun handedOff(sessionId: String): Boolean = synchronized(lock) {
        entries[sessionId]?.lifecycle?.state == WorkspaceState.WAITING_FOR_CONFIRMATION
    }

    /** Health facts for the plane watchdog: service alive, display and task present, frames fresh. */
    fun probe(sessionId: String): Triple<Boolean, Boolean, Boolean> = synchronized(lock) {
        val service = backend?.asBinder()?.isBinderAlive == true
        val present = service && entries[sessionId] != null && runCatching { backend!!.status(sessionId).getBoolean("ok") }.getOrDefault(false)
        Triple(service, present, present && LiveVisionRuntime.healthy(sessionId))
    }

    private fun open(packageName: String, attach: (String) -> Bundle): ExecutionSession {
        val id = "workspace-${UUID.randomUUID()}"
        // Plan 26 (A42-3): the background screen has the phone's own shape and density (scaled to at most 1080 px
        // wide), so apps pick the same layout as on the phone and learned maps keep matching.
        val (width, height, density) = screenShape()
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val thread = HandlerThread("cyclone-workspace-frames").apply { start() }
        try {
            val created = checked(backend!!.create(id, reader.surface, width, height, density))
            val displayId = created.getInt("displayId", -1)
            require(displayId > 0)
            val launched = attach(id)
            val session = LiveVisionRuntime.sessions.registerOwned(id, displayId, packageName)
            val revision = LiveVisionRuntime.startSource(id, displayId, FrameSourceType.VIRTUAL_DISPLAY_SURFACE)
            val lifecycle = WorkspaceLifecycle(id, displayId).apply { transition(WorkspaceState.BACKGROUND_OK) }
            val entry = Entry(session, lifecycle, reader, thread, launched.getLong("generation"), revision)
            entries[id] = entry
            reader.setOnImageAvailableListener({ source ->
                runCatching {
                    source.acquireLatestImage()?.use { image ->
                        val plane = image.planes[0]
                        val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                        val bitmap = try {
                            padded.copyPixelsFromBuffer(plane.buffer)
                            Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                        } catch (error: Throwable) { padded.recycle(); throw error }
                        if (bitmap !== padded) padded.recycle()
                        LiveVisionRuntime.publish(id, displayId, FrameSourceType.VIRTUAL_DISPLAY_SURFACE, entry.sourceRevision,
                            image.timestamp / 1_000_000, bitmap)
                    }
                }
            }, Handler(thread.looper))
            return session
        } catch (error: Exception) {
            runCatching { backend?.close(id) }; reader.close(); thread.quitSafely()
            entries.remove(id)
            if (LiveVisionRuntime.sessions.snapshot().any { it.sessionId == id }) {
                LiveVisionRuntime.stopSource(id); LiveVisionRuntime.sessions.remove(id)
            }
            throw error
        }
    }

    private fun screenShape(): Triple<Int, Int, Int> {
        val metrics = android.util.DisplayMetrics()
        runCatching {
            @Suppress("DEPRECATION")
            appContext?.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
        }
        return WorkspaceShape.of(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    fun requireScope(scope: ExecutionContext): ExecutionSession = synchronized(lock) {
        val entry = entries[scope.sessionId] ?: error("STALE_SESSION: workspace is unavailable")
        check(entry.session.displayId == scope.displayId && scope.displayId > 0)
        checked(backend?.status(scope.sessionId) ?: error("BACKEND_DISCONNECTED"))
        LiveVisionRuntime.sessions.requireSessionDisplay(scope.sessionId, scope.displayId)
    }

    fun observe(scope: ExecutionContext): UiSnapshot = synchronized(lock) {
        val session = requireScope(scope)
        val service = CycloneAccessibilityService.instance ?: error("ACCESSIBILITY_NOT_CONNECTED")
        // The display holds only what this task opened; another app on top (a permission dialog, a sign-in page) is
        // shown as it is. Approvals still apply to every tap there.
        service.observeDisplay(session.displayId, session.targetPackage.orEmpty())
    }

    fun requestConfirmation(sessionId: String, action: String, nodeId: String, fingerprint: String, kind: String) = synchronized(lock) {
        val entry = entries.getValue(sessionId)
        val confirmation = WorkspaceConfirmation(action = action, nodeId = nodeId, fingerprint = fingerprint, kind = kind)
        entry.consent.request(confirmation)
        WorkspaceTasks.state.value?.takeIf { it.sessionId == sessionId }?.let { task ->
            WorkspaceTasks.update(task.taskId) { it.copy(confirmation = confirmation) }
        }
    }
    fun approveConfirmation(sessionId: String, token: String) = synchronized(lock) {
        val entry = entries.getValue(sessionId)
        check(entry.lifecycle.owner == InputOwner.HUMAN)
        check(entry.consent.approve(token, SystemClock.elapsedRealtime())) { "Confirmation expired" }
    }
    fun consumeConfirmation(sessionId: String, action: String, nodeId: String, fingerprint: String, kind: String): Boolean = synchronized(lock) {
        entries.getValue(sessionId).consent.consume(action, nodeId, fingerprint, kind, SystemClock.elapsedRealtime())
    }

    fun generation(sessionId: String): Long = synchronized(lock) { entries.getValue(sessionId).lifecycle.generation }
    fun ownsInput(sessionId: String): Boolean = synchronized(lock) {
        entries[sessionId]?.lifecycle?.let { it.owner == InputOwner.CYCLONE && it.state == WorkspaceState.BACKGROUND_OK } == true && backend != null
    }

    fun input(scope: ExecutionContext, generation: Long, kind: Int, coordinates: FloatArray = floatArrayOf(), text: String = ""): Bundle = synchronized(lock) {
        authorizeTouchLocked(scope, generation)
        try { checked(backend!!.input(scope.sessionId, entries.getValue(scope.sessionId).remoteGeneration, kind, coordinates, text)) }
        finally { LiveVisionRuntime.mutationFinished(scope.sessionId) }
    }

    /**
     * Prove workspace input authority without injecting `/system/bin/input`.
     * Touch tools then use [com.cyclone.mobile.HumanGestureDispatch] with [android.accessibilityservice.GestureDescription.Builder.setDisplayId].
     */
    fun authorizeTouch(scope: ExecutionContext, generation: Long): GestureBounds = synchronized(lock) {
        authorizeTouchLocked(scope, generation)
        val entry = entries.getValue(scope.sessionId)
        GestureBounds(0f, 0f, entry.reader.width.toFloat(), entry.reader.height.toFloat())
    }

    private fun authorizeTouchLocked(scope: ExecutionContext, generation: Long) {
        val entry = entries[scope.sessionId] ?: error("STALE_SESSION")
        requireScope(scope)
        entry.lifecycle.requireMutation(WorkspaceLease(scope.sessionId, scope.displayId, generation), true, backend != null)
        val keyguard = appContext?.getSystemService(android.app.KeyguardManager::class.java)
        // Plan 28: a locked phone refuses the action but keeps the background screen as it is. Pausing here left the
        // session paused after unlock with nothing to resume it, so every later action failed. The mission waits for
        // the unlock before its next step (MindDevice.blocker).
        check(keyguard?.isDeviceLocked == false) { "SCREEN_LOCKED: unlock and resume the task" }
        // Plan 26 (A42-3): a still page sends no new frames. The accessibility fingerprint check before every action
        // already proves the page is current, so a frame that exists is enough; none at all is still a stall.
        check(LiveVisionRuntime.healthy(scope.sessionId) || LiveVisionRuntime.hasFrame(scope.sessionId)) {
            "FRAME_STREAM_STALLED: no workspace vision"
        }
        val remote = checked(backend!!.status(scope.sessionId))
        check(remote.getBoolean("agent")) { "HUMAN_HAS_CONTROL: stale input authority" }
    }

    fun pause(sessionId: String, state: WorkspaceState = WorkspaceState.PAUSED) = synchronized(lock) {
        val entry = entries.getValue(sessionId)
        // Invalidate locally before waiting for the remote in-flight operation to finish.
        entry.lifecycle.transition(state)
        LiveVisionRuntime.sessions.setOwner(sessionId, InputOwner.HUMAN)
        com.cyclone.mobile.gateway.GatewayObservationStore.clear(sessionId)
        entry.remoteGeneration = checked(backend?.revoke(sessionId) ?: error("BACKEND_DISCONNECTED")).getLong("generation")
    }

    fun resume(sessionId: String) = synchronized(lock) {
        val entry = entries.getValue(sessionId)
        check(entry.lifecycle.state in setOf(WorkspaceState.PAUSED, WorkspaceState.WAITING_FOR_CONFIRMATION,
            WorkspaceState.BACKGROUND_NEEDS_HANDOFF))
        entry.remoteGeneration = checked(backend?.resume(sessionId) ?: error("BACKEND_DISCONNECTED")).getLong("generation")
        entry.sourceRevision = LiveVisionRuntime.startSource(sessionId, entry.session.displayId,
            FrameSourceType.VIRTUAL_DISPLAY_SURFACE)
        entry.lifecycle.transition(WorkspaceState.BACKGROUND_OK)
        LiveVisionRuntime.sessions.setOwner(sessionId, InputOwner.CYCLONE)
        com.cyclone.mobile.gateway.GatewayObservationStore.clear(sessionId)
    }

    /**
     * Plan 28: a background screen Cyclone still holds but whose input was paused (not handed to the owner) gets its
     * input back. False when there is nothing to take back or the screen is the owner's.
     */
    fun reclaim(sessionId: String): Boolean = synchronized(lock) {
        val entry = entries[sessionId] ?: return@synchronized false
        if (entry.lifecycle.state !in setOf(WorkspaceState.PAUSED, WorkspaceState.BACKGROUND_NEEDS_HANDOFF)) return@synchronized false
        runCatching { resume(sessionId) }.isSuccess
    }

    fun handoff(sessionId: String) = synchronized(lock) {
        entries.getValue(sessionId).consent.clear()
        pause(sessionId, WorkspaceState.WAITING_FOR_CONFIRMATION)
        checked(backend?.handoff(sessionId) ?: error("BACKEND_DISCONNECTED"))
        LiveVisionRuntime.stopSource(sessionId)
        // Session keeps its nonzero identity and HUMAN owner. It never becomes default-foreground.
    }

    fun close(sessionId: String, state: WorkspaceState = WorkspaceState.CANCELLED) = synchronized(lock) {
        val entry = entries.remove(sessionId) ?: return@synchronized
        runCatching { entry.lifecycle.transition(state) }
        LiveVisionRuntime.sessions.setOwner(sessionId, InputOwner.HUMAN)
        runCatching { backend?.revoke(sessionId) }
        runCatching { backend?.close(sessionId) }
        LiveVisionRuntime.stopSource(sessionId)
        com.cyclone.mobile.gateway.GatewayObservationStore.clear(sessionId)
        entry.reader.close(); entry.thread.quitSafely()
        LiveVisionRuntime.sessions.remove(sessionId)
    }

    fun invalidateAll() = synchronized(lock) {
        entries.keys.toList().forEach { close(it, WorkspaceState.FAILED) }
    }
    private fun checked(value: Bundle): Bundle {
        check(value.getBoolean("ok")) { value.getString("error") ?: "BACKGROUND_MODE_UNAVAILABLE" }
        return value
    }
}
