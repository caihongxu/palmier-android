package com.palmier.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
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
import java.io.ByteArrayOutputStream

/**
 * Unified native surface exposed to the WebView.
 *
 * Replaces seven per-capability permission plugins (Location, NotificationListener,
 * Sms, Contacts, Calendar, Dnd, FullScreenIntent) with one typed interface,
 * plus FCM token access, capability gating, and deep-link delivery as events.
 */
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
            alias = "sms",
            strings = [Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS]
        ),
        Permission(
            alias = "contacts",
            strings = [Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS]
        ),
        Permission(
            alias = "calendar",
            strings = [Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR]
        )
    ]
)
class DevicePlugin : Plugin() {

    companion object {
        const val EVENT_DEEP_LINK = "deepLink"

        /**
         * Permission types this native build understands. The PWA is served remotely and
         * can ship ahead of the APK, so types absent from this set must be reported as
         * unsupported rather than throwing — lets the PWA hide toggles it can't fulfill.
         */
        val SUPPORTED_TYPES: Set<String> = setOf(
            "location", "sms", "contacts", "calendar",
            "notificationListener", "dnd", "fullScreenIntent",
        )
    }

    private var pendingSettingsCall: PluginCall? = null
    private var pendingSettingsType: String? = null

    // ---- FCM ----

    @PluginMethod
    fun getFcmToken(call: PluginCall) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> call.resolve(JSObject().put("token", token)) }
            .addOnFailureListener { e -> call.reject("fcm_unavailable", e) }
    }

    // ---- Installed apps ----

    /**
     * Returns user-visible (launcher) apps installed on this device, used by the
     * notification app-filter UI. Filters via Intent.CATEGORY_LAUNCHER so we don't
     * need the restricted QUERY_ALL_PACKAGES permission — this only surfaces apps
     * the user can open from their home screen. Icons are rendered to 96x96 PNG
     * and base64-encoded as a data URL.
     */
    @PluginMethod
    fun getInstalledApps(call: PluginCall) {
        Thread {
            try {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val activities = pm.queryIntentActivities(intent, 0)

                // Dedupe by package — a single app can have multiple launcher activities.
                val seen = mutableSetOf<String>()
                val apps = JSArray()
                for (info in activities) {
                    val packageName = info.activityInfo.packageName
                    if (!seen.add(packageName)) continue
                    if (packageName == context.packageName) continue  // skip Palmier itself
                    val appName = info.loadLabel(pm).toString()
                    val iconDataUrl = try {
                        drawableToDataUrl(info.loadIcon(pm))
                    } catch (_: Exception) { null }
                    val obj = JSObject()
                        .put("packageName", packageName)
                        .put("appName", appName)
                    if (iconDataUrl != null) obj.put("icon", iconDataUrl)
                    apps.put(obj)
                }
                call.resolve(JSObject().put("apps", apps))
            } catch (e: Exception) {
                call.reject("failed to enumerate apps", e)
            }
        }.start()
    }

    private fun drawableToDataUrl(drawable: Drawable): String {
        val size = 96
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
        } else {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bmp
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val encoded = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        return "data:image/png;base64,$encoded"
    }

    // ---- Email client ----

    /**
     * Returns whether any installed app can handle a mailto: intent. Used by the
     * PWA to gate the Sending Email toggle — no point enabling it if the user
     * has no email client configured. Pure PackageManager lookup; no UI, no side
     * effects. Requires the matching <queries> entry in the manifest on Android 11+.
     */
    @PluginMethod
    fun hasEmailClient(call: PluginCall) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:test@example.com"))
        val available = intent.resolveActivity(context.packageManager) != null
        call.resolve(JSObject().put("available", available).put("supported", true))
    }

    // ---- Capability gating ----

    @PluginMethod
    fun setEnabledCapabilities(call: PluginCall) {
        val arr = call.getArray("capabilities") ?: return call.reject("missing capabilities")
        val set = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.toSet()
        CapabilityState.set(context, set)
        call.resolve()
    }

    // ---- Permissions ----

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
            "sms", "contacts", "calendar" -> requestRuntime(type, call)
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

    // ---- Deep links ----

    /** Called by MainActivity when an intent arrives with a deepLink extra. */
    fun emitDeepLink(path: String) {
        notifyListeners(EVENT_DEEP_LINK, JSObject().put("path", path))
    }

    // ---- Internals ----

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
        "sms" -> isManifestPermissionGranted(Manifest.permission.RECEIVE_SMS) && isManifestPermissionGranted(Manifest.permission.SEND_SMS)
        "contacts" -> isManifestPermissionGranted(Manifest.permission.READ_CONTACTS) && isManifestPermissionGranted(Manifest.permission.WRITE_CONTACTS)
        "calendar" -> isManifestPermissionGranted(Manifest.permission.READ_CALENDAR) && isManifestPermissionGranted(Manifest.permission.WRITE_CALENDAR)
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
