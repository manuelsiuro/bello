#!/usr/bin/env bash
# Install the debug APK on the tablet and launch it.
source "$(dirname "$0")/common.sh"
adb_ install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
adb_ shell am start -n "$PKG/.ui.MainActivity"
