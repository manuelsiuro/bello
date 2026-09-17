#!/usr/bin/env bash
# Kiosk watchdog on/off (off while developing if you need other apps in the foreground).
source "$(dirname "$0")/common.sh"
adb_ shell am start -n "$PKG/.ui.MainActivity" --es kiosk "${1:?on|off}" >/dev/null
