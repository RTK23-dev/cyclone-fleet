package com.cyclone.mobile.codes

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SubscriptionManager

/**
 * Plan 49 on Android: Settings → Codes, this phone's numbers and the SMS database read for one code step.
 *
 * - **Texts:** read from the SMS database for the code step's window only, in memory, never stored or logged. Cyclone
 *   targets Android 15 (API 35), so plain code texts are readable at once; see plan 49 §1 before raising `targetSdk`.
 * - **Numbers:** each SIM's own number (when the carrier sets it) plus the numbers the owner confirmed. They stay on
 *   the phone.
 */
object AndroidCodes {
    private const val PREFS = "cyclone_codes"
    private const val ENABLED = "enabled"
    private const val NUMBERS = "numbers"
    const val MAX_NUMBERS = 4

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, on).apply()
    }

    fun canReadTexts(context: Context): Boolean = granted(context, Manifest.permission.READ_SMS)

    /** Cyclone may fill codes from this phone's texts: the owner's switch and Android's permission. */
    fun ready(context: Context): Boolean = enabled(context) && canReadTexts(context)

    /** The numbers the owner confirmed or typed. */
    fun confirmed(context: Context): List<String> =
        prefs(context).getString(NUMBERS, "").orEmpty().split('\n').map { it.trim() }.filter { it.isNotBlank() }

    fun setConfirmed(context: Context, numbers: List<String>) {
        val clean = numbers.map { it.trim() }.filter { AutoCodePolicy.digits(it).length in 6..15 }.distinctBy { AutoCodePolicy.digits(it) }
        prefs(context).edit().putString(NUMBERS, clean.take(MAX_NUMBERS).joinToString("\n")).apply()
    }

    /** Each active SIM's number as Android knows it (often blank), with its subscription id. */
    fun simNumbers(context: Context): List<Pair<Int, String>> {
        if (!granted(context, Manifest.permission.READ_PHONE_NUMBERS)) return emptyList()
        return runCatching {
            val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            @Suppress("MissingPermission")
            val subs = manager.activeSubscriptionInfoList.orEmpty()
            subs.mapNotNull { info ->
                @Suppress("MissingPermission")
                val number = runCatching { manager.getPhoneNumber(info.subscriptionId) }.getOrNull().orEmpty()
                number.takeIf { AutoCodePolicy.digits(it).length >= 6 }?.let { info.subscriptionId to it }
            }
        }.getOrDefault(emptyList())
    }

    /** All of this phone's numbers: the SIMs' and the confirmed ones. */
    fun numbers(context: Context): List<String> =
        (simNumbers(context).map { it.second } + confirmed(context)).distinctBy { AutoCodePolicy.digits(it).takeLast(9) }

    /** The SIM a number belongs to, or -1 when unknown (then texts to either SIM count). */
    fun subscriptionOf(context: Context, number: String?): Int =
        number?.let { n -> simNumbers(context).firstOrNull { AutoCodePolicy.same(it.second, n) }?.first } ?: -1

    /** The SMS database, read for one window. Bodies stay in memory and are dropped by the caller at once. */
    fun inbox(context: Context): SmsInbox = SmsInbox { since ->
        if (!canReadTexts(context)) return@SmsInbox emptyList()
        val out = ArrayList<SmsLine>()
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.SUBSCRIPTION_ID),
                "${Telephony.Sms.DATE} >= ?",
                arrayOf(since.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT 20",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    out += SmsLine(cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty(), cursor.getLong(2),
                        if (cursor.isNull(3)) -1 else cursor.getInt(3))
                }
            }
        }
        out
    }

    /** `numbers.list` for the PC's Numbers page: the owner's switch, whether texts can be read, and this phone's numbers. */
    fun report(context: Context): org.json.JSONObject =
        NumbersReport.build(enabled(context), canReadTexts(context), simNumbers(context), confirmed(context))

    private fun granted(context: Context, permission: String) =
        runCatching { context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
