#!/usr/bin/env bash
# Reboot the tablet and measure how long until the face is ready (Phase 1 done criterion: < 60 s after boot).
source "$(dirname "$0")/common.sh"
adb_ reboot
adb_ wait-for-device
echo "device back on adb, waiting for FACE_READY…"
start=$(date +%s)
for _ in $(seq 1 180); do
  sleep 2
  line="$(adb_ shell cat "$DEVICE_FILES/logs/bello.log" 2>/dev/null | grep -E "BOOT_COMPLETED|FACE_READY" | tail -2 | tr -d '\r')"
  if printf '%s\n' "$line" | head -1 | grep -q BOOT_COMPLETED && printf '%s\n' "$line" | tail -1 | grep -q FACE_READY; then
    printf '%s\n' "$line"
    echo "FACE_READY about $(( $(date +%s) - start ))s after adb came back"
    exit 0
  fi
done
echo "FACE_READY not seen after boot"; exit 1
