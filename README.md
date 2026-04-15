# Palmier Android

Native Android wrapper for the Palmier PWA, built with [Capacitor](https://capacitorjs.com/). Provides native capabilities (FCM push, GPS location) that the web layer can't access in the background.

## Prerequisites

- [Node.js](https://nodejs.org/) (18+)
- [Android Studio](https://developer.android.com/studio) (bundles JDK 17+)
- A Firebase project with `google-services.json` placed in `android/app/`

## Setup

```bash
npm install
bash sync.sh             # copies PWA build and syncs into Android project
npx cap open android     # opens in Android Studio
```

## Development Workflow

1. Make changes to the PWA in `palmier-server/pwa/`
2. Build the PWA: `cd ../palmier-server/pwa && npm run build`
3. Sync into Android: `bash sync.sh`
4. Build and run from Android Studio

## Native Features

### FCM (Firebase Cloud Messaging)

The app registers an FCM token on launch and sends it to the Palmier server. This allows the server to wake the device via data-only FCM messages even when the app is not open.

- `PalmierFirebaseMessagingService` — handles incoming FCM data messages and token refreshes
- FCM token is saved to SharedPreferences so the web layer can read it via Capacitor Preferences

### Device Geolocation

When an agent on the host requests the device's location:

1. Server sends an FCM data message to the device
2. `PalmierFirebaseMessagingService` receives it and starts `GeolocationForegroundService`
3. The service fetches GPS via `FusedLocationProviderClient` and POSTs the result back to the server
4. A brief "Fetching location..." notification appears during the GPS fix (required by Android for background location access)

### Location Access Toggle

The PWA sidebar includes an "Enable Location Access" toggle (only visible on native platforms). When enabled, the device's FCM token is registered with the host as the designated location device. Only one device per host can have location access enabled at a time.

### Runtime Permissions

On first launch, the app requests:
- `ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION` — for GPS access from the background
- `POST_NOTIFICATIONS` (Android 13+) — for the foreground service notification

## Project Structure

- `www/` — copied PWA build output (gitignored, populated by `sync.sh`)
- `android/` — native Android project (Capacitor-managed)
- `android/app/src/main/java/com/palmier/app/` — native Java code
  - `MainActivity.java` — permission requests and FCM token registration
  - `PalmierFirebaseMessagingService.java` — FCM message handling
  - `GeolocationForegroundService.java` — background GPS fetch
- `capacitor.config.json` — Capacitor configuration
