package com.cyclone.mobile.setup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import com.cyclone.mobile.permissions.CyclonePermissionSetup
import com.cyclone.mobile.runtime.background.BackgroundSetup
import com.cyclone.mobile.runtime.background.BackgroundSetupActivity

/**
 * Plan 30: the Android side of the setup cards. It reads whether each setting is on and opens Android's own settings
 * screen or permission dialog for it. It never changes a setting itself.
 */
object SetupState {
    fun cards(context: Context): List<SetupCard> = SetupFlow.cards(Build.VERSION.SDK_INT, com.cyclone.mobile.voice.DriverMode.enabled(context))

    fun done(context: Context): Set<SetupCard> = cards(context).filterTo(mutableSetOf()) { isOn(context, it) }

    fun isOn(context: Context, card: SetupCard): Boolean = when (card) {
        SetupCard.PHONE_CONTROL -> CyclonePermissionSetup.phoneControlReady(context)
        SetupCard.OVER_APPS -> CyclonePermissionSetup.overlayEnabled(context)
        SetupCard.RESULTS -> CyclonePermissionSetup.resultNotificationsEnabled(context)
        SetupCard.READ_NOTIFICATIONS -> CyclonePermissionSetup.notificationAccessEnabled(context)
        SetupCard.KEEP_RUNNING -> CyclonePermissionSetup.batteryUnrestricted(context)
        SetupCard.BACKGROUND -> runCatching { BackgroundSetup.read(context).setupFailure == null }.getOrDefault(false)
        SetupCard.CALENDAR -> CyclonePermissionSetup.calendarEnabled(context) && CyclonePermissionSetup.calendarWriteEnabled(context)
        SetupCard.CONTACTS -> CyclonePermissionSetup.contactsEnabled(context)
        SetupCard.VOICE -> context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        SetupCard.DRIVER -> com.cyclone.mobile.voice.DriverMode.enabled(context) &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun shouldOpen(context: Context): Boolean = SetupFlow.shouldOpen(cards(context), done(context), SetupStore.seen(context))

    /**
     * Opens the place where the owner turns [card] on. Runtime permissions use Android's own dialog the first time;
     * after that (Android stops showing it) the app's settings page. Returns false when nothing could be opened.
     */
    fun open(context: Context, card: SetupCard): Boolean {
        val on = isOn(context, card)
        // Driver mode is Cyclone's own setting: the owner's tap on this card turns it on (Settings → Driver mode turns
        // it off); Android's microphone dialog follows when the microphone is not allowed yet.
        if (card == SetupCard.DRIVER) {
            com.cyclone.mobile.voice.DriverMode.setEnabled(context, true)
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return open(context, SetupCard.VOICE)
            return true
        }
        val runtime = runtimePermissions(card)
        if (runtime != null) {
            val activity = context as? Activity
            if (!on && activity != null && !SetupStore.asked(context, card)) {
                SetupStore.markAsked(context, card)
                ActivityCompat.requestPermissions(activity, runtime, REQUEST_CODE)
                return true
            }
            return start(context, CyclonePermissionSetup.appDetails(context))
        }
        val intent = when (card) {
            SetupCard.PHONE_CONTROL -> CyclonePermissionSetup.accessibilitySettings()
            SetupCard.OVER_APPS -> CyclonePermissionSetup.overlaySettings(context)
            SetupCard.READ_NOTIFICATIONS -> CyclonePermissionSetup.notificationAccessSettings()
            SetupCard.KEEP_RUNNING -> if (on) CyclonePermissionSetup.batteryOptimizationSettings() else CyclonePermissionSetup.batteryExemptionRequest(context)
            SetupCard.BACKGROUND -> Intent(context, BackgroundSetupActivity::class.java)
            else -> CyclonePermissionSetup.appDetails(context)
        }
        return start(context, intent)
    }

    /** The runtime permissions a card asks for through Android's own dialog, or null for a settings-screen card. */
    fun runtimePermissions(card: SetupCard): Array<String>? = when (card) {
        SetupCard.RESULTS -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else null
        SetupCard.CALENDAR -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        SetupCard.CONTACTS -> arrayOf(Manifest.permission.READ_CONTACTS)
        SetupCard.VOICE -> arrayOf(Manifest.permission.RECORD_AUDIO)
        else -> null
    }

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess || runCatching {
        context.startActivity(CyclonePermissionSetup.appDetails(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    private const val REQUEST_CODE = 330
}

/** Which cards the owner has already been shown, and which permission dialogs were already asked. Nothing else. */
object SetupStore {
    private const val PREFS = "cyclone_setup_cards"
    private const val SEEN = "seen"
    private const val ASKED = "asked"

    fun seen(context: Context): Set<String> = prefs(context).getStringSet(SEEN, emptySet()).orEmpty()

    fun markSeen(context: Context, cards: Collection<SetupCard>) {
        prefs(context).edit().putStringSet(SEEN, seen(context) + cards.map { it.id }).apply()
    }

    fun asked(context: Context, card: SetupCard): Boolean = card.id in prefs(context).getStringSet(ASKED, emptySet()).orEmpty()

    fun markAsked(context: Context, card: SetupCard) {
        val asked = prefs(context).getStringSet(ASKED, emptySet()).orEmpty()
        prefs(context).edit().putStringSet(ASKED, asked + card.id).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
