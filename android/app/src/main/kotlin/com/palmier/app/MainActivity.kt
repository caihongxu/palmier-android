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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : BridgeActivity() {

    companion object {
        private const val TAG = "PalmierMain"
        const val SERVER_URL = "https://app.palmier.me"
    }

    // Deep link that arrived before the Device plugin was ready to notify JS listeners.
    private var pendingDeepLink: String? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, if (granted) "Notification permission granted" else "Notification permission denied")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(DevicePlugin::class.java)
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        handleNotificationTap(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationTap(intent)
    }

    private fun handleNotificationTap(intent: Intent?) {
        if (intent == null) return
        dismissPeerWebPush(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent) {
        val path = intent.getStringExtra("deepLink") ?: return
        val plugin = bridge?.getPlugin("Device")?.instance as? DevicePlugin
        if (plugin == null) {
            pendingDeepLink = path
            return
        }
        plugin.emitDeepLink(path)
    }

    private fun dismissPeerWebPush(intent: Intent) {
        val hostId = intent.getStringExtra("dismiss_host_id") ?: return
        val taskId = intent.getStringExtra("dismiss_task_id")
        val sessionId = intent.getStringExtra("dismiss_session_id")
        if (taskId == null && sessionId == null) return

        Thread {
            try {
                val payload = JSONObject().apply {
                    put("host_id", hostId)
                    if (taskId != null) put("task_id", taskId)
                    if (sessionId != null) put("session_id", sessionId)
                }
                val conn = URL("$SERVER_URL/api/push/dismiss").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dismiss peer web push", e)
            }
        }.start()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        val path = pendingDeepLink ?: return
        pendingDeepLink = null
        (bridge?.getPlugin("Device")?.instance as? DevicePlugin)?.emitDeepLink(path)
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
}
