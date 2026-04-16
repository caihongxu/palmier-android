package com.palmier.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.getcapacitor.BridgeActivity
import com.google.firebase.messaging.FirebaseMessaging
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : BridgeActivity() {

    companion object {
        private const val TAG = "PalmierMain"
        private const val PREFS_NAME = "CapacitorStorage"
        const val SERVER_URL = "https://app.palmier.me"
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, if (granted) "Notification permission granted" else "Notification permission denied")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(LocationPermissionPlugin::class.java)
        registerPlugin(NotificationListenerPlugin::class.java)
        registerPlugin(SmsPermissionPlugin::class.java)
        registerPlugin(ContactsPermissionPlugin::class.java)
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        registerFcmToken()
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val deepLink = intent?.getStringExtra("deepLink") ?: return
        bridge?.webView?.post {
            bridge?.webView?.evaluateJavascript(
                "window.location.href='$deepLink'", null
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun registerFcmToken() {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                Log.d(TAG, "FCM token: $token")
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

                // Save FCM token so the web layer can read it via Capacitor Preferences
                prefs.edit().putString("fcmToken", token).apply()

                val hostId = prefs.getString("hostId", null)
                if (hostId != null) {
                    postFcmToken(SERVER_URL, hostId, token)
                } else {
                    Log.w(TAG, "hostId not yet set, token will be registered on next app launch")
                }
            }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to get FCM token", e) }
    }

    private fun postFcmToken(serverUrl: String, hostId: String, token: String) {
        Thread {
            try {
                val url = URL("$serverUrl/api/fcm/register")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val json = """{"hostId":"$hostId","fcmToken":"$token"}"""
                conn.outputStream.use { it.write(json.toByteArray()) }

                Log.d(TAG, "FCM token registered, response: ${conn.responseCode}")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register FCM token", e)
            }
        }.start()
    }
}
