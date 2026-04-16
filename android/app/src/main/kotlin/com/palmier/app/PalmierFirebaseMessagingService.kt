package com.palmier.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL

class PalmierFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "PalmierFCM"
        private const val PREFS_NAME = "CapacitorStorage"
        const val CHANNEL_ID = "palmier_tasks"
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "New FCM token: $token")
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString("fcmToken", token).apply()
        registerTokenWithServer(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        val type = data["type"] ?: return

        Log.d(TAG, "Received message type: $type")

        when (type) {
            "geolocation-request" -> handleGeolocation(data)

            "read-contacts" -> ContactsHandler.handleReadContacts(this, data)
            "create-contact" -> ContactsHandler.handleCreateContact(this, data)

            "read-calendar" -> CalendarHandler.handleReadCalendar(this, data)
            "create-calendar-event" -> CalendarHandler.handleCreateEvent(this, data)

            "confirm" -> showConfirmNotification(data)

            "permission", "input", "complete", "fail", "notification" -> showNotification(data)

            "confirm-dismiss", "permission-dismiss", "input-dismiss" -> dismissNotification(data)
        }
    }

    private fun handleGeolocation(data: Map<String, String>) {
        val requestId = data["requestId"] ?: return
        val hostId = data["hostId"] ?: return

        Log.d(TAG, "Geolocation request: $requestId")
        val intent = Intent(this, GeolocationForegroundService::class.java).apply {
            putExtra("requestId", requestId)
            putExtra("hostId", hostId)
            putExtra("serverUrl", MainActivity.SERVER_URL)
        }
        startForegroundService(intent)
    }

    private fun showConfirmNotification(data: Map<String, String>) {
        val hostId = data["host_id"] ?: return
        val requestId = data["session_id"] ?: return
        val notificationId = "confirm:$requestId".hashCode()

        ensureNotificationChannel()

        val confirmIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = "com.palmier.app.CONFIRM"
            putExtra("session_id", requestId)
            putExtra("host_id", hostId)
            putExtra("notification_id", notificationId)
        }
        val confirmPending = PendingIntent.getBroadcast(
            this, notificationId, confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val abortIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = "com.palmier.app.ABORT"
            putExtra("session_id", requestId)
            putExtra("host_id", hostId)
            putExtra("notification_id", notificationId)
        }
        val abortPending = PendingIntent.getBroadcast(
            this, notificationId + 1, abortIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            this, notificationId + 2, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = data["title"] ?: "Confirmation Required"
        val body = data["body"] ?: "A task requires confirmation to run."

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .addAction(0, "Confirm", confirmPending)
            .addAction(0, "Abort", abortPending)
            .build()

        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun showNotification(data: Map<String, String>) {
        val title = data["title"] ?: "Palmier"
        val body = data["body"] ?: return
        val taskId = data["task_id"]
        val sessionId = data["session_id"]
        val runId = data["run_id"]

        val notificationId = when {
            sessionId != null -> "session:$sessionId".hashCode()
            taskId != null -> "task:$taskId".hashCode()
            else -> body.hashCode()
        }

        ensureNotificationChannel()

        val deepLink = when {
            taskId != null && runId != null -> "/runs/$taskId/$runId"
            taskId != null -> "/runs/$taskId/latest"
            else -> "/"
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("deepLink", deepLink)
        }
        val tapPending = PendingIntent.getActivity(
            this, notificationId, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .build()

        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun dismissNotification(data: Map<String, String>) {
        val type = data["type"] ?: return
        val taskId = data["task_id"]
        val sessionId = data["session_id"]

        val notificationId = when {
            type == "confirm-dismiss" && sessionId != null -> "confirm:$sessionId".hashCode()
            type == "input-dismiss" && sessionId != null -> "session:$sessionId".hashCode()
            taskId != null -> "task:$taskId".hashCode()
            else -> return
        }

        getSystemService(NotificationManager::class.java).cancel(notificationId)
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Task Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for task events"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun registerTokenWithServer(token: String) {
        Thread {
            try {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val hostId = prefs.getString("hostId", null)

                if (hostId == null) {
                    Log.w(TAG, "hostId not set, skipping token registration")
                    return@Thread
                }

                val url = URL("${MainActivity.SERVER_URL}/api/fcm/register")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val json = """{"hostId":"$hostId","fcmToken":"$token"}"""
                conn.outputStream.use { it.write(json.toByteArray()) }

                Log.d(TAG, "Token registration response: ${conn.responseCode}")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register token", e)
            }
        }.start()
    }
}
