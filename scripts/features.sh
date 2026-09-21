#!/usr/bin/env bash
# Switch one of Bello's features on or off, or list them all (docs/features.md).
# Usage: features.sh status | on <key> | off <key>
# Keys: chat tv timers memory weather news fuel wikipedia jokes holidays
source "$(dirname "$0")/common.sh"
case "${1:-status}" in
  on|off) [ -n "$2" ] || { echo "usage: $0 on|off <key>"; exit 1; }; command="$1:$2" ;;
  status) command="status" ;;
  *) echo "usage: $0 status | on <key> | off <key>"; exit 1 ;;
esac
adb_ shell am start -n "$PKG/.ui.MainActivity" --es feature "$command" >/dev/null
sleep 2
adb_ shell cat "$DEVICE_FILES/logs/bello.log" | grep -E "FEATURE |unknown feature" | tail -10
