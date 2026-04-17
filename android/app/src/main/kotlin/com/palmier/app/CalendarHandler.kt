package com.palmier.app

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone

/**
 * Handles calendar read/create requests triggered via FCM.
 */
object CalendarHandler {

    private const val TAG = "PalmierCalendar"

    fun handleReadCalendar(context: Context, data: Map<String, String>) {
        val requestId = data["requestId"] ?: return
        val hostId = data["hostId"] ?: return

        if (!CapabilityState.isEnabled(context, "calendar")) {
            postResponse(requestId, hostId, JSONObject().put("error", "Calendar access disabled by user"))
            return
        }

        val startDate = data["startDate"]?.toLongOrNull() ?: System.currentTimeMillis()
        val endDate = data["endDate"]?.toLongOrNull() ?: (startDate + 7 * 24 * 60 * 60 * 1000L) // default: 7 days

        Thread {
            try {
                val events = readEvents(context, startDate, endDate)
                postResponse(requestId, hostId, JSONObject().put("events", events))
            } catch (e: SecurityException) {
                Log.e(TAG, "Calendar permission not granted", e)
                postResponse(requestId, hostId, JSONObject().put("error", "Calendar permission not granted"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read calendar", e)
                postResponse(requestId, hostId, JSONObject().put("error", "Failed to read calendar: ${e.message}"))
            }
        }.start()
    }

    fun handleCreateEvent(context: Context, data: Map<String, String>) {
        val requestId = data["requestId"] ?: return
        val hostId = data["hostId"] ?: return
        val title = data["title"]
        val startTime = data["startTime"]?.toLongOrNull()
        val endTime = data["endTime"]?.toLongOrNull()
        val location = data["location"]
        val description = data["description"]

        if (!CapabilityState.isEnabled(context, "calendar")) {
            postResponse(requestId, hostId, JSONObject().put("error", "Calendar access disabled by user"))
            return
        }

        if (title.isNullOrBlank() || startTime == null || endTime == null) {
            postResponse(requestId, hostId, JSONObject().put("error", "title, startTime, and endTime are required"))
            return
        }

        Thread {
            try {
                createEvent(context, title, startTime, endTime, location, description)
                postResponse(requestId, hostId, JSONObject().put("ok", true))
            } catch (e: SecurityException) {
                Log.e(TAG, "Calendar permission not granted", e)
                postResponse(requestId, hostId, JSONObject().put("error", "Calendar permission not granted"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create event", e)
                postResponse(requestId, hostId, JSONObject().put("error", "Failed to create event: ${e.message}"))
            }
        }.start()
    }

    private fun readEvents(context: Context, startMs: Long, endMs: Long): JSONArray {
        val events = JSONArray()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
        )

        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(startMs.toString(), endMs.toString())

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection, selection, selectionArgs,
            "${CalendarContract.Events.DTSTART} ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(CalendarContract.Events._ID)
            val titleIdx = cursor.getColumnIndex(CalendarContract.Events.TITLE)
            val startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
            val endIdx = cursor.getColumnIndex(CalendarContract.Events.DTEND)
            val locationIdx = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            val descIdx = cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)
            val allDayIdx = cursor.getColumnIndex(CalendarContract.Events.ALL_DAY)
            val calNameIdx = cursor.getColumnIndex(CalendarContract.Events.CALENDAR_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                events.put(JSONObject().apply {
                    put("id", cursor.getString(idIdx) ?: "")
                    put("title", cursor.getString(titleIdx) ?: "")
                    put("startTime", cursor.getLong(startIdx))
                    put("endTime", cursor.getLong(endIdx))
                    put("location", cursor.getString(locationIdx) ?: "")
                    put("description", cursor.getString(descIdx) ?: "")
                    put("allDay", cursor.getInt(allDayIdx) == 1)
                    put("calendar", cursor.getString(calNameIdx) ?: "")
                })
            }
        }

        return events
    }

    private fun createEvent(context: Context, title: String, startMs: Long, endMs: Long, location: String?, description: String?) {
        // Use the first available calendar
        val calId = getDefaultCalendarId(context)
            ?: throw IllegalStateException("No calendar account found on device")

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (!description.isNullOrBlank()) put(CalendarContract.Events.DESCRIPTION, description)
        }

        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        Log.d(TAG, "Event created: $title")
    }

    private fun getDefaultCalendarId(context: Context): Long? {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.IS_PRIMARY} = 1",
            null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        // Fallback: first calendar
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    private fun postResponse(requestId: String, hostId: String, result: JSONObject) {
        try {
            val url = URL("${MainActivity.SERVER_URL}/api/device/calendar-response")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val payload = JSONObject().apply {
                put("requestId", requestId)
                put("hostId", hostId)
                put("result", result)
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            Log.d(TAG, "Response posted, status: ${conn.responseCode}")
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post response", e)
        }
    }
}
