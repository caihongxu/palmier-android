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

    fun enable(context: Context, capability: String) {
        set(context, get(context) + capability)
    }

    fun disable(context: Context, capability: String) {
        set(context, get(context) - capability)
    }

    fun isEnabled(context: Context, capability: String): Boolean {
        return capability in get(context)
    }

    fun get(context: Context): Set<String> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptySet()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
