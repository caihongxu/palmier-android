package com.palmier.app

import android.content.Context
import org.json.JSONArray

/**
 * Local kill-switch for device capabilities. Native receivers and FCM handlers
 * consult this as a second layer of defense beyond the server-side capability
 * token: if the user has the capability disabled in the drawer, the native side
 * refuses to relay events or respond to requests even if the server asks.
 *
 * Storage: a single JSON-array string under "enabledCapabilities" in CapacitorStorage.
 * Written only by DevicePlugin.setEnabledCapabilities (from the PWA's derived state).
 *
 * Legacy fallback: older APK+PWA combos used per-capability keys
 * ("smsListenerEnabled", "contactsAccessEnabled", etc.) with null-means-permissive
 * semantics. While the new key is absent, isEnabled defers to those keys so pre-sync
 * FCM requests on fresh upgrades don't break. After the PWA calls
 * setEnabledCapabilities once, the new key takes over.
 */
object CapabilityState {
    private const val PREFS = "CapacitorStorage"
    private const val KEY = "enabledCapabilities"

    fun set(context: Context, capabilities: Set<String>) {
        val arr = JSONArray()
        capabilities.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    fun isEnabled(context: Context, capability: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY, null)
        if (json != null) {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).any { arr.getString(it) == capability }
            } catch (_: Exception) {
                false
            }
        }
        // Legacy fallback: null means "not explicitly disabled" (permissive).
        val legacyKey = when (capability) {
            "notifications" -> "notificationListenerEnabled"
            "sms" -> "smsListenerEnabled"
            "contacts" -> "contactsAccessEnabled"
            "calendar" -> "calendarAccessEnabled"
            else -> return false
        }
        return prefs.getString(legacyKey, null) != "false"
    }
}
