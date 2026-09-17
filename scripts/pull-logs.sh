#!/usr/bin/env bash
# Copy the app's rotating log files from the tablet to ./logs/ and print the tail.
source "$(dirname "$0")/common.sh"
mkdir -p "$ROOT/logs"
adb_ pull "$DEVICE_FILES/logs/." "$ROOT/logs/" >/dev/null
tail -n "${1:-40}" "$ROOT/logs/bello.log"
