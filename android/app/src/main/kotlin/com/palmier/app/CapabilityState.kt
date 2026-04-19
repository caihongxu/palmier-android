package com.palmier.app

import android.content.Context
import org.json.JSONArray

// Local kill-switch consulted by native receivers and FCM handlers. If a capability
// is disabled here, the native side refuses to act even if the server requests it —
// a second line of defense beyond the server-side capability token.
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
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return false
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).any { arr.getString(it) == capability }
        } catch (_: Exception) {
            false
        }
    }
}
