package com.palmier.app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "FullScreenIntent")
class FullScreenIntentPlugin : Plugin() {

    @PluginMethod
    fun check(call: PluginCall) {
        call.resolve(JSObject().apply { put("granted", isGranted()) })
    }

    @PluginMethod
    fun request(call: PluginCall) {
        if (isGranted()) {
            call.resolve(JSObject().apply { put("granted", true) })
            return
        }
        pendingCall = call
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (_: Exception) {
                // Fallback: open app notification settings if the specific intent isn't available
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
        } else {
            pendingCall = null
            call.resolve(JSObject().apply { put("granted", true) })
        }
    }

    private var pendingCall: PluginCall? = null

    override fun handleOnResume() {
        super.handleOnResume()
        val call = pendingCall ?: return
        pendingCall = null
        call.resolve(JSObject().apply { put("granted", isGranted()) })
    }

    private fun isGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.canUseFullScreenIntent()
    }
}
