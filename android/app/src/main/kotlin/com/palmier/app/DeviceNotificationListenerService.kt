package com.palmier.app

import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

class DeviceNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "PalmierNotifListener"
        private const val DEBOUNCE_MS = 2000L
    }

    // Debounce: track recent notifications by packageName+title
    private val recentKeys = LinkedHashMap<String, Long>(64, 0.75f, true)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Skip Palmier's own task notifications to avoid feedback loops
        if (sbn.packageName == packageName && sbn.notification.channelId == "palmier_tasks") return

        // Skip default SMS app notifications — SMS is captured separately via SmsBroadcastReceiver
        val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(this)
        if (defaultSmsPackage != null && sbn.packageName == defaultSmsPackage) return

        // Check if the user has toggled notification relaying on
        if (!CapabilityState.isEnabled(this, "notifications")) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Skip empty notifications
        if (title.isBlank() && text.isBlank()) return

        // Debounce: skip if same key was seen within DEBOUNCE_MS
        val dedupeKey = "${sbn.packageName}:$title"
        val now = System.currentTimeMillis()
        val lastSeen = recentKeys[dedupeKey]
        if (lastSeen != null && now - lastSeen < DEBOUNCE_MS) return
        recentKeys[dedupeKey] = now

        // Trim old entries
        if (recentKeys.size > 200) {
            val iter = recentKeys.iterator()
            while (recentKeys.size > 100) { iter.next(); iter.remove() }
        }

        val appName = try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        val hostId = getSharedPreferences("CapacitorStorage", MODE_PRIVATE).getString("hostId", null)
        if (hostId == null) {
            Log.w(TAG, "hostId not set, skipping notification relay")
            return
        }

        Log.d(TAG, "Relaying notification from $appName: $title")

        Thread {
            try {
                val url = URL("${MainActivity.SERVER_URL}/api/device/notifications")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val id = sbn.key ?: "${sbn.packageName}:${sbn.id}"
                val timestamp = sbn.postTime

                // Use org.json for proper escaping
                val notification = org.json.JSONObject().apply {
                    put("id", id)
                    put("packageName", sbn.packageName)
                    put("appName", appName)
                    put("title", title)
                    put("text", text)
                    put("timestamp", timestamp)
                }
                val payload = org.json.JSONObject().apply {
                    put("hostId", hostId)
                    put("notification", notification)
                }

                conn.outputStream.use { it.write(payload.toString().toByteArray()) }

                val code = conn.responseCode
                if (code != 200) {
                    Log.w(TAG, "Relay response: $code")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to relay notification", e)
            }
        }.start()
    }
}
