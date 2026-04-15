package com.palmier.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
    name = "LocationPermission",
    permissions = [
        Permission(
            alias = "location",
            strings = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
        ),
        Permission(
            alias = "backgroundLocation",
            strings = [Manifest.permission.ACCESS_BACKGROUND_LOCATION]
        )
    ]
)
class LocationPermissionPlugin : Plugin() {

    @PluginMethod
    fun request(call: PluginCall) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        if (!fine) {
            requestPermissionForAlias("location", call, "onLocationResult")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bg = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            if (!bg) {
                requestPermissionForAlias("backgroundLocation", call, "onBackgroundLocationResult")
            } else {
                call.resolve(buildResult())
            }
        } else {
            call.resolve(buildResult())
        }
    }

    @PermissionCallback
    private fun onLocationResult(call: PluginCall) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        if (fine && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bg = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            if (!bg) {
                requestPermissionForAlias("backgroundLocation", call, "onBackgroundLocationResult")
                return
            }
        }
        call.resolve(buildResult())
    }

    @PermissionCallback
    private fun onBackgroundLocationResult(call: PluginCall) {
        call.resolve(buildResult())
    }

    @PluginMethod
    fun check(call: PluginCall) {
        call.resolve(buildResult())
    }

    private fun buildResult(): JSObject {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val background = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        return JSObject().apply {
            put("fine", fine)
            put("background", background)
        }
    }
}
