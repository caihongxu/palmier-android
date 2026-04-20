package com.palmier.app

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object RingerHandler {

    private const val TAG = "PalmierRinger"

    fun handleSetRingerMode(context: Context, data: Map<String, String>) {
        val requestId = data["requestId"] ?: return
        val hostId = data["hostId"] ?: return
        val mode = data["mode"]

        if (!CapabilityState.isEnabled(context, "dnd")) {
            postResponse(requestId, hostId, JSONObject().put("error", "Set Ringer Mode capability is disabled on this device"))
            return
        }

        Thread {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                if (!notifManager.isNotificationPolicyAccessGranted) {
                    postResponse(requestId, hostId, JSONObject().put("error", "Do Not Disturb access not granted"))
                    return@Thread
                }

                when (mode) {
                    "normal" -> audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    "vibrate" -> audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                    "silent" -> audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                    else -> {
                        postResponse(requestId, hostId, JSONObject().put("error", "mode must be 'normal', 'vibrate', or 'silent'"))
                        return@Thread
                    }
                }
                Log.d(TAG, "Ringer mode set to $mode")
                postResponse(requestId, hostId, JSONObject().put("ok", true).put("mode", mode))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set ringer mode", e)
                postResponse(requestId, hostId, JSONObject().put("error", "Failed to set ringer mode: ${e.message}"))
            }
        }.start()
    }

    private fun postResponse(requestId: String, hostId: String, result: JSONObject) {
        try {
            val url = URL("${MainActivity.SERVER_URL}/api/device/ringer-response")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val payload = JSONObject().apply {
                put("requestId", requestId)
                put("hostId", hostId)
                put("result", result)
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            Log.d(TAG, "Response posted, status: ${conn.responseCode}")
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post response", e)
        }
    }
}
