package com.palmier.app

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles send-SMS requests triggered via FCM.
 */
object SmsHandler {

    private const val TAG = "PalmierSms"

    fun handleSendSms(context: Context, data: Map<String, String>) {
        val requestId = data["requestId"] ?: return
        val hostId = data["hostId"] ?: return
        val to = data["to"]
        val body = data["body"]

        if (!CapabilityState.isEnabled(context, "sms")) {
            postResponse(requestId, hostId, JSONObject().put("error", "SMS access disabled by user"))
            return
        }

        if (to.isNullOrBlank() || body.isNullOrBlank()) {
            postResponse(requestId, hostId, JSONObject().put("error", "to and body are required"))
            return
        }

        Thread {
            try {
                val smsManager = context.getSystemService(SmsManager::class.java)
                val parts = smsManager.divideMessage(body)
                if (parts.size == 1) {
                    smsManager.sendTextMessage(to, null, body, null, null)
                } else {
                    smsManager.sendMultipartTextMessage(to, null, parts, null, null)
                }
                Log.d(TAG, "SMS sent to $to")
                postResponse(requestId, hostId, JSONObject().put("ok", true))
            } catch (e: SecurityException) {
                Log.e(TAG, "SMS permission not granted", e)
                postResponse(requestId, hostId, JSONObject().put("error", "SMS permission not granted"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send SMS", e)
                postResponse(requestId, hostId, JSONObject().put("error", "Failed to send SMS: ${e.message}"))
            }
        }.start()
    }

    private fun postResponse(requestId: String, hostId: String, result: JSONObject) {
        try {
            val url = URL("${MainActivity.SERVER_URL}/api/device/sms-response")
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
