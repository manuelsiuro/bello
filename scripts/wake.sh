#!/usr/bin/env bash
# Wake word "Bello": turn it on or off, change how eager it is, or read its status.
# Usage: wake.sh on | off | low | normal | high | status
source "$(dirname "$0")/common.sh"
cmd="${1:-status}"
adb_ shell am start -n "$PKG/.ui.MainActivity" --es wake "$cmd" >/dev/null
sleep 1.5
adb_ shell cat "$DEVICE_FILES/logs/bello.log" | grep -E "WAKE_STATUS|WAKE_ENABLED|WAKE_SENSITIVITY" | tail -3
