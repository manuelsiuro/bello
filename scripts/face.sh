#!/usr/bin/env bash
# Set the face state: idle listening thinking speaking happy confused sad alert sleepy
source "$(dirname "$0")/common.sh"
adb_ shell am start -n "$PKG/.ui.MainActivity" --es state "${1:?state}" >/dev/null
