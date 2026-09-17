#!/usr/bin/env bash
# Run the on-device self-check and print its results from the app log file.
source "$(dirname "$0")/common.sh"
log() { adb_ shell cat "$DEVICE_FILES/logs/bello.log" 2>/dev/null || true; }
runs() { log | grep -c "SELFCHECK_DONE" || true; }

before="$(runs)"
adb_ shell am start -n "$PKG/.ui.MainActivity" --ez selfcheck true >/dev/null
for _ in $(seq 1 90); do
  sleep 1
  [ "$(runs)" -gt "$before" ] && break
done

out="$(log)"
# Checks of the most recent run: lines after the previous SELFCHECK_DONE.
printf '%s\n' "$out" | awk '/SELFCHECK_DONE/ { n++ } { lines[NR] = $0; run[NR] = n }
  END { for (i = 1; i <= NR; i++) if ((run[i] == n - 1 && lines[i] ~ /CHECK /) || (run[i] == n && lines[i] ~ /SELFCHECK_DONE/)) print lines[i] }'
result="$(printf '%s\n' "$out" | grep "SELFCHECK_DONE" | tail -1 | sed -E 's/.*ok=([0-9]+)\/([0-9]+).*/\1 \2/')"
if [ "$(runs)" -gt "$before" ] && [ "${result% *}" = "${result#* }" ]; then
  echo "SELF-CHECK PASSED ($result)"
else
  echo "SELF-CHECK FAILED (${result:-no result})"
  exit 1
fi
