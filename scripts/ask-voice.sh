#!/usr/bin/env bash
# Full voice round trip: tap the face, then speak a French phrase from the Mac speaker.
# Usage: ask-voice.sh "Bonjour Bello, comment vas-tu ?" [macos-voice]
source "$(dirname "$0")/common.sh"
phrase="${1:?phrase}"; voice="${2:-Thomas}"
mkdir -p "$CACHE/audio"
file="$CACHE/audio/$(printf '%s' "$phrase$voice" | shasum | cut -c1-12).aiff"
[ -f "$file" ] || say -v "$voice" -o "$file" "$phrase"
adb_ shell am start -n "$PKG/.ui.MainActivity" --ez tap true >/dev/null
sleep 2.5           # let the recognizer start
afplay "$file"
sleep 6
adb_ shell cat "$DEVICE_FILES/logs/bello.log" | grep -E "assistant:|stt:|tts:" | tail -12
