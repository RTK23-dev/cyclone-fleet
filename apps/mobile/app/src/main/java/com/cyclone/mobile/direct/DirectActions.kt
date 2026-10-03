package com.cyclone.mobile.direct

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import com.cyclone.mobile.DeviceState
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** What a direct action returns: a payload, or a stable code and a message (plan 29). */
data class DirectResult(val payload: JSONObject? = null, val code: String? = null, val message: String? = null) {
    val ok: Boolean get() = code == null

    companion object {
        const val PERMISSION = "PERMISSION_REQUIRED"
        const val INVALID = "INVALID_REQUEST"
        const val UNAVAILABLE = "CAPABILITY_UNAVAILABLE"
        const val FAILED = "ACTION_FAILED"
        fun refused(code: String, message: String) = DirectResult(code = code, message = message)
    }
}

/**
 * Plan 29 (alpha.45, direct first): things Cyclone can do for the owner without any screen, through Android's own
 * providers and the clock app's documented contract. Called only from PhoneToolExecutor. Nothing here reads or keeps
 * secrets; contact details and events go to the model only because the owner's mission asked for them.
 */
object DirectActions {
    val CALENDAR_READ = listOf(Manifest.permission.READ_CALENDAR)
    val CALENDAR_WRITE = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    val CONTACTS = listOf(Manifest.permission.READ_CONTACTS)

    private fun missing(context: Context, permissions: List<String>): List<String> =
        permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun needs(context: Context, permissions: List<String>, what: String): DirectResult? =
        missing(context, permissions).takeIf { it.isNotEmpty() }?.let {
            DirectResult.refused(DirectResult.PERMISSION, "$what: ${it.joinToString(",")}")
        }

    // ---- calendar ------------------------------------------------------------------------------------------------

    fun calendarFind(context: Context, params: JSONObject): DirectResult {
        needs(context, CALENDAR_READ, "Cyclone needs access to your calendar")?.let { return it }
        val window = when (val checked = DirectPlan.window(params.optString("from"), params.optString("to"), LocalDateTime.now())) {
            is DirectPlan.Checked.Ok -> checked.value
            is DirectPlan.Checked.Refused -> return DirectResult.refused(DirectResult.INVALID, checked.reason)
        }
        val query = params.optString("query").trim().take(60)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, DirectPlan.millis(window.first))
            android.content.ContentUris.appendId(it, DirectPlan.millis(window.second))
        }.build()
        val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
        val selection = if (query.isNotEmpty()) "${CalendarContract.Instances.TITLE} LIKE ?" else null
        val args = if (query.isNotEmpty()) arrayOf("%$query%") else null
        val events = JSONArray()
        context.contentResolver.query(uri, projection, selection, args, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
            while (cursor.moveToNext() && events.length() < MAX_EVENTS) {
                val allDay = cursor.getInt(3) == 1
                // All-day instances are stored at UTC midnight; timed ones in real time.
                val zone = if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()
                val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(cursor.getLong(1)), zone)
                val end = LocalDateTime.ofInstant(Instant.ofEpochMilli(cursor.getLong(2)), zone)
                events.put(JSONObject().put("title", cursor.getString(0).orEmpty().take(200))
                    .put("when", DirectPlan.span(start, end, allDay))
                    .put("start", start.toString()).put("end", end.toString()).put("allDay", allDay)
                    .put("location", cursor.getString(4)?.take(200) ?: JSONObject.NULL)
                    .put("calendar", cursor.getString(5)?.take(80) ?: JSONObject.NULL))
            }
        } ?: return DirectResult.refused(DirectResult.UNAVAILABLE, "The calendar could not be read")
        return DirectResult(JSONObject().put("from", window.first.toString()).put("to", window.second.toString()).put("events", events))
    }

    fun calendarAdd(context: Context, params: JSONObject): DirectResult {
        needs(context, CALENDAR_WRITE, "Cyclone needs access to your calendar")?.let { return it }
        val event = when (val checked = DirectPlan.event(
            params.optString("title"), params.optString("start"), params.optString("end").takeIf { it.isNotBlank() },
            params.optInt("durationMinutes", 0).takeIf { it > 0 }, params.optBoolean("allDay"),
            params.optString("location"), params.optString("notes"),
            if (params.has("reminderMinutes")) params.optInt("reminderMinutes", -1) else null, LocalDateTime.now(),
        )) {
            is DirectPlan.Checked.Ok -> checked.value
            is DirectPlan.Checked.Refused -> return DirectResult.refused(DirectResult.INVALID, checked.reason)
        }
        val calendar = writableCalendar(context, params.optString("calendar").trim())
            ?: return DirectResult.refused(DirectResult.UNAVAILABLE, "No calendar on this phone accepts new events; add a Google account calendar first")
        val zone = if (event.allDay) ZoneOffset.UTC else ZoneId.systemDefault()
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar.first)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DTSTART, DirectPlan.millis(event.start, zone))
            put(CalendarContract.Events.DTEND, DirectPlan.millis(event.end, zone))
            put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (event.allDay) "UTC" else ZoneId.systemDefault().id)
            event.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            event.notes?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            put(CalendarContract.Events.HAS_ALARM, if (event.reminderMinutes != null) 1 else 0)
        }
        val inserted = runCatching { context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }.getOrNull()
            ?: return DirectResult.refused(DirectResult.FAILED, "The calendar did not accept the event")
        val eventId = android.content.ContentUris.parseId(inserted)
        event.reminderMinutes?.let { minutes ->
            runCatching {
                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, minutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                })
            }
        }
        // Read it back: the event exists with this title, or the add is not claimed.
        val saved = context.contentResolver.query(inserted, arrayOf(CalendarContract.Events.TITLE), null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == event.title
        } == true
        if (!saved) return DirectResult.refused(DirectResult.FAILED, "The event could not be read back from the calendar")
        return DirectResult(JSONObject().put("added", true).put("verified", true).put("eventId", eventId)
            .put("title", event.title).put("when", DirectPlan.span(event.start, event.end, event.allDay))
            .put("calendar", calendar.second).put("reminderMinutes", event.reminderMinutes ?: JSONObject.NULL))
    }

    /** The calendar new events go to: one named by the owner, else the primary synced one, else any writable one. */
    private fun writableCalendar(context: Context, wanted: String): Pair<Long, String>? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_TYPE)
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1"
        val found = mutableListOf<Triple<Long, String, Int>>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection,
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1).orEmpty()
                val rank = when {
                    wanted.isNotEmpty() && name.equals(wanted, ignoreCase = true) -> 0
                    cursor.getInt(2) == 1 && cursor.getString(3) == "com.google" -> 1
                    cursor.getInt(2) == 1 -> 2
                    cursor.getString(3) == "com.google" -> 3
                    else -> 4
                }
                found += Triple(cursor.getLong(0), name, rank)
            }
        }
        return found.minByOrNull { it.third }?.let { it.first to it.second }
    }

    // ---- contacts ------------------------------------------------------------------------------------------------

    fun contactsFind(context: Context, params: JSONObject): DirectResult {
        needs(context, CONTACTS, "Cyclone needs access to your contacts")?.let { return it }
        val query = DirectPlan.contactQuery(params.optString("query"))
            ?: return DirectResult.refused(DirectResult.INVALID, "query needs at least 2 letters or digits")
        val people = linkedMapOf<Long, String>()
        context.contentResolver.query(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query)),
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { cursor ->
            while (cursor.moveToNext() && people.size < MAX_CONTACTS) people[cursor.getLong(0)] = cursor.getString(1).orEmpty()
        } ?: return DirectResult.refused(DirectResult.UNAVAILABLE, "The contacts could not be read")
        val found = JSONArray()
        people.forEach { (id, name) ->
            found.put(JSONObject().put("name", name.take(100))
                .put("phones", JSONArray(values(context, ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.CONTACT_ID, id)))
                .put("emails", JSONArray(values(context, ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    ContactsContract.CommonDataKinds.Email.ADDRESS, ContactsContract.CommonDataKinds.Email.CONTACT_ID, id))))
        }
        return DirectResult(JSONObject().put("query", query).put("contacts", found))
    }

    private fun values(context: Context, uri: Uri, column: String, contactColumn: String, id: Long): List<String> =
        context.contentResolver.query(uri, arrayOf(column), "$contactColumn = ?", arrayOf(id.toString()), null)?.use { cursor ->
            buildList { while (cursor.moveToNext() && size < 4) cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() && it !in this }?.let(::add) }
        }.orEmpty()

    // ---- clock ---------------------------------------------------------------------------------------------------

    /** An alarm through the clock app's contract without its screen; proven by Android's next alarm. */
    fun alarm(context: Context, params: JSONObject): DirectResult {
        val hour = params.optInt("hour", -1)
        val minute = params.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return DirectResult.refused(DirectResult.INVALID, "hour 0-23 and minute 0-59 are required")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
        label(params)?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        start(context, intent)?.let { return it }
        val alarms = context.getSystemService(AlarmManager::class.java)
        val deadline = System.currentTimeMillis() + CLOCK_WAIT_MS
        var verified = false
        while (!verified && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            verified = DirectPlan.alarmMatches(alarms?.nextAlarmClock?.triggerTime, hour, minute, LocalDateTime.now())
        }
        return DirectResult(JSONObject().put("requested", true).put("verified", verified)
            .put("time", "%02d:%02d".format(hour, minute)))
    }

    /** A timer through the clock app's contract without its screen; proven by the clock's own running-timer notification. */
    fun timer(context: Context, params: JSONObject): DirectResult {
        val seconds = params.optInt("seconds", -1)
        if (seconds !in 1..86_400) return DirectResult.refused(DirectResult.INVALID, "seconds 1-86400 is required")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        label(params)?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val clock = context.packageManager.resolveActivity(intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true), 0)?.activityInfo?.packageName
        val sent = System.currentTimeMillis()
        start(context, intent)?.let { return it }
        val deadline = sent + CLOCK_WAIT_MS
        var verified = false
        while (!verified && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            verified = clock != null && DeviceState.notificationSnapshot().any { it.packageName == clock && it.postTime >= sent - 1_000 }
        }
        return DirectResult(JSONObject().put("requested", true).put("verified", verified).put("seconds", seconds))
    }

    private fun label(params: JSONObject): String? = params.optString("label").trim().take(60).ifBlank { null }

    // ---- plan 42 (Instant): the torch, the volume and the media keys ------------------------------------------------

    /** The flashlight on or off, through Android's torch API. */
    fun flashlight(context: Context, params: JSONObject): DirectResult {
        val on = params.optBoolean("on", true)
        val cameras = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
            ?: return DirectResult.refused(DirectResult.UNAVAILABLE, "No camera service on this phone")
        return try {
            val id = cameras.cameraIdList.firstOrNull { id ->
                cameras.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return DirectResult.refused(DirectResult.UNAVAILABLE, "This phone has no flashlight")
            cameras.setTorchMode(id, on)
            DirectResult(JSONObject().put("flashlight", if (on) "on" else "off"))
        } catch (_: Exception) {
            DirectResult.refused(DirectResult.FAILED, "The flashlight could not be switched (the camera may be in use)")
        }
    }

    /** One step of the media volume up or down, with Android's own volume panel so the owner sees it. */
    fun volume(context: Context, params: JSONObject): DirectResult {
        val up = params.optString("direction", "up") != "down"
        val audio = context.getSystemService(android.media.AudioManager::class.java)
            ?: return DirectResult.refused(DirectResult.UNAVAILABLE, "No audio service on this phone")
        audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
            if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
        return DirectResult(JSONObject().put("volume", audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
            .put("max", audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)))
    }

    /** A media key (play/pause, next, previous) to whatever is playing. */
    fun media(context: Context, params: JSONObject): DirectResult {
        val code = when (params.optString("action", "play_pause")) {
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "play_pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            else -> return DirectResult.refused(DirectResult.INVALID, "action is play_pause, next or previous")
        }
        val audio = context.getSystemService(android.media.AudioManager::class.java)
            ?: return DirectResult.refused(DirectResult.UNAVAILABLE, "No audio service on this phone")
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        return DirectResult(JSONObject().put("sent", params.optString("action", "play_pause")))
    }

    private fun start(context: Context, intent: Intent): DirectResult? {
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            null
        } catch (_: android.content.ActivityNotFoundException) {
            DirectResult.refused(DirectResult.UNAVAILABLE, "No clock app on this phone accepts this request")
        } catch (_: SecurityException) {
            DirectResult.refused(DirectResult.FAILED, "The clock app refused the request")
        }
    }

    // ---- access --------------------------------------------------------------------------------------------------

    /**
     * Ask Android for [permissions] (its own dialog, over whatever the owner is doing) and wait up to [timeoutMs] for
     * the answer. True when all are granted. Never from the main thread.
     */
    fun requestAccess(context: Context, permissions: List<String>, timeoutMs: Long = ACCESS_WAIT_MS): Boolean {
        if (missing(context, permissions).isEmpty()) return true
        DirectAccessActivity.answered = false
        runCatching {
            context.startActivity(Intent(context, DirectAccessActivity::class.java)
                .putExtra(DirectAccessActivity.EXTRA_PERMISSIONS, missing(context, permissions).toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }.onFailure { return false }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (missing(context, permissions).isEmpty()) return true
            if (DirectAccessActivity.answered) return missing(context, permissions).isEmpty()
            Thread.sleep(250)
        }
        return missing(context, permissions).isEmpty()
    }

    /** The permissions a failed direct result asks for (from its message), for requestAccess. */
    fun requested(message: String?): List<String> =
        message?.substringAfterLast(": ", "")?.split(',')?.map { it.trim() }?.filter { it.startsWith("android.permission.") }.orEmpty()

    const val MAX_EVENTS = 30
    const val MAX_CONTACTS = 5
    const val CLOCK_WAIT_MS = 3_000L
    const val ACCESS_WAIT_MS = 60_000L
}

/** Shows only Android's permission dialog and closes. No layout, no content of its own. */
class DirectAccessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        answered = false
        val wanted = intent.getStringArrayExtra(EXTRA_PERMISSIONS)?.filter { it.startsWith("android.permission.") }.orEmpty()
        if (wanted.isEmpty()) { answered = true; finish(); return }
        requestPermissions(wanted.toTypedArray(), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        answered = true
        finish()
    }

    companion object {
        const val EXTRA_PERMISSIONS = "com.cyclone.mobile.direct.PERMISSIONS"
        private const val REQUEST = 4_501
        @Volatile var answered = false
    }
}
