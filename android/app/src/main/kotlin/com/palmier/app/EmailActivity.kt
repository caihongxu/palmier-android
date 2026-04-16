package com.palmier.app

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

/**
 * Transparent activity that launches the email app with a pre-filled draft.
 * Uses full-screen intent pattern to launch from the background.
 * Auto-finishes when the email app returns, sending the user back to their original screen.
 * Extends plain Activity (not AppCompatActivity) to allow android:style/Theme.Translucent.
 */
class EmailActivity : Activity() {

    companion object {
        private const val TAG = "PalmierEmail"
        private const val EMAIL_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KeyguardManager::class.java)
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        // Dismiss the notification that launched us
        val notificationId = intent.getIntExtra("notification_id", 0)
        if (notificationId != 0) {
            getSystemService(NotificationManager::class.java).cancel(notificationId)
        }

        val to = intent.getStringExtra("to") ?: ""
        val subject = intent.getStringExtra("subject") ?: ""
        val body = intent.getStringExtra("body") ?: ""
        val cc = intent.getStringExtra("cc") ?: ""
        val bcc = intent.getStringExtra("bcc") ?: ""

        // Build mailto URI
        val uriBuilder = StringBuilder("mailto:${Uri.encode(to)}")
        val params = mutableListOf<String>()
        if (subject.isNotBlank()) params.add("subject=${Uri.encode(subject)}")
        if (body.isNotBlank()) params.add("body=${Uri.encode(body)}")
        if (cc.isNotBlank()) params.add("cc=${Uri.encode(cc)}")
        if (bcc.isNotBlank()) params.add("bcc=${Uri.encode(bcc)}")
        if (params.isNotEmpty()) {
            uriBuilder.append("?${params.joinToString("&")}")
        }

        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse(uriBuilder.toString())
        }

        try {
            @Suppress("DEPRECATION")
            startActivityForResult(emailIntent, EMAIL_REQUEST_CODE)
            Log.d(TAG, "Email app launched for: $to")
        } catch (e: Exception) {
            Log.e(TAG, "No email app found", e)
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Email app finished — return user to their original screen
        finish()
    }
}
