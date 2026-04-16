package com.palmier.app

import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "NotificationListener")
class NotificationListenerPlugin : Plugin() {

    @PluginMethod
    fun check(call: PluginCall) {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        call.resolve(JSObject().apply { put("enabled", enabled) })
    }

    @PluginMethod
    fun request(call: PluginCall) {
        // Store the call so we can resolve it when the user returns from settings
        pendingCall = call
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private var pendingCall: PluginCall? = null

    override fun handleOnResume() {
        super.handleOnResume()
        val call = pendingCall ?: return
        pendingCall = null
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        call.resolve(JSObject().apply { put("enabled", enabled) })
    }
}
