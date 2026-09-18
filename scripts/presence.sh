#!/usr/bin/env bash
# Presence detection: on, off, its status, or what the camera is actually seeing right now.
# Usage: presence.sh on | off | status | check
source "$(dirname "$0")/common.sh"
adb_ shell am start -n "$PKG/.ui.MainActivity" --es presence "${1:-status}" >/dev/null
sleep 4
adb_ shell cat "$DEVICE_FILES/logs/bello.log" | grep -E "PRESENCE" | tail -8
