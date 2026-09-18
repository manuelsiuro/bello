#!/usr/bin/env bash
# Ask a question by text and print what Bello answered (from its own log).
source "$(dirname "$0")/common.sh"
msg="${1:?question}"
"$ROOT/scripts/text.sh" "$msg"
sleep "${2:-12}"
adb_ shell "cat $DEVICE_FILES/logs/bello.log" | grep -E "router:|assistant: answer|LLM_OK|LLM_FAIL" | tail -4
