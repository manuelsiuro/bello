#!/usr/bin/env bash
# Debug builds only: make the app crash and check that it comes back by itself.
source "$(dirname "$0")/common.sh"
focus() { adb_ shell dumpsys window windows | grep mCurrentFocus | tr -d '\r' | sed 's/.*u0 //;s/}.*//'; }
adb_ shell am start -n "$PKG/.ui.MainActivity" --ez crash true >/dev/null
sleep 3
echo "just after crash: $(focus)"
for i in $(seq 1 20); do
  sleep 5
  f="$(focus)"
  if printf '%s' "$f" | grep -q "$PKG/$PKG.ui.MainActivity"; then echo "recovered after ~$((i * 5))s: $f"; exit 0; fi
  printf '%s' "$f" | grep -q "Application Error" && echo "  system crash dialog is showing"
done
echo "not recovered: $(focus)"; exit 1
