package com.cyclone.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract

object CalendarMatcher {
    fun isFree(context: Context, startMillis: Long, endMillis: Long): Boolean {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return false
        val projection = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMillis.toString())
            .appendPath(endMillis.toString())
            .build()
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val begin = cursor.getLong(1)
                val end = cursor.getLong(2)
                if (begin < endMillis && end > startMillis) return false
            }
        }
        return true
    }
}
