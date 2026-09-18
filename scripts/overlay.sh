#!/usr/bin/env bash
# Show or hide the debug overlay on the face. Usage: scripts/overlay.sh on|off
source "$(dirname "$0")/common.sh"
adb_ shell am start -n "$PKG/.ui.MainActivity" --es overlay "${1:-on}" >/dev/null
