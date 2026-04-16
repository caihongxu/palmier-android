package com.palmier.app

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BatteryHandler {

    private const val TAG = "PalmierBattery"

    fun handleReadBattery(context: Context, data: Map<String, String>) {
        val requestId = data["requestId"] ?: return
        val hostId = data["hostId"] ?: return

        Thread {
            try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = bm.isCharging

                val result = JSONObject().apply {
                    put("level", level)
                    put("charging", charging)
                }
                postResponse(requestId, hostId, result)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read battery", e)
                postResponse(requestId, hostId, JSONObject().put("error", "Failed to read battery: ${e.message}"))
            }
        }.start()
    }

    private fun postResponse(requestId: String, hostId: String, result: JSONObject) {
        try {
            val url = URL("${MainActivity.SERVER_URL}/api/device/battery-response")
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
