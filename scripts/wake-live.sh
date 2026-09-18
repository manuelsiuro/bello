#!/usr/bin/env bash
# Measure the wake word with a real voice — yours — instead of a speaker.
#
#   wake-live.sh calls [n] [distance]   say "Bello" n times when prompted; prints detection
#   wake-live.sh room [minutes]         leave the TV or radio on and count false wakes
#
# Nothing is played from the Mac: the tablet listens to the room it lives in. Both modes write
# what the tablet heard to .cache/wake/, so scripts/wake-test.sh score can pick thresholds from it.
source "$(dirname "$0")/common.sh"
AUDIO="$CACHE/wake"
LOG="$DEVICE_FILES/logs/bello.log"
mkdir -p "$AUDIO"

log_lines() { adb_ shell cat "$LOG" | wc -l | tr -d ' '; }
since() { adb_ shell cat "$LOG" | awk -v n="$1" 'NR > n'; }

wait_listening() {
  for _ in $(seq 40); do
    case "$(adb_ shell cat "$LOG" | grep -Eo 'WAKE_(LISTENING|PAUSED|STOPPED|UNAVAILABLE)' | tail -1)" in
      WAKE_LISTENING) return 0 ;;
      WAKE_UNAVAILABLE|WAKE_STOPPED) echo "the wake word is not listening — scripts/wake.sh on" >&2; exit 1 ;;
    esac
    sleep 1
  done
  echo "timed out waiting for the wake word to listen" >&2; exit 1
}

calls() {
  local trials="${1:-10}" distance="${2:-}" hits=0
  local tag="${distance// /}"          # "1 m" and "2 m" are separate files, without the space
  local file="$AUDIO/positives-live${tag:+-$tag}.txt"
  : > "$file"
  echo "Say « Bello » once when prompted, then stay quiet. ${distance:+About $distance from the tablet. }"
  echo "Ctrl-C to stop."
  for i in $(seq "$trials"); do
    wait_listening
    local before; before=$(log_lines)
    printf '\n  %2d/%s  say « Bello » ... ' "$i" "$trials"
    sleep 4
    local new; new=$(since "$before")
    printf '%s\t%s\n' "call-$i" "$(grep -o 'WAKE_HEARD.*' <<<"$new" | head -1)" >> "$file"
    if grep -q "WAKE_OK" <<<"$new"; then
      hits=$((hits + 1))
      printf 'heard you  %s' "$(grep -o 'conf=.*' <<<"$new" | head -1)"
    else
      printf 'missed     %s' "$(grep -o 'WAKE_REJECT.*' <<<"$new" | head -1 || echo 'nothing heard at all')"
    fi
    # Let a wake finish its listening window before the next call.
    sleep 4
  done
  echo
  awk -v h="$hits" -v n="$trials" 'BEGIN { printf "\ndetection with a real voice: %d/%d (%.0f %%)\n", h, n, h * 100 / n }'
  echo "what the tablet heard: $file"
}

room() {
  local minutes="${1:-30}"
  wait_listening
  local before; before=$(log_lines)
  echo "Listening for $minutes min. Leave the television or radio on and carry on as usual;"
  echo "do not say « Bello ». Ctrl-C to stop early."
  local i=0
  while [ "$i" -lt "$minutes" ]; do
    sleep 60
    i=$((i + 1))
    local new; new=$(since "$before")
    printf '\r  %d min — false wakes so far: %s ' "$i" "$(grep -c 'WAKE_OK' <<<"$new" || true)"
  done
  echo
  local new; new=$(since "$before")
  { echo "# seconds=$((minutes * 60))"; grep -o 'WAKE_HEARD.*' <<<"$new" || true; } > "$AUDIO/negatives-live.txt"
  local wakes; wakes=$(grep -c "WAKE_OK" <<<"$new" || true)
  awk -v w="$wakes" -v m="$minutes" 'BEGIN {
    printf "false wakes in a real room: %d in %d min = %.1f / hour\n", w, m, w * 60 / m }'
  echo "what the tablet heard: $AUDIO/negatives-live.txt"
}

case "${1:-}" in
  calls) shift; calls "$@" ;;
  room) shift; room "$@" ;;
  *) sed -n '2,8p' "$0"; exit 1 ;;
esac
