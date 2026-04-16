package com.palmier.app

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles set-alarm requests triggered via FCM.
 */
object AlarmHandler {

    private const val TAG = "PalmierAlarm"

    fun handleSetAlarm(context: Context, data: Map<String, String>) {
        val requestId = data["requestId"] ?: return
        val hostId = data["hostId"] ?: return
        val hour = data["hour"]?.toIntOrNull()
        val minutes = data["minutes"]?.toIntOrNull()
        val label = data["label"]
        val daysStr = data["days"] // comma-separated: "2,3,4,5,6"

        if (hour == null || minutes == null) {
            postResponse(requestId, hostId, JSONObject().put("error", "hour and minutes are required"))
            return
        }

        Thread {
            try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    if (!daysStr.isNullOrBlank()) {
                        val days = daysStr.split(",").mapNotNull { it.trim().toIntOrNull() }.toCollection(ArrayList())
                        if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, days)
                    }
                }
                context.startActivity(intent)
                Log.d(TAG, "Alarm set for $hour:$minutes")
                postResponse(requestId, hostId, JSONObject().put("ok", true))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set alarm", e)
                postResponse(requestId, hostId, JSONObject().put("error", "Failed to set alarm: ${e.message}"))
            }
        }.start()
    }

    private fun postResponse(requestId: String, hostId: String, result: JSONObject) {
        try {
            val url = URL("${MainActivity.SERVER_URL}/api/device/alarm-response")
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
