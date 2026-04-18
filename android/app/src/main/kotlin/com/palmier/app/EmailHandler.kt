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

object EmailHandler {

    private const val TAG = "PalmierEmail"
    private const val CHANNEL_ID = "palmier_email"

    fun handleSendEmail(context: Context, data: Map<String, String>) {
        val requestId = data["requestId"] ?: return
        val hostId = data["hostId"] ?: return
        val to = data["to"] ?: ""
        val subject = data["subject"] ?: ""
        val body = data["body"] ?: ""
        val cc = data["cc"] ?: ""
        val bcc = data["bcc"] ?: ""

        Thread {
            try {
                val notificationId = "email:$requestId".hashCode()

                val emailIntent = Intent(context, EmailActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("to", to)
                    putExtra("subject", subject)
                    putExtra("body", body)
                    putExtra("cc", cc)
                    putExtra("bcc", bcc)
                    putExtra("notification_id", notificationId)
                }

                if (MainActivity.isInForeground) {
                    // App is foregrounded — launch the email composer directly,
                    // skipping the notification since we already have the user's attention.
                    context.startActivity(emailIntent)
                    Log.d(TAG, "Email composer launched directly (foreground) for: $to")
                } else {
                    ensureChannel(context)

                    val fullScreenPending = PendingIntent.getActivity(
                        context, notificationId, emailIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_email)
                        .setContentTitle("Sending email")
                        .setContentText("To: $to")
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setCategory(NotificationCompat.CATEGORY_EMAIL)
                        .setFullScreenIntent(fullScreenPending, true)
                        .setAutoCancel(true)
                        .build()

                    context.getSystemService(NotificationManager::class.java)
                        .notify(notificationId, notification)

                    Log.d(TAG, "Email notification posted for: $to")
                }

                postResponse(requestId, hostId, JSONObject().put("ok", true))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch email", e)
                postResponse(requestId, hostId, JSONObject().put("error", "Failed to launch email: ${e.message}"))
            }
        }.start()
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pending Emails",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Pending emails to send from AI agents"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun postResponse(requestId: String, hostId: String, result: JSONObject) {
        try {
            val url = URL("${MainActivity.SERVER_URL}/api/device/email-response")
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
