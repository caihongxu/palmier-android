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
    name = "ContactsPermission",
    permissions = [
        Permission(
            alias = "contacts",
            strings = [Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS]
        )
    ]
)
class ContactsPermissionPlugin : Plugin() {

    @PluginMethod
    fun check(call: PluginCall) {
        call.resolve(buildResult())
    }

    @PluginMethod
    fun request(call: PluginCall) {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        if (!read || !write) {
            requestPermissionForAlias("contacts", call, "onContactsPermissionResult")
        } else {
            call.resolve(buildResult())
        }
    }

    @PermissionCallback
    private fun onContactsPermissionResult(call: PluginCall) {
        call.resolve(buildResult())
    }

    private fun buildResult(): JSObject {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        return JSObject().apply {
            put("granted", read && write)
        }
    }
}
