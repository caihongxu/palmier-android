# Palmier Android

Native Android wrapper for the Palmier PWA, built with [Capacitor](https://capacitorjs.com/). The WebView loads directly from [app.palmier.me](https://app.palmier.me), so PWA changes ship instantly with no APK rebuild. The app provides native capabilities the web layer can't access in the background — FCM push, GPS, contacts, calendar, SMS, email, alarms, battery, ringer control, and device notifications.

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

## Native Features

All device capabilities work in the background via FCM data messages — the app doesn't need to be in the foreground. Each capability is toggled on/off from the drawer when this device is the host's **linked device** (the one device the host talks to for SMS, contacts, location, etc.). Toggle state is stored in `CapacitorStorage` SharedPreferences and consulted by `CapabilityState.isEnabled` before every handler executes, regardless of what the host requests.

### FCM (Firebase Cloud Messaging)

The app registers an FCM token on launch and sends it to the Palmier server. This allows the server to wake the device via data-only FCM messages even when the app is not open.

- `PalmierFirebaseMessagingService` — handles incoming FCM data messages, dispatches to capability handlers, and manages token refreshes
- FCM token is saved to SharedPreferences so the web layer can read it via Capacitor Preferences

### Device Geolocation

When an agent requests the device's location, a foreground service briefly starts to fetch the GPS fix.

- `GeolocationForegroundService` — fetches GPS via `FusedLocationProviderClient`
- Toggle: **Get Location** (runtime permission: `ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION`)

### Device Notifications

Captures all notifications from all apps on the device and relays them to the host.

- `DeviceNotificationListenerService` — `NotificationListenerService` that captures notifications
- Excludes Palmier's own task notifications (channel `palmier_tasks`) and the default SMS app's notifications
- Debounces rapid notifications (2s window per app+title)
- Toggle: **Notifications from Other Apps** (system settings — notification listener access)

### SMS

Captures incoming SMS messages and enables sending SMS from the device. Read and send are gated by separate toggles so users can enable only what they need.

- `SmsBroadcastReceiver` — captures incoming SMS via `SMS_RECEIVED` broadcast
- `SmsHandler` — sends SMS via `SmsManager`
- Toggles: **Read SMS** (runtime permission: `RECEIVE_SMS`), **Send SMS** (runtime permission: `SEND_SMS`)

### Contacts

Read and create contacts on the device.

- `ContactsHandler` — reads via `ContactsContract`, creates via batch `ContentProviderOperation`
- Toggle: **Manage Contacts** (runtime permissions: `READ_CONTACTS` + `WRITE_CONTACTS`)

### Send Email

Compose an email from the agent — the user taps a notification to review and send via their own email client. Nothing is sent silently.

- `EmailHandler` — posts a "Pending email" notification; tapping it launches `EmailActivity`
- `EmailActivity` — fires an `ACTION_SENDTO` `mailto:` intent with `to`/`cc`/`bcc`/`subject`/`body` pre-filled, handing off to the user's email app
- Toggle: **Send Email** (no runtime permission; requires a `mailto:`-capable app installed — otherwise the toggle surfaces `"no-email-client"`)

### Calendar

Read and create calendar events on the device.

- `CalendarHandler` — reads/creates via `CalendarContract`
- Toggle: **Manage Calendar** (runtime permissions: `READ_CALENDAR` + `WRITE_CALENDAR`)

### Alarm

Trigger a full-screen alarm popup on the device — even over the lock screen — with a looping ringtone that plays until the user dismisses it. Bypasses Do Not Disturb.

- `AlarmHandler` — posts a `CATEGORY_ALARM` notification with a full-screen intent on a DND-bypassing channel (`palmier_alarms`)
- `AlarmActivity` — full-screen activity launched by the intent; renders title/description + Dismiss button, plays the default alarm ringtone on the alarm audio stream via `RingtoneManager`
- Toggle: **Trigger Alarms** (requires `USE_FULL_SCREEN_INTENT`; Android 14+ needs the user to grant it in app settings)

### Battery

Read battery level and charging status.

- `BatteryHandler` — reads via `BatteryManager`
- No toggle or permission needed

### Ringer Mode

Set the phone's ringer mode (normal, vibrate, silent).

- `RingerHandler` — sets via `AudioManager` and `NotificationManager`
- Toggle: **Set Ringer Mode** (system settings — DND access)

## Native/Web Interface

A single custom Capacitor plugin (`Device`) exposes the entire native surface to the PWA. Collapsing the earlier six permission plugins into one keeps the contract typed and discoverable, with no shared SharedPreferences "mailbox" between the two sides.

| Method | Purpose |
|--------|---------|
| `getFcmToken()` | Fetch the device's Firebase token. Always fresh — the PWA no longer reads a cached copy from SharedPreferences. |
| `getCapabilityStatus()` | Returns `{ capabilities: [{ name, enabled, supported }] }` — one entry per capability the APK knows about. The PWA renders only these; older APKs that don't yet support a capability simply omit it from the list (PWA ships ahead of the APK). |
| `setCapabilityEnabled({capability, enabled})` | Atomically gates a single capability. With `enabled: true`, the plugin drives any required permission dialog or system-Settings round-trip, then writes `CapabilityState` only on grant. With `enabled: false`, removes from `CapabilityState` (no OS dialog). Returns `{ enabled, reason? }` — `reason` distinguishes `"denied"`, `"no-email-client"`, and `"unsupported"` so the PWA can show appropriate messaging. The plugin also prunes `CapabilityState` on every app resume so any permission revoked in system Settings flips the corresponding toggle off automatically. |
| `getInstalledApps()` | Enumerate launcher apps for the notification-trigger picker. |
| `addListener("deepLink", handler)` | Event channel for FCM notification taps. Native emits `{path}`; the PWA's router handles it client-side (no more `evaluateJavascript`). |

## License

Licensed under the Apache License 2.0. See [LICENSE](LICENSE) for the full text.
