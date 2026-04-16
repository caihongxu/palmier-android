package com.palmier.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AlertHandler {

    private const val TAG = "PalmierAlert"
    private const val CHANNEL_ID = "palmier_alerts"

    fun handleSendAlert(context: Context, data: Map<String, String>) {
        val requestId = data["requestId"] ?: return
        val hostId = data["hostId"] ?: return
        val title = data["title"] ?: "Alert"
        val description = data["description"] ?: ""

        Thread {
            try {
                ensureChannel(context)

                val notificationId = "alert:$requestId".hashCode()

                // Full-screen intent — shows as full-screen activity on lock screen,
                // heads-up notification when unlocked
                val fullScreenIntent = Intent(context, AlertActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("title", title)
                    putExtra("description", description)
                    putExtra("notification_id", notificationId)
                }
                val fullScreenPending = PendingIntent.getActivity(
                    context, notificationId, fullScreenIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(description)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setFullScreenIntent(fullScreenPending, true)
                    .setAutoCancel(true)
                    .build()

                context.getSystemService(NotificationManager::class.java)
                    .notify(notificationId, notification)

                Log.d(TAG, "Alert sent: $title")
                postResponse(requestId, hostId, JSONObject().put("ok", true))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send alert", e)
                postResponse(requestId, hostId, JSONObject().put("error", "Failed to send alert: ${e.message}"))
            }
        }.start()
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Urgent alerts from AI agents"
            setBypassDnd(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun postResponse(requestId: String, hostId: String, result: JSONObject) {
        try {
            val url = URL("${MainActivity.SERVER_URL}/api/device/alert-response")
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
