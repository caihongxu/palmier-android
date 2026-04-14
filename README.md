# Palmier Android

Native Android wrapper for the Palmier PWA, built with [Capacitor](https://capacitorjs.com/).

## Prerequisites

- [Node.js](https://nodejs.org/) (18+)
- [Android Studio](https://developer.android.com/studio)
- JDK 17+

## Setup

```bash
npm install
./sync.sh        # copies PWA build and syncs into Android project
npx cap open android  # opens in Android Studio
```

## Development Workflow

1. Make changes to the PWA in `palmier-server/pwa/`
2. Build the PWA: `cd ../palmier-server/pwa && npm run build`
3. Sync into Android: `./sync.sh`
4. Build and run from Android Studio

## Project Structure

- `www/` — copied PWA build output (gitignored, populated by `sync.sh`)
- `android/` — native Android project (Capacitor-managed)
- `capacitor.config.json` — Capacitor configuration
