package com.cyclone.mobile

import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

object DeviceState {
    enum class Controller { AGENT, HUMAN }

    @Volatile private var _controller: Controller = Controller.AGENT
    val controller: Controller get() = _controller

    @Volatile var accessibilityConnected: Boolean = false
    @Volatile var bridgeConnected: Boolean = false
    @Volatile var currentPackage: String? = null
    @Volatile var currentClassName: String? = null
    @Volatile var latestNotification: StatusBarNotification? = null
    @Volatile var lastScreenshotPath: String? = null
    @Volatile var requireFreshObservation: Boolean = false

    private val uiEventMonitor = Object()
    private val uiEventGeneration = AtomicLong(0)
    @Volatile private var _lastUiEventAtMs: Long = 0L
    var lastUiEventAtMs: Long
        get() = _lastUiEventAtMs
        set(value) {
            _lastUiEventAtMs = value
            synchronized(uiEventMonitor) {
                uiEventGeneration.incrementAndGet()
                uiEventMonitor.notifyAll()
            }
        }

    private val controllerEpoch = AtomicLong(0)
    private val notifications = ConcurrentHashMap<String, StatusBarNotification>()
    val log = CopyOnWriteArrayList<String>()
    val commandAudit = CopyOnWriteArrayList<CommandAuditRecord>()

    data class CommandAuditRecord(
        val commandId: String,
        val tool: String,
        val startedAtMs: Long,
        val finishedAtMs: Long,
        val ok: Boolean,
        val beforeFingerprint: String?,
        val afterFingerprint: String?,
        val errorCode: String?,
    )

    fun controllerEpoch(): Long = controllerEpoch.get()
    fun uiGeneration(): Long = uiEventGeneration.get()

    /**
     * Waits for Android Accessibility to publish a newer UI event generation. Unlike the old
     * fingerprint polling loop this does not traverse the Accessibility tree while the UI is idle.
     * The returned generation equals [generation] on timeout.
     */
    fun awaitUiEventAfter(generation: Long, timeoutMs: Long): Long {
        if (timeoutMs <= 0L || uiEventGeneration.get() > generation) return uiEventGeneration.get()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(uiEventMonitor) {
            while (uiEventGeneration.get() <= generation) {
                val remainingNs = deadline - System.nanoTime()
                if (remainingNs <= 0L) break
                val waitMs = (remainingNs / 1_000_000L).coerceAtLeast(1L)
                uiEventMonitor.wait(waitMs)
            }
        }
        return uiEventGeneration.get()
    }

    @Synchronized
    fun setController(controller: Controller) {
        if (_controller == controller) return
        _controller = controller
        controllerEpoch.incrementAndGet()
        // Returning control invalidates every queued target. A fresh observe is mandatory.
        requireFreshObservation = controller == Controller.AGENT
        addLog("Controller changed to ${controller.name}")
    }

    fun markObserved() {
        if (_controller == Controller.AGENT) requireFreshObservation = false
    }

    fun storeNotification(sbn: StatusBarNotification) {
        latestNotification = sbn
        notifications[sbn.key] = sbn
        if (notifications.size > 100) {
            notifications.values.sortedBy { it.postTime }.take(notifications.size - 100).forEach { notifications.remove(it.key) }
        }
    }

    fun removeNotification(key: String) {
        notifications.remove(key)
        if (latestNotification?.key == key) latestNotification = notifications.values.maxByOrNull { it.postTime }
    }

    fun notification(key: String?): StatusBarNotification? =
        if (key.isNullOrBlank()) latestNotification else notifications[key]

    fun notificationSnapshot(): List<StatusBarNotification> = notifications.values.sortedByDescending { it.postTime }

    fun addAudit(record: CommandAuditRecord) {
        commandAudit.add(0, record)
        while (commandAudit.size > 250) commandAudit.removeAt(commandAudit.size - 1)
    }

    fun addLog(message: String) {
        log.add(0, "${System.currentTimeMillis()} $message")
        while (log.size > 100) log.removeAt(log.size - 1)
    }
}
