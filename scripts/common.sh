#!/usr/bin/env bash
# Shared settings for Bello helper scripts (run on the Mac).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${ANDROID_SERIAL:-e3572b180497ec75}"
PKG="com.bello.assistant"
DEVICE_FILES="/sdcard/Android/data/$PKG/files"
MODEL_NAME="vosk-model-small-fr-0.22"
MODEL_URL="https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
CACHE="$ROOT/.cache"
adb_() { adb -s "$SERIAL" "$@"; }
java17() { /usr/libexec/java_home -v 17; }
