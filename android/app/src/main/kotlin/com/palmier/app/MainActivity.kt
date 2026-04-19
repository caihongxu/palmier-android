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
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val path = intent?.getStringExtra("deepLink") ?: return
        val plugin = bridge?.getPlugin("Device")?.instance as? DevicePlugin
        if (plugin == null) {
            // Bridge not ready yet; deliver from onPostCreate.
            pendingDeepLink = path
            return
        }
        plugin.emitDeepLink(path)
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
