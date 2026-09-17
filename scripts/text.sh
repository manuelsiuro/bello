#!/usr/bin/env bash
# Send a typed question to Bello, as if written in the text box.
source "$(dirname "$0")/common.sh"
msg="${1:?text}"
adb_ shell am start -n "$PKG/.ui.MainActivity" --es text "'${msg//\'/\'\\\'\'}'" >/dev/null
