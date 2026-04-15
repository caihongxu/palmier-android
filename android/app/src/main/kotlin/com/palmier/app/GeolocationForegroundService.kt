package com.palmier.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.net.HttpURLConnection
import java.net.URL

class GeolocationForegroundService : Service() {

    companion object {
        private const val TAG = "PalmierGeoService"
        private const val CHANNEL_ID = "palmier_location"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = intent?.getStringExtra("requestId") ?: return START_NOT_STICKY
        val hostId = intent.getStringExtra("hostId") ?: return START_NOT_STICKY
        val serverUrl = intent.getStringExtra("serverUrl") ?: return START_NOT_STICKY

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Palmier")
            .setContentText("Fetching location...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        fetchLocationAndRespond(requestId, hostId, serverUrl)
        return START_NOT_STICKY
    }

    @Suppress("MissingPermission")
    private fun fetchLocationAndRespond(requestId: String, hostId: String, serverUrl: String) {
        val locationClient = LocationServices.getFusedLocationProviderClient(this)
        val cancellationToken = CancellationTokenSource()

        try {
            locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationToken.token)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        Log.d(TAG, "Location obtained: ${location.latitude}, ${location.longitude}")
                        postLocationToServer(serverUrl, requestId, hostId,
                            location.latitude, location.longitude,
                            location.accuracy, location.time)
                    } else {
                        Log.w(TAG, "Location is null")
                        postErrorToServer(serverUrl, requestId, hostId, "Location unavailable")
                    }
                    stopSelf()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get location", e)
                    postErrorToServer(serverUrl, requestId, hostId, "Location fetch failed: ${e.message}")
                    stopSelf()
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted", e)
            postErrorToServer(serverUrl, requestId, hostId, "Location permission not granted")
            stopSelf()
        }
    }

    private fun postLocationToServer(
        serverUrl: String, requestId: String, hostId: String,
        latitude: Double, longitude: Double, accuracy: Float, timestamp: Long
    ) {
        Thread {
            try {
                val url = URL("$serverUrl/api/fcm/geolocation-response")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val json = """{"requestId":"$requestId","hostId":"$hostId","latitude":$latitude,"longitude":$longitude,"accuracy":$accuracy,"timestamp":$timestamp}"""
                conn.outputStream.use { it.write(json.toByteArray()) }

                Log.d(TAG, "Location posted, response: ${conn.responseCode}")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to post location", e)
            }
        }.start()
    }

    private fun postErrorToServer(serverUrl: String, requestId: String, hostId: String, error: String) {
        Thread {
            try {
                val url = URL("$serverUrl/api/fcm/geolocation-response")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val escapedError = error.replace("\"", "\\\"")
                val json = """{"requestId":"$requestId","hostId":"$hostId","error":"$escapedError"}"""
                conn.outputStream.use { it.write(json.toByteArray()) }

                Log.d(TAG, "Error posted, response: ${conn.responseCode}")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to post error", e)
            }
        }.start()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Location Requests",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown briefly when fetching device location"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
