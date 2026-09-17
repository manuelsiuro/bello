#!/usr/bin/env bash
# Download (once) and push the Vosk French model to the app's external files dir.
source "$(dirname "$0")/common.sh"
mkdir -p "$CACHE"
if [ ! -d "$CACHE/$MODEL_NAME" ]; then
  curl -fsSL -o "$CACHE/$MODEL_NAME.zip" "$MODEL_URL"
  unzip -q -o "$CACHE/$MODEL_NAME.zip" -d "$CACHE"
  rm "$CACHE/$MODEL_NAME.zip"
fi
if [ "$(adb_ shell "[ -f $DEVICE_FILES/$MODEL_NAME/am/final.mdl ] && echo yes" | tr -d '\r')" = "yes" ]; then
  echo "Model already on device: $DEVICE_FILES/$MODEL_NAME"
else
  adb_ shell mkdir -p "$DEVICE_FILES"
  adb_ push "$CACHE/$MODEL_NAME" "$DEVICE_FILES/"
fi
