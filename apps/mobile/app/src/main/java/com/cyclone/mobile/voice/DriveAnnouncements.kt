package com.cyclone.mobile.voice

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * From a posted notification to a message Drive may announce (plan 32 D3). The notification listener hands every
 * notification here; only messages the owner opted into (Driver mode on, announcements on, the app allowed, the sender
 * allowed) come out, once each. Nothing is stored or logged: the message lives in memory until it is said.
 */
object DriveAnnouncements {
    private val _incoming = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Messages to announce; the Drive session collects these while Driver mode is on. */
    val incoming: SharedFlow<IncomingMessage> = _incoming

    private val seen = ArrayDeque<String>()

    fun posted(context: Context, sbn: StatusBarNotification) {
        val settings = DriverMode.load(context)
        if (!settings.enabled || !settings.announce || sbn.packageName !in settings.announceApps) return
        val message = read(context, sbn) ?: return
        if (!VoiceAnnounce.allowed(message, settings)) return
        synchronized(seen) {
            // Chat apps repost a notification when anything about it changes: one message is announced once.
            if (message.id in seen) return
            seen.addLast(message.id)
            while (seen.size > SEEN) seen.removeFirst()
        }
        _incoming.tryEmit(message)
    }

    private fun read(context: Context, sbn: StatusBarNotification): IncomingMessage? {
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.isOngoing) return null
        // An old notification reposted (a reconnect, a phone restart) is not news.
        if (System.currentTimeMillis() - sbn.postTime > FRESH_MS) return null
        val extras = n.extras ?: return null
        val last = runCatching {
            @Suppress("DEPRECATION")
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(extras.getParcelableArray(Notification.EXTRA_MESSAGES)).lastOrNull()
        }.getOrNull()
        // "Family (3 messages)" → "Family": a group is announced by its name only.
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
            .replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
        val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || conversation.isNotBlank()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val sender = if (group) conversation.ifBlank { title } else last?.senderPerson?.name?.toString()?.ifBlank { null } ?: title
        val text = last?.text?.toString() ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (sender.isBlank()) return null
        val replyable = n.actions.orEmpty().any { action -> action.remoteInputs.orEmpty().any { it.allowFreeFormInput } }
        val label = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrNull() ?: VoiceAnnounce.CHAT_APPS.firstOrNull { it.first == sbn.packageName }?.second.orEmpty()
        return IncomingMessage("${sbn.key}#${text.hashCode()}", sbn.packageName, label, sender, text, group, replyable)
    }

    private const val SEEN = 40
    private const val FRESH_MS = 2 * 60_000L
}
