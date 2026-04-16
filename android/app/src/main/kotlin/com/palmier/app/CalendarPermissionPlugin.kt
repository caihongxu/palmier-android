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
    name = "CalendarPermission",
    permissions = [
        Permission(
            alias = "calendar",
            strings = [Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR]
        )
    ]
)
class CalendarPermissionPlugin : Plugin() {

    @PluginMethod
    fun check(call: PluginCall) {
        call.resolve(buildResult())
    }

    @PluginMethod
    fun request(call: PluginCall) {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
        if (!read || !write) {
            requestPermissionForAlias("calendar", call, "onCalendarPermissionResult")
        } else {
            call.resolve(buildResult())
        }
    }

    @PermissionCallback
    private fun onCalendarPermissionResult(call: PluginCall) {
        call.resolve(buildResult())
    }

    private fun buildResult(): JSObject {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
        return JSObject().apply {
            put("granted", read && write)
        }
    }
}
