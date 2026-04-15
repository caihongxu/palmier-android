package com.palmier.app

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL

class PalmierFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "PalmierFCM"
        private const val PREFS_NAME = "CapacitorStorage"
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "New FCM token: $token")
        // Save to SharedPreferences so the web layer can read it via Capacitor Preferences
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString("fcmToken", token).apply()
        registerTokenWithServer(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        if (data["type"] == "geolocation-request") {
            val requestId = data["requestId"]
            val hostId = data["hostId"]

            if (requestId != null && hostId != null) {
                Log.d(TAG, "Geolocation request: $requestId")
                val intent = Intent(this, GeolocationForegroundService::class.java).apply {
                    putExtra("requestId", requestId)
                    putExtra("hostId", hostId)
                    putExtra("serverUrl", MainActivity.SERVER_URL)
                }
                startForegroundService(intent)
            }
        }
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
