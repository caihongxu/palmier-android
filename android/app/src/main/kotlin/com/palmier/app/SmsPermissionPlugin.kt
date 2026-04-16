package com.palmier.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
    name = "SmsPermission",
    permissions = [
        Permission(
            alias = "sms",
            strings = [Manifest.permission.RECEIVE_SMS]
        )
    ]
)
class SmsPermissionPlugin : Plugin() {

    @PluginMethod
    fun check(call: PluginCall) {
        call.resolve(buildResult())
    }

    @PluginMethod
    fun request(call: PluginCall) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissionForAlias("sms", call, "onSmsPermissionResult")
        } else {
            call.resolve(buildResult())
        }
    }

    @PermissionCallback
    private fun onSmsPermissionResult(call: PluginCall) {
        call.resolve(buildResult())
    }

    private fun buildResult(): JSObject {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED
        return JSObject().apply { put("granted", granted) }
    }
}
