package com.palmier.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class PalmierFirebaseMessagingService extends FirebaseMessagingService {
    private static final String TAG = "PalmierFCM";
    private static final String PREFS_NAME = "CapacitorStorage";

    @Override
    public void onNewToken(@NonNull String token) {
        Log.d(TAG, "New FCM token: " + token);
        // Save to SharedPreferences so the web layer can read it via Capacitor Preferences
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString("fcmToken", token).apply();
        registerTokenWithServer(token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        Map<String, String> data = remoteMessage.getData();
        String type = data.get("type");

        if ("geolocation-request".equals(type)) {
            String requestId = data.get("requestId");
            String hostId = data.get("hostId");

            if (requestId != null && hostId != null) {
                Log.d(TAG, "Geolocation request: " + requestId);
                Intent intent = new Intent(this, GeolocationForegroundService.class);
                intent.putExtra("requestId", requestId);
                intent.putExtra("hostId", hostId);

                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String serverUrl = prefs.getString("serverUrl", null);
                if (serverUrl != null) {
                    intent.putExtra("serverUrl", serverUrl);
                    startForegroundService(intent);
                } else {
                    Log.e(TAG, "No serverUrl configured, cannot fulfill geolocation request");
                }
            }
        }
    }

    private void registerTokenWithServer(String token) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String serverUrl = prefs.getString("serverUrl", null);
                String hostId = prefs.getString("hostId", null);

                if (serverUrl == null || hostId == null) {
                    Log.w(TAG, "serverUrl or hostId not set, skipping token registration");
                    return;
                }

                URL url = new URL(serverUrl + "/api/fcm/register");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                String json = String.format(
                    "{\"hostId\":\"%s\",\"fcmToken\":\"%s\"}",
                    hostId, token
                );

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "Token registration response: " + responseCode);
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Failed to register token", e);
            }
        }).start();
    }
}
