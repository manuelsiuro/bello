#!/usr/bin/env bash
# Night mode: force it on or off to see it without waiting for 23:00, or hand it back to the clock.
# Usage: night.sh on | off | auto
source "$(dirname "$0")/common.sh"
adb_ shell am start -n "$PKG/.ui.MainActivity" --es night "${1:?on|off|auto}" >/dev/null
sleep 1.5
adb_ shell cat "$DEVICE_FILES/logs/bello.log" | grep -E "NIGHT" | tail -3
