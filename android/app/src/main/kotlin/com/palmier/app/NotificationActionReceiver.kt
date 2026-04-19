package com.palmier.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PalmierAction"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra("session_id") ?: return
        val hostId = intent.getStringExtra("host_id") ?: return
        val notificationId = intent.getIntExtra("notification_id", 0)

        val response = when (intent.action) {
            "com.palmier.app.CONFIRM" -> "confirmed"
            "com.palmier.app.ABORT" -> "aborted"
            else -> return
        }

        Log.d(TAG, "Action: $response for request $requestId")

        context.getSystemService(NotificationManager::class.java).cancel(notificationId)

        val pendingResult = goAsync()
        Thread {
            try {
                val url = URL("${MainActivity.SERVER_URL}/api/push/respond")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val json = """{"session_id":"$requestId","host_id":"$hostId","response":"$response"}"""
                conn.outputStream.use { it.write(json.toByteArray()) }

                Log.d(TAG, "Response posted, status: ${conn.responseCode}")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to post response", e)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
