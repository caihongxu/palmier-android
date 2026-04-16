# Palmier Android

Native Android wrapper for the Palmier PWA, built with [Capacitor](https://capacitorjs.com/). Provides native capabilities that the web layer can't access in the background — FCM push, GPS, contacts, calendar, SMS, alarms, battery, ringer control, and device notifications.

## Prerequisites

- [Node.js](https://nodejs.org/) (22+)
- [Android Studio](https://developer.android.com/studio) (bundles JDK 21+)
- A Firebase project with `google-services.json` placed in `android/app/`

## Setup

```bash
npm install
npx cap sync          # copies PWA build and syncs into Android project
npx cap open android  # opens in Android Studio
```

## Development Workflow

1. Make changes to the PWA in `palmier-server/pwa/`
2. Build the PWA: `cd ../palmier-server/pwa && pnpm build`
3. Copy into www: `cp -r ../palmier-server/pwa/dist/* www/`
4. Sync into Android: `npx cap sync`
5. Build and run from Android Studio

## Releases

Release APKs are built automatically via GitHub Actions when a version tag is pushed:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The workflow builds a signed APK and creates a GitHub release. See `.github/workflows/release.yml`.

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

Set alarms on the device's default clock app.

- `AlarmHandler` — fires `AlarmClock.ACTION_SET_ALARM` intent
- No toggle needed (uses `SET_ALARM` normal permission, auto-granted)

### Battery

Read battery level and charging status.

- `BatteryHandler` — reads via `BatteryManager`
- No toggle or permission needed

### Ringer Mode

Set the phone's ringer mode (normal, vibrate, silent).

- `RingerHandler` — sets via `AudioManager` and `NotificationManager`
- Toggle: **Do Not Disturb Control** (system settings — DND access)

## Capacitor Plugins

Custom Capacitor plugins bridge native permissions to the PWA's toggle UI:

| Plugin | Methods | Purpose |
|--------|---------|---------|
| `LocationPermission` | `check()`, `request()` | Fine + background location |
| `NotificationListener` | `check()`, `request()` | Notification listener access (opens system settings) |
| `SmsPermission` | `check()`, `request()` | RECEIVE_SMS + SEND_SMS |
| `ContactsPermission` | `check()`, `request()` | READ_CONTACTS + WRITE_CONTACTS |
| `CalendarPermission` | `check()`, `request()` | READ_CALENDAR + WRITE_CALENDAR |
| `DndAccess` | `check()`, `request()` | Do Not Disturb policy access (opens system settings) |

## Project Structure

- `www/` — copied PWA build output (gitignored, populated by cap sync)
- `android/` — native Android project (Capacitor-managed)
- `android/app/src/main/kotlin/com/palmier/app/` — native Kotlin code
  - `MainActivity.kt` — permission requests, FCM token registration, plugin registration
  - `PalmierFirebaseMessagingService.kt` — FCM message handling and dispatch
  - `GeolocationForegroundService.kt` — background GPS fetch
  - `DeviceNotificationListenerService.kt` — device notification capture
  - `SmsBroadcastReceiver.kt` — incoming SMS capture
  - `SmsHandler.kt` — send SMS
  - `ContactsHandler.kt` — read/create contacts
  - `CalendarHandler.kt` — read/create calendar events
  - `AlarmHandler.kt` — set alarms
  - `BatteryHandler.kt` — read battery
  - `RingerHandler.kt` — set ringer mode
  - `DndAccessPlugin.kt` — DND access Capacitor plugin
  - `NotificationListenerPlugin.kt` — notification listener Capacitor plugin
  - `SmsPermissionPlugin.kt` — SMS permission Capacitor plugin
  - `ContactsPermissionPlugin.kt` — contacts permission Capacitor plugin
  - `CalendarPermissionPlugin.kt` — calendar permission Capacitor plugin
  - `LocationPermissionPlugin.kt` — location permission Capacitor plugin
  - `NotificationActionReceiver.kt` — push notification action buttons
- `capacitor.config.json` — Capacitor configuration
- `.github/workflows/release.yml` — automated APK release workflow
