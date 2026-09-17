#!/usr/bin/env bash
# Set the Minion voice: voice.sh <pitch> <rate>   (defaults 1.6 / 1.05)
source "$(dirname "$0")/common.sh"
adb_ shell am start -n "$PKG/.ui.MainActivity" --es voice "${1:?pitch},${2:?rate}" >/dev/null
