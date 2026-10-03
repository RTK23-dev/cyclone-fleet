package com.cyclone.mobile

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.voice.DriveAnnouncements

class CycloneNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications.orEmpty().forEach(DeviceState::storeNotification)
        AutomationRuntime.initialize(this)
        DeviceState.addLog("Notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        DeviceState.storeNotification(sbn)
        val title = sbn.notification.extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = sbn.notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        DeviceState.addLog("Notification ${sbn.packageName}: $title $text")
        AutomationRuntime.onNotification(this, sbn.packageName, title, text)
        // Driver mode: messages the owner opted into are announced (plan 32 D3); everything else is ignored there.
        runCatching { DriveAnnouncements.posted(this, sbn) }
        BridgeClient.sendNotificationEvent(sbn.packageName, title, text, sbn.key)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        DeviceState.removeNotification(sbn.key)
        BridgeClient.sendNotificationEvent(sbn.packageName, "", "", sbn.key)
    }
}
