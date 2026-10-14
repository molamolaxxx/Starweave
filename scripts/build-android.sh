#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR/mobile"

if [[ -n "${CORDOVA_JAVA_HOME:-}" ]]; then
  export JAVA_HOME="$CORDOVA_JAVA_HOME"
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo 'Set JAVA_HOME or CORDOVA_JAVA_HOME to JDK 17.' >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
if ! java -version 2>&1 | head -1 | grep -q '"17\.'; then
  echo 'Cordova Android 13 requires JDK 17; set CORDOVA_JAVA_HOME.' >&2
  exit 1
fi
if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  echo 'Set ANDROID_HOME to the Android SDK with platform 34 and build-tools 34.0.0.' >&2
  exit 1
fi
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
npm ci --no-audit --no-fund
npm test
npm run prepare:android
npm run build:android
mkdir -p "$ROOT_DIR/dist/android"
APP_VERSION="$(node -p 'require("./package.json").version')"
APK_PATH="$ROOT_DIR/dist/android/starweave-$APP_VERSION-debug.apk"
cp platforms/android/app/build/outputs/apk/debug/app-debug.apk "$APK_PATH"
sha256sum "$APK_PATH"
