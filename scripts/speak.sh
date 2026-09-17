#!/usr/bin/env bash
# Make Bello say a sentence out loud (tests the voice without asking a question).
source "$(dirname "$0")/common.sh"
msg="${1:?text}"
adb_ shell am start -n "$PKG/.ui.MainActivity" --es speak "'${msg//\'/\'\\\'\'}'" >/dev/null
