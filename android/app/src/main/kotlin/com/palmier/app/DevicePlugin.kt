package com.palmier.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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

        // PWA may ship ahead of the APK; capabilities outside this list resolve as
        // unsupported instead of throwing.
        val KNOWN_CAPABILITIES: List<String> = listOf(
            "sms-read", "sms-send", "send-email",
            "notifications", "contacts", "calendar",
            "location", "dnd", "alarm",
        )
    }

    private var pendingCapabilityCall: PluginCall? = null
    private var pendingCapabilityName: String? = null
    private var pendingCapabilityPermType: String? = null

    @PluginMethod
    fun getFcmToken(call: PluginCall) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> call.resolve(JSObject().put("token", token)) }
            .addOnFailureListener { e -> call.reject("fcm_unavailable", e) }
    }

    // Requires QUERY_ALL_PACKAGES to see all non-system apps on Android 11+.
    // Hides pure OS system packages; updated system apps (pre-installed but
    // user-facing, e.g. Messages, Chrome) carry FLAG_UPDATED_SYSTEM_APP and stay.
    @PluginMethod
    fun getInstalledApps(call: PluginCall) {
        Thread {
            try {
                val pm = context.packageManager
                val apps = JSArray()
                for (info in pm.getInstalledApplications(0)) {
                    val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isUpdatedSystem = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    if (isSystem && !isUpdatedSystem) continue
                    if (info.packageName == context.packageName) continue
                    val appName = info.loadLabel(pm).toString()
                    apps.put(JSObject().put("packageName", info.packageName).put("appName", appName))
                }
                call.resolve(JSObject().put("apps", apps))
            } catch (e: Exception) {
                call.reject("failed to enumerate apps", e)
            }
        }.start()
    }

    @PluginMethod
    fun getCapabilityStatus(call: PluginCall) {
        val enabledSet = CapabilityState.get(context)
        val arr = JSArray()
        for (cap in KNOWN_CAPABILITIES) {
            arr.put(
                JSObject()
                    .put("name", cap)
                    .put("enabled", cap in enabledSet)
                    .put("supported", true)
            )
        }
        call.resolve(JSObject().put("capabilities", arr))
    }

    @PluginMethod
    fun setCapabilityEnabled(call: PluginCall) {
        val capability = call.getString("capability") ?: return call.reject("missing capability")
        val enabled = call.getBoolean("enabled") ?: return call.reject("missing enabled")

        if (capability !in KNOWN_CAPABILITIES) {
            call.resolve(disabledResult("unsupported"))
            return
        }

        if (!enabled) {
            CapabilityState.disable(context, capability)
            call.resolve(disabledResult())
            return
        }

        when (capability) {
            "send-email" -> {
                if (!hasEmailClient()) {
                    call.resolve(disabledResult("no-email-client"))
                    return
                }
                ensurePostNotifications(call, capability)
            }
            "sms-read" -> ensureRuntime(call, capability, "smsRead")
            "sms-send" -> ensureRuntime(call, capability, "smsSend")
            "contacts" -> ensureRuntime(call, capability, "contacts")
            "calendar" -> ensureRuntime(call, capability, "calendar")
            "location" -> ensureLocation(call, capability)
            "notifications" -> ensureSettings(call, capability, "notificationListener", Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            "dnd" -> ensureSettings(call, capability, "dnd", Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            "alarm" -> ensureAlarm(call, capability)
        }
    }

    private fun ensureRuntime(call: PluginCall, capability: String, alias: String) {
        if (isGranted(alias)) {
            CapabilityState.enable(context, capability)
            call.resolve(enabledResult())
            return
        }
        call.data.put("capability", capability)
        call.data.put("alias", alias)
        requestPermissionForAlias(alias, call, "onRuntimeResult")
    }

    @PermissionCallback
    private fun onRuntimeResult(call: PluginCall) {
        val capability = call.getString("capability") ?: return call.reject("missing capability")
        val alias = call.getString("alias") ?: return call.reject("missing alias")
        if (isGranted(alias)) {
            CapabilityState.enable(context, capability)
            call.resolve(enabledResult())
        } else {
            call.resolve(disabledResult("denied"))
        }
    }

    private fun ensurePostNotifications(call: PluginCall, capability: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isGranted("postNotifications")) {
            CapabilityState.enable(context, capability)
            call.resolve(enabledResult())
            return
        }
        ensureRuntime(call, capability, "postNotifications")
    }

    private fun ensureLocation(call: PluginCall, capability: String) {
        if (!isManifestPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            call.data.put("capability", capability)
            requestPermissionForAlias("location", call, "onLocationFineResult")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isManifestPermissionGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            call.data.put("capability", capability)
            requestPermissionForAlias("backgroundLocation", call, "onLocationBackgroundResult")
            return
        }
        CapabilityState.enable(context, capability)
        call.resolve(enabledResult())
    }

    @PermissionCallback
    private fun onLocationFineResult(call: PluginCall) {
        val capability = call.getString("capability") ?: return call.reject("missing capability")
        if (!isManifestPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            call.resolve(disabledResult("denied"))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isManifestPermissionGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            requestPermissionForAlias("backgroundLocation", call, "onLocationBackgroundResult")
            return
        }
        CapabilityState.enable(context, capability)
        call.resolve(enabledResult())
    }

    @PermissionCallback
    private fun onLocationBackgroundResult(call: PluginCall) {
        val capability = call.getString("capability") ?: return call.reject("missing capability")
        if (isGranted("location")) {
            CapabilityState.enable(context, capability)
            call.resolve(enabledResult())
        } else {
            call.resolve(disabledResult("denied"))
        }
    }

    private fun ensureSettings(call: PluginCall, capability: String, type: String, intent: Intent) {
        if (isGranted(type)) {
            CapabilityState.enable(context, capability)
            call.resolve(enabledResult())
            return
        }
        startSettingsRoundTrip(call, capability, type, intent)
    }

    // Alarms post a notification with full-screen intent. Both POST_NOTIFICATIONS
    // (runtime, Tiramisu+) and USE_FULL_SCREEN_INTENT (auto-granted, can be
    // revoked on Android 14+) must be granted; deny either and the alarm
    // silently never fires.
    private fun ensureAlarm(call: PluginCall, capability: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isGranted("postNotifications")) {
            call.data.put("capability", capability)
            requestPermissionForAlias("postNotifications", call, "onAlarmNotificationsResult")
            return
        }
        finishAlarmFullScreenStep(call, capability)
    }

    @PermissionCallback
    private fun onAlarmNotificationsResult(call: PluginCall) {
        val capability = call.getString("capability") ?: return call.reject("missing capability")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isGranted("postNotifications")) {
            call.resolve(disabledResult("denied"))
            return
        }
        finishAlarmFullScreenStep(call, capability)
    }

    private fun finishAlarmFullScreenStep(call: PluginCall, capability: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || isGranted("fullScreenIntent")) {
            CapabilityState.enable(context, capability)
            call.resolve(enabledResult())
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
        startSettingsRoundTrip(call, capability, "fullScreenIntent", intent)
    }

    private fun startSettingsRoundTrip(call: PluginCall, capability: String, permType: String, intent: Intent) {
        pendingCapabilityCall = call
        pendingCapabilityName = capability
        pendingCapabilityPermType = permType
        if (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0) {
            intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    override fun handleOnResume() {
        super.handleOnResume()
        pruneRevokedCapabilities()
        val call = pendingCapabilityCall ?: return
        val capability = pendingCapabilityName ?: return
        val permType = pendingCapabilityPermType ?: return
        pendingCapabilityCall = null
        pendingCapabilityName = null
        pendingCapabilityPermType = null
        if (isGranted(permType)) {
            CapabilityState.enable(context, capability)
            call.resolve(enabledResult())
        } else {
            call.resolve(disabledResult("denied"))
        }
    }

    // Keeps the SharedPreferences kill-switch honest when the user revokes a
    // permission in system Settings while the app is backgrounded.
    private fun pruneRevokedCapabilities() {
        val current = CapabilityState.get(context)
        if (current.isEmpty()) return
        val pruned = current.filter { cap -> permissionsForCapability(cap).all { isGranted(it) } }.toSet()
        if (pruned.size != current.size) CapabilityState.set(context, pruned)
    }

    private fun permissionsForCapability(capability: String): List<String> = when (capability) {
        "sms-read" -> listOf("smsRead")
        "sms-send" -> listOf("smsSend")
        "send-email" -> listOf("postNotifications")
        "notifications" -> listOf("notificationListener")
        "contacts" -> listOf("contacts")
        "calendar" -> listOf("calendar")
        "location" -> listOf("location")
        "dnd" -> listOf("dnd")
        "alarm" -> listOf("postNotifications", "fullScreenIntent")
        else -> emptyList()
    }

    fun emitDeepLink(path: String) {
        notifyListeners(EVENT_DEEP_LINK, JSObject().put("path", path))
    }

    private fun enabledResult(): JSObject = JSObject().put("enabled", true)

    private fun disabledResult(reason: String? = null): JSObject {
        val obj = JSObject().put("enabled", false)
        if (reason != null) obj.put("reason", reason)
        return obj
    }

    private fun hasEmailClient(): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:test@example.com"))
        return intent.resolveActivity(context.packageManager) != null
    }

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
