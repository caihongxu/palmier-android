#!/bin/bash
# Copies the latest PWA build from palmier-server and syncs it into the Android project.
# Usage: ./sync.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PWA_DIST="$SCRIPT_DIR/../palmier-server/pwa/dist"

if [ ! -d "$PWA_DIST" ]; then
  echo "PWA dist not found at $PWA_DIST"
  echo "Run 'npm run build' in palmier-server/pwa first."
  exit 1
fi

rm -rf "$SCRIPT_DIR/www"
cp -r "$PWA_DIST" "$SCRIPT_DIR/www"
cd "$SCRIPT_DIR"
npx cap sync
echo "Done. Run 'npx cap open android' to open in Android Studio."
