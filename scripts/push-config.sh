#!/usr/bin/env bash
# Push the provider config (with the API keys) to the tablet and reload it in the app.
# Usage: scripts/push-config.sh [file]   (default: config/bello.local.json)
source "$(dirname "$0")/common.sh"
file="${1:-$ROOT/config/bello.local.json}"
[ -f "$file" ] || { echo "no $file — copy config/bello.example.json and add your keys"; exit 1; }
python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$file" || { echo "invalid JSON"; exit 1; }
adb_ push "$file" "$DEVICE_FILES/config.json" >/dev/null
echo "pushed $(basename "$file") → $DEVICE_FILES/config.json"
"$ROOT/scripts/llm.sh" reload
