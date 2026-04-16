package com.palmier.app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "DndAccess")
class DndAccessPlugin : Plugin() {

    @PluginMethod
    fun check(call: PluginCall) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        call.resolve(JSObject().apply { put("enabled", nm.isNotificationPolicyAccessGranted) })
    }

    @PluginMethod
    fun request(call: PluginCall) {
        pendingCall = call
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    private var pendingCall: PluginCall? = null

    override fun handleOnResume() {
        super.handleOnResume()
        val call = pendingCall ?: return
        pendingCall = null
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        call.resolve(JSObject().apply { put("enabled", nm.isNotificationPolicyAccessGranted) })
    }
}
