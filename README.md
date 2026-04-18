# Palmier Android

Native Android wrapper for the Palmier PWA, built with [Capacitor](https://capacitorjs.com/). The WebView loads directly from [app.palmier.me](https://app.palmier.me), so PWA changes ship instantly with no APK rebuild. The app provides native capabilities the web layer can't access in the background — FCM push, GPS, contacts, calendar, SMS, alarms, battery, ringer control, and device notifications.

## Prerequisites

- [Node.js](https://nodejs.org/) (22+)
- [Android Studio](https://developer.android.com/studio) (bundles JDK 21+)
- A Firebase project with `google-services.json` placed in `android/app/`

## Setup

```bash
npm install
npx cap sync          # syncs native plugins + offline fallback into Android project
npx cap open android  # opens in Android Studio
```

## How the WebView Loads

`capacitor.config.json` sets `server.url` to `https://app.palmier.me`. On launch, the WebView fetches the PWA from the cloud. When the device is offline the WebView falls back to `www/offline.html` (configured via `server.errorPath`), which shows a "you're offline" screen and auto-reloads when connectivity returns.

Because the PWA is served remotely, this repo has no build-time dependency on `palmier-server`. Release builds run `npx cap sync` only — no PWA build step.

### Access modes

This app only supports **Server mode** (via `app.palmier.me`). LAN mode is browser-only — the WebView would block cleartext `http://<host-ip>:<port>` requests as mixed content. Users on LAN open the PWA from Chrome/Safari directly.

### Deep links

FCM notification taps pass a relative path (e.g. `/runs/:taskId/:runId`) via an Intent extra. `MainActivity.handleDeepLink` evaluates `window.location.href='<path>'` inside the WebView, which resolves against `https://app.palmier.me`. No external `intent-filter` is registered.

## Development Workflow

1. Make changes to the PWA in `palmier-server/pwa/` and deploy to `app.palmier.me`.
2. Reload the app — changes are live (no rebuild, no `cap sync`).
3. Only rebuild the APK when you change native Kotlin code, Capacitor plugins, `capacitor.config.json`, or `www/offline.html`.

## Releases

Release APKs are built automatically via GitHub Actions when a version tag is pushed:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The workflow runs `npm ci && npx cap sync`, then builds a signed APK and creates a GitHub release. See `.github/workflows/release.yml`.

## Native Features

All device capabilities work in the background via FCM data messages — the app doesn't need to be in the foreground. Each capability is toggled on/off in the app's settings menu.

### FCM (Firebase Cloud Messaging)

The app registers an FCM token on launch and sends it to the Palmier server. This allows the server to wake the device via data-only FCM messages even when the app is not open.

- `PalmierFirebaseMessagingService` — handles incoming FCM data messages, dispatches to capability handlers, and manages token refreshes
- FCM token is saved to SharedPreferences so the web layer can read it via Capacitor Preferences

### Device Geolocation

When an agent requests the device's location, a foreground service briefly starts to fetch the GPS fix.

- `GeolocationForegroundService` — fetches GPS via `FusedLocationProviderClient`
- Toggle: **Location Access** (runtime permission: `ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION`)

### Device Notifications

Captures all notifications from all apps on the device and relays them to the host.

- `DeviceNotificationListenerService` — `NotificationListenerService` that captures notifications
- Excludes Palmier's own task notifications (channel `palmier_tasks`) and the default SMS app's notifications
- Debounces rapid notifications (2s window per app+title)
- Toggle: **Notification Access** (system settings — notification listener access)

### SMS

Captures incoming SMS messages and enables sending SMS from the device.

- `SmsBroadcastReceiver` — captures incoming SMS via `SMS_RECEIVED` broadcast
- `SmsHandler` — sends SMS via `SmsManager`
- Toggle: **SMS Access** (runtime permissions: `RECEIVE_SMS` + `SEND_SMS`)

### Contacts

Read and create contacts on the device.

- `ContactsHandler` — reads via `ContactsContract`, creates via batch `ContentProviderOperation`
- Toggle: **Contacts Access** (runtime permissions: `READ_CONTACTS` + `WRITE_CONTACTS`)

### Calendar

Read and create calendar events on the device.

- `CalendarHandler` — reads/creates via `CalendarContract`
- Toggle: **Calendar Access** (runtime permissions: `READ_CALENDAR` + `WRITE_CALENDAR`)

### Alarm

Trigger a full-screen alarm popup on the device — even over the lock screen — with a looping ringtone that plays until the user dismisses it. Bypasses Do Not Disturb.

- `AlarmHandler` — posts a `CATEGORY_ALARM` notification with a full-screen intent on a DND-bypassing channel (`palmier_alarms`)
- `AlarmActivity` — full-screen activity launched by the intent; renders title/description + Dismiss button, plays the default alarm ringtone on the alarm audio stream via `RingtoneManager`
- Toggle: **Full-Screen Alarm** (requires `USE_FULL_SCREEN_INTENT`; Android 14+ needs the user to grant it in app settings)

### Battery

Read battery level and charging status.

- `BatteryHandler` — reads via `BatteryManager`
- No toggle or permission needed

### Ringer Mode

Set the phone's ringer mode (normal, vibrate, silent).

- `RingerHandler` — sets via `AudioManager` and `NotificationManager`
- Toggle: **Do Not Disturb Control** (system settings — DND access)

## Native/Web Interface

A single custom Capacitor plugin (`Device`) exposes the entire native surface to the PWA. Collapsing the earlier six permission plugins into one keeps the contract typed and discoverable, with no shared SharedPreferences "mailbox" between the two sides.

| Method | Purpose |
|--------|---------|
| `getFcmToken()` | Fetch the device's Firebase token. Always fresh — the PWA no longer reads a cached copy from SharedPreferences. |
| `getSupportedPermissions()` | List of permission types this APK understands. The PWA queries on mount and hides toggles whose permissions the installed APK can't fulfill — important because the PWA is served remotely and can ship ahead of the APK. |
| `checkPermission({type})` / `requestPermission({type})` | Unified permission gate. `type` is one of `location`, `sms`, `contacts`, `calendar`, `notificationListener`, `dnd`, `fullScreenIntent`. Returns `{granted, supported}`; unknown types resolve with `supported: false` rather than throwing. |
| `setEnabledCapabilities({capabilities})` | Authoritative local whitelist — native receivers (`SmsBroadcastReceiver`, `DeviceNotificationListenerService`) and FCM handlers refuse to act on capabilities not in this list, even if the server asks. Persisted via `CapabilityState`. |
| `addListener("deepLink", handler)` | Event channel for FCM notification taps. Native emits `{path}`; the PWA's router handles it client-side (no more `evaluateJavascript`). |

## Project Structure

- `www/offline.html` — offline fallback page (shown when `app.palmier.me` is unreachable)
- `android/` — native Android project (Capacitor-managed)
- `android/app/src/main/kotlin/com/palmier/app/` — native Kotlin code
  - `MainActivity.kt` — plugin registration, POST_NOTIFICATIONS prompt, deep-link buffering
  - `DevicePlugin.kt` — the unified Capacitor plugin (permissions, FCM token, capability gating, deep-link events)
  - `CapabilityState.kt` — reads/writes the local enabled-capabilities set (with legacy fallback for upgrades from the pre-`Device` APK)
  - `PalmierFirebaseMessagingService.kt` — FCM message handling, dispatch, background token re-registration
  - `GeolocationForegroundService.kt` — background GPS fetch
  - `DeviceNotificationListenerService.kt` — device notification capture
  - `SmsBroadcastReceiver.kt` — incoming SMS capture
  - `SmsHandler.kt` — send SMS
  - `ContactsHandler.kt` — read/create contacts
  - `CalendarHandler.kt` — read/create calendar events
  - `AlarmHandler.kt` / `AlarmActivity.kt` — full-screen alarm popup with looping ringtone
  - `BatteryHandler.kt` — read battery
  - `RingerHandler.kt` — set ringer mode
  - `NotificationActionReceiver.kt` — push notification action buttons
- `capacitor.config.json` — Capacitor configuration (remote `server.url` + offline `errorPath`)
- `.github/workflows/release.yml` — automated APK release workflow
