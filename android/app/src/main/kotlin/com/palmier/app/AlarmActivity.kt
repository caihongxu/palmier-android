package com.palmier.app

import android.app.KeyguardManager
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AlarmActivity : AppCompatActivity() {

    private var ringtone: android.media.Ringtone? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val title = intent.getStringExtra("title") ?: "Alarm"
        val description = intent.getStringExtra("description") ?: ""
        val notificationId = intent.getIntExtra("notification_id", 0)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 128, 64, 64)
            gravity = android.view.Gravity.CENTER
        }

        val iconText = TextView(this).apply {
            text = "\u26A0\uFE0F"
            textSize = 48f
            gravity = android.view.Gravity.CENTER
        }
        layout.addView(iconText)

        val titleView = TextView(this).apply {
            text = title
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 16)
        }
        layout.addView(titleView)

        if (description.isNotBlank()) {
            val descView = TextView(this).apply {
                text = description
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, 48)
            }
            layout.addView(descView)
        }

        val dismissButton = Button(this).apply {
            text = "Dismiss"
            textSize = 18f
            setOnClickListener {
                stopAlarmSound()
                getSystemService(NotificationManager::class.java).cancel(notificationId)
                finish()
            }
        }
        layout.addView(dismissButton)

        setContentView(layout)

        playAlarmSound()
    }

    private fun playAlarmSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        } catch (_: Exception) {
        }
    }

    private fun stopAlarmSound() {
        ringtone?.stop()
        ringtone = null
    }

    override fun onDestroy() {
        stopAlarmSound()
        super.onDestroy()
    }
}
