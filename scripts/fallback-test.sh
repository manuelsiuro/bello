#!/usr/bin/env bash
# Acceptance criterion 3, without spending any free quota: two fake providers on the Mac, reached
# through `adb reverse`. The first always answers 429, the second answers normally.
# Checks that the tablet falls through to the second and then skips the first while it cools down.
source "$(dirname "$0")/common.sh"
LOG="$DEVICE_FILES/logs/bello.log"
BAD=8099
GOOD=8098
mkdir -p "$CACHE"

cleanup() {
  kill ${PIDS:-} 2>/dev/null || true
  adb_ reverse --remove tcp:$BAD 2>/dev/null || true
  adb_ reverse --remove tcp:$GOOD 2>/dev/null || true
  if [ -s "$CACHE/config.backup.json" ]; then
    adb_ push "$CACHE/config.backup.json" "$DEVICE_FILES/config.json" >/dev/null
    echo "restored the previous config.json"
  else
    adb_ shell rm -f "$DEVICE_FILES/config.json"
  fi
  adb_ shell am start -n "$PKG/.ui.MainActivity" --es llm reload >/dev/null
}
trap cleanup EXIT

# Keep the real config (if any) to put back at the end.
adb_ shell "[ -f $DEVICE_FILES/config.json ] && echo yes" | grep -q yes \
  && adb_ pull "$DEVICE_FILES/config.json" "$CACHE/config.backup.json" >/dev/null \
  || : > "$CACHE/config.backup.json"

python3 "$ROOT/scripts/tools/fake_llm.py" $BAD --status 429 --retry-after 60 &
PIDS="$!"
python3 "$ROOT/scripts/tools/fake_llm.py" $GOOD --text "[happy] Je suis le deuxieme cerveau." &
PIDS="$PIDS $!"
sleep 1
adb_ reverse tcp:$BAD tcp:$BAD >/dev/null
adb_ reverse tcp:$GOOD tcp:$GOOD >/dev/null

cat > "$CACHE/config.fallback.json" <<JSON
{ "cooldownMs": 60000, "timeoutMs": 8000,
  "providers": [
    { "id": "fake-429", "baseUrl": "http://127.0.0.1:$BAD/v1", "key": "x", "model": "fake" },
    { "id": "fake-ok",  "baseUrl": "http://127.0.0.1:$GOOD/v1", "key": "x", "model": "fake" }
  ] }
JSON
adb_ push "$CACHE/config.fallback.json" "$DEVICE_FILES/config.json" >/dev/null
adb_ shell am start -n "$PKG/.ui.MainActivity" --es llm reload >/dev/null
sleep 3

mark=$(adb_ shell "cat $LOG" | wc -l | tr -d ' ')
echo "--- question 1: expect fake-429 to fail, fake-ok to answer"
"$ROOT/scripts/text.sh" "Premiere question"
sleep 8
echo "--- question 2: expect fake-429 to be skipped (cooldown)"
"$ROOT/scripts/text.sh" "Deuxieme question"
sleep 8

adb_ shell "cat $LOG" | awk -v n="$mark" 'NR > n' | grep -E "LLM_OK|LLM_FAIL|LLM_NONE" | sed 's/^.*llm: //'
