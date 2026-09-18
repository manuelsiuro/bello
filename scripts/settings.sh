#!/usr/bin/env bash
# The whole configuration as one file (FR-SET-04, 05).
#
#   settings.sh export [--keys]   write it on the tablet and copy it here (keys masked by default)
#   settings.sh import [FILE]     push a file to the tablet and apply it (default: the exported one)
#   settings.sh open              open the settings screen on the tablet
#   settings.sh status            print the device settings from the log
source "$(dirname "$0")/common.sh"
LOCAL="$ROOT/config/bello-export.json"
REMOTE="$DEVICE_FILES/bello-config-export.json"
send() { adb_ shell am start -n "$PKG/.ui.MainActivity" --es settings "$1" >/dev/null; }

case "${1:-status}" in
  export)
    with_keys=false
    [ "${2:-}" = "--keys" ] && with_keys=true
    $with_keys && send export-keys || send export
    sleep 2
    mkdir -p "$ROOT/config"
    adb_ pull "$REMOTE" "$LOCAL" >/dev/null
    # Trust nothing: look at what actually arrived, whichever spelling the keys use.
    if grep -qE '"(key|apiKey)"[[:space:]]*:[[:space:]]*"[^…"]' "$LOCAL"; then
      if $with_keys; then
        echo "$LOCAL"
        echo "this copy contains your API keys — it is git-ignored; do not send it to anyone"
      else
        rm -f "$LOCAL"
        echo "the export still contained keys although they were not asked for; deleted" >&2
        exit 1
      fi
    else
      echo "$LOCAL"
      echo "keys are masked in this copy"
    fi
    ;;
  import)
    file="${2:-$LOCAL}"
    [ -f "$file" ] || { echo "no such file: $file" >&2; exit 1; }
    adb_ push "$file" "$REMOTE" >/dev/null
    send import
    sleep 2
    adb_ shell cat "$DEVICE_FILES/logs/bello.log" | grep -E "SETTINGS import|CONFIG_APPLIED|config:" | tail -4
    ;;
  open) send open ;;
  status)
    send status
    sleep 1.5
    adb_ shell cat "$DEVICE_FILES/logs/bello.log" | grep "SETTINGS {" | tail -1
    ;;
  *) sed -n '2,8p' "$0"; exit 1 ;;
esac
