#!/usr/bin/env bash
# Provider status or config reload. Usage: scripts/llm.sh status|reload
source "$(dirname "$0")/common.sh"
cmd="${1:-status}"
adb_ shell am start -n "$PKG/.ui.MainActivity" --es llm "$cmd" >/dev/null
sleep 2
adb_ shell "cat $DEVICE_FILES/logs/bello.log" 2>/dev/null | grep -E "LLM_STATUS|LLM_RELOADED|llm: " | tail -12
