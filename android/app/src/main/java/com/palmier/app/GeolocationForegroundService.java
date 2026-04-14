package com.palmier.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class GeolocationForegroundService extends Service {
    private static final String TAG = "PalmierGeoService";
    private static final String CHANNEL_ID = "palmier_location";
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String requestId = intent.getStringExtra("requestId");
        String hostId = intent.getStringExtra("hostId");
        String serverUrl = intent.getStringExtra("serverUrl");

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Palmier")
                .setContentText("Fetching location...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        fetchLocationAndRespond(requestId, hostId, serverUrl);

        return START_NOT_STICKY;
    }

    private void fetchLocationAndRespond(String requestId, String hostId, String serverUrl) {
        FusedLocationProviderClient locationClient = LocationServices.getFusedLocationProviderClient(this);
        CancellationTokenSource cancellationToken = new CancellationTokenSource();

        try {
            locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationToken.getToken())
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            Log.d(TAG, "Location obtained: " + location.getLatitude() + ", " + location.getLongitude());
                            postLocationToServer(serverUrl, requestId, hostId,
                                    location.getLatitude(), location.getLongitude(),
                                    location.getAccuracy(), location.getTime());
                        } else {
                            Log.w(TAG, "Location is null");
                            postErrorToServer(serverUrl, requestId, hostId, "Location unavailable");
                        }
                        stopSelf();
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to get location", e);
                        postErrorToServer(serverUrl, requestId, hostId, "Location fetch failed: " + e.getMessage());
                        stopSelf();
                    });
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
            postErrorToServer(serverUrl, requestId, hostId, "Location permission not granted");
            stopSelf();
        }
    }

    private void postLocationToServer(String serverUrl, String requestId, String hostId,
                                       double latitude, double longitude, float accuracy, long timestamp) {
        new Thread(() -> {
            try {
                URL url = new URL(serverUrl + "/api/fcm/geolocation-response");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                String json = String.format(
                        "{\"requestId\":\"%s\",\"hostId\":\"%s\",\"latitude\":%f,\"longitude\":%f,\"accuracy\":%f,\"timestamp\":%d}",
                        requestId, hostId, latitude, longitude, accuracy, timestamp
                );

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "Location posted, response: " + responseCode);
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Failed to post location", e);
            }
        }).start();
    }

    private void postErrorToServer(String serverUrl, String requestId, String hostId, String error) {
        new Thread(() -> {
            try {
                URL url = new URL(serverUrl + "/api/fcm/geolocation-response");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                String json = String.format(
                        "{\"requestId\":\"%s\",\"hostId\":\"%s\",\"error\":\"%s\"}",
                        requestId, hostId, error.replace("\"", "\\\"")
                );

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "Error posted, response: " + responseCode);
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Failed to post error", e);
            }
        }).start();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Location Requests",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shown briefly when fetching device location");
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
