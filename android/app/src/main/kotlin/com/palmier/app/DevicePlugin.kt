package com.palmier.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.google.firebase.messaging.FirebaseMessaging

@CapacitorPlugin(
    name = "Device",
    permissions = [
        Permission(
            alias = "location",
            strings = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
        ),
        Permission(
            alias = "backgroundLocation",
            strings = [Manifest.permission.ACCESS_BACKGROUND_LOCATION]
        ),
        Permission(
            alias = "smsRead",
            strings = [Manifest.permission.RECEIVE_SMS]
        ),
        Permission(
            alias = "smsSend",
            strings = [Manifest.permission.SEND_SMS]
        ),
        Permission(
            alias = "contacts",
            strings = [Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS]
        ),
        Permission(
            alias = "calendar",
            strings = [Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR]
        ),
        Permission(
            alias = "postNotifications",
            strings = [Manifest.permission.POST_NOTIFICATIONS]
        )
    ]
)
class DevicePlugin : Plugin() {

    companion object {
        const val EVENT_DEEP_LINK = "deepLink"

        // PWA is served remotely and can ship ahead of the APK, so unknown types
        // must resolve as unsupported rather than throw.
        val SUPPORTED_TYPES: Set<String> = setOf(
            "location", "smsRead", "smsSend", "contacts", "calendar",
            "notificationListener", "dnd", "fullScreenIntent",
            "postNotifications",
        )
    }

    private var pendingSettingsCall: PluginCall? = null
    private var pendingSettingsType: String? = null

    @PluginMethod
    fun getFcmToken(call: PluginCall) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> call.resolve(JSObject().put("token", token)) }
            .addOnFailureListener { e -> call.reject("fcm_unavailable", e) }
    }

    // Filters via CATEGORY_LAUNCHER so QUERY_ALL_PACKAGES isn't needed.
    @PluginMethod
    fun getInstalledApps(call: PluginCall) {
        Thread {
            try {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val activities = pm.queryIntentActivities(intent, 0)

                // A single app can register multiple launcher activities; dedupe by package.
                val seen = mutableSetOf<String>()
                val apps = JSArray()
                for (info in activities) {
                    val packageName = info.activityInfo.packageName
                    if (!seen.add(packageName)) continue
                    if (packageName == context.packageName) continue
                    val appName = info.loadLabel(pm).toString()
                    apps.put(JSObject().put("packageName", packageName).put("appName", appName))
                }
                call.resolve(JSObject().put("apps", apps))
            } catch (e: Exception) {
                call.reject("failed to enumerate apps", e)
            }
        }.start()
    }

    // Requires the matching <queries> entry in AndroidManifest on Android 11+.
    @PluginMethod
    fun hasEmailClient(call: PluginCall) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:test@example.com"))
        val available = intent.resolveActivity(context.packageManager) != null
        call.resolve(JSObject().put("available", available).put("supported", true))
    }

    @PluginMethod
    fun setEnabledCapabilities(call: PluginCall) {
        val arr = call.getArray("capabilities") ?: return call.reject("missing capabilities")
        val set = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.toSet()
        CapabilityState.set(context, set)
        call.resolve()
    }

    @PluginMethod
    fun getSupportedPermissions(call: PluginCall) {
        val arr = com.getcapacitor.JSArray()
        SUPPORTED_TYPES.forEach { arr.put(it) }
        call.resolve(JSObject().put("types", arr))
    }

    @PluginMethod
    fun checkPermission(call: PluginCall) {
        val type = call.getString("type") ?: return call.reject("missing type")
        if (type !in SUPPORTED_TYPES) {
            call.resolve(JSObject().put("granted", false).put("supported", false))
            return
        }
        call.resolve(JSObject().put("granted", isGranted(type)).put("supported", true))
    }

    @PluginMethod
    fun requestPermission(call: PluginCall) {
        val type = call.getString("type") ?: return call.reject("missing type")
        if (type !in SUPPORTED_TYPES) {
            call.resolve(JSObject().put("granted", false).put("supported", false))
            return
        }
        when (type) {
            "location" -> requestLocation(call)
            "smsRead", "smsSend", "contacts", "calendar" -> requestRuntime(type, call)
            "postNotifications" -> requestPostNotifications(call)
            "notificationListener" -> openSettings(call, type, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            "dnd" -> openSettings(call, type, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            "fullScreenIntent" -> requestFullScreenIntent(call)
        }
    }

    private fun requestRuntime(type: String, call: PluginCall) {
        if (isGranted(type)) {
            call.resolve(grantedResult(true))
            return
        }
        call.data.put("type", type)
        requestPermissionForAlias(type, call, "onRuntimeResult")
    }

    @PermissionCallback
    private fun onRuntimeResult(call: PluginCall) {
        val type = call.getString("type") ?: return call.reject("missing type")
        call.resolve(grantedResult(isGranted(type)))
    }

    private fun requestLocation(call: PluginCall) {
        val fine = isManifestPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!fine) {
            requestPermissionForAlias("location", call, "onLocationFineResult")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isManifestPermissionGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            requestPermissionForAlias("backgroundLocation", call, "onLocationBackgroundResult")
            return
        }
        call.resolve(grantedResult(true))
    }

    @PermissionCallback
    private fun onLocationFineResult(call: PluginCall) {
        if (!isManifestPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            call.resolve(grantedResult(false))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isManifestPermissionGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            requestPermissionForAlias("backgroundLocation", call, "onLocationBackgroundResult")
            return
        }
        call.resolve(grantedResult(true))
    }

    @PermissionCallback
    private fun onLocationBackgroundResult(call: PluginCall) {
        call.resolve(grantedResult(isGranted("location")))
    }

    private fun requestPostNotifications(call: PluginCall) {
        // Pre-Tiramisu POST_NOTIFICATIONS is auto-granted; skip the runtime prompt.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            call.resolve(grantedResult(true))
            return
        }
        requestRuntime("postNotifications", call)
    }

    private fun requestFullScreenIntent(call: PluginCall) {
        if (isGranted("fullScreenIntent")) {
            call.resolve(grantedResult(true))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            call.resolve(grantedResult(true))
            return
        }
        val intent = try {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } catch (_: Exception) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
        openSettings(call, "fullScreenIntent", intent)
    }

    private fun openSettings(call: PluginCall, type: String, intent: Intent) {
        pendingSettingsCall = call
        pendingSettingsType = type
        if (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0) {
            intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    override fun handleOnResume() {
        super.handleOnResume()
        val call = pendingSettingsCall ?: return
        val type = pendingSettingsType ?: return
        pendingSettingsCall = null
        pendingSettingsType = null
        call.resolve(grantedResult(isGranted(type)))
    }

    fun emitDeepLink(path: String) {
        notifyListeners(EVENT_DEEP_LINK, JSObject().put("path", path))
    }

    private fun grantedResult(granted: Boolean): JSObject =
        JSObject().put("granted", granted).put("supported", true)

    private fun isManifestPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isGranted(type: String): Boolean = when (type) {
        "location" -> {
            val fine = isManifestPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)
            val bg = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                isManifestPermissionGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            fine && bg
        }
        "smsRead" -> isManifestPermissionGranted(Manifest.permission.RECEIVE_SMS)
        "smsSend" -> isManifestPermissionGranted(Manifest.permission.SEND_SMS)
        "contacts" -> isManifestPermissionGranted(Manifest.permission.READ_CONTACTS) && isManifestPermissionGranted(Manifest.permission.WRITE_CONTACTS)
        "calendar" -> isManifestPermissionGranted(Manifest.permission.READ_CALENDAR) && isManifestPermissionGranted(Manifest.permission.WRITE_CALENDAR)
        "postNotifications" -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true
            else isManifestPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
        }
        "notificationListener" ->
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        "dnd" ->
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
        "fullScreenIntent" -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) true
            else (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()
        }
        else -> false
    }
}
