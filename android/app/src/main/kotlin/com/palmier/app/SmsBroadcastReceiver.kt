package com.palmier.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class SmsBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PalmierSmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        if (!CapabilityState.isEnabled(context, "sms-read")) return

        val prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
        val hostId = prefs.getString("hostId", null)
        if (hostId == null) {
            Log.w(TAG, "hostId not set, skipping SMS relay")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        // Group message parts by sender (multi-part SMS)
        val bySender = mutableMapOf<String, StringBuilder>()
        var timestamp = System.currentTimeMillis()
        for (msg in messages) {
            val sender = msg.displayOriginatingAddress ?: msg.originatingAddress ?: "unknown"
            bySender.getOrPut(sender) { StringBuilder() }.append(msg.displayMessageBody ?: "")
            timestamp = msg.timestampMillis
        }

        val pendingResult = goAsync()
        Thread {
            try {
                for ((sender, body) in bySender) {
                    Log.d(TAG, "Relaying SMS from $sender")

                    val url = URL("${MainActivity.SERVER_URL}/api/device/sms")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000

                    val sms = org.json.JSONObject().apply {
                        put("id", UUID.randomUUID().toString())
                        put("sender", sender)
                        put("body", body.toString())
                        put("timestamp", timestamp)
                    }
                    val payload = org.json.JSONObject().apply {
                        put("hostId", hostId)
                        put("sms", sms)
                    }

                    conn.outputStream.use { it.write(payload.toString().toByteArray()) }

                    val code = conn.responseCode
                    if (code != 200) {
                        Log.w(TAG, "Relay response: $code")
                    }
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to relay SMS", e)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
