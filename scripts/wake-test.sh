#!/usr/bin/env bash
# Measure the wake word on the tablet, from the Mac speaker.
#
#   wake-test.sh make             render fresh "Bello" utterances (voices unseen in SP-03)
#   wake-test.sh background [min] render fresh French speech (today's news, random articles)
#   wake-test.sh detect [volume]  play each utterance, count the wakes (detection rate)
#   wake-test.sh noise [FILE...]  play long speech, count the wakes (false wakes per hour)
#   wake-test.sh score            sweep thresholds over what the tablet heard in both runs
#
# The tablet must be near the speaker, the room quiet for `detect`, and the wake word on.
# The Mac's own output level is part of the instrument — a run at half volume measures nothing —
# so it is set here and restored afterwards.
source "$(dirname "$0")/common.sh"
AUDIO="$CACHE/wake"
LOG="$DEVICE_FILES/logs/bello.log"

speaker() { osascript -e "set volume output volume ${1:?}" ; }
restore_speaker() { speaker "$WAS_VOLUME"; }
loud() {
  WAS_VOLUME=$(osascript -e 'output volume of (get volume settings)')
  trap restore_speaker EXIT
  speaker "${SPEAKER_VOLUME:-100}"
}

log_lines() { adb_ shell cat "$LOG" | wc -l | tr -d ' '; }
since() { adb_ shell cat "$LOG" | awk -v n="$1" 'NR > n'; }
seconds_of() { afinfo "$1" | awk -F': ' '/estimated duration/ {printf "%.0f", $2}'; }

# The wake word holds the microphone only between conversations; a trial is only valid once the
# previous one has let go of it.
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

make_audio() {
  mkdir -p "$AUDIO"
  # Voices and phrasings that were not used to design the rule (SP-03 used Thomas, Amelie, Eddy,
  # Flo, Jacques, Sandy), so the start-window rule is judged on material it has not seen.
  local i=0
  for voice in Grandma Grandpa Rocko Shelley; do
    for phrase in "Bello" "Bello, quelle heure est-il ?" "Bello, mets un minuteur de trois minutes" \
                  "Bello ! Quel temps fait-il aujourd'hui ?"; do
      i=$((i + 1))
      say -v "$voice" -o "$AUDIO/$(printf '%02d' $i)_${voice}.aiff" "$phrase"
    done
    i=$((i + 1))
    say -v "$voice" -r 210 -o "$AUDIO/$(printf '%02d' $i)_${voice}_vite.aiff" "Bello, raconte-moi une blague"
  done
  echo "rendered $i utterances in $AUDIO"
}

# Loud, continuous French from material the rule has never seen — the situation that produced
# 4.3 false wakes an hour in SP-03.
background() {
  local minutes="${1:-15}" part=1
  mkdir -p "$AUDIO"
  rm -f "$AUDIO"/background_*.aiff
  python3 "$ROOT/scripts/tools/french_text.py" $((minutes * 170)) > "$AUDIO/background.txt"
  # Three voices, so no single synthetic timbre decides the result.
  for voice in Thomas Jacques Sandy; do
    awk -v part="$part" -v n=3 'NR == 1 {
        c = split($0, w, " "); from = int((part - 1) * c / n) + 1; to = int(part * c / n)
        for (i = from; i <= to; i++) printf "%s ", w[i]
      }' "$AUDIO/background.txt" > "$AUDIO/background_$part.txt"
    say -v "$voice" -f "$AUDIO/background_$part.txt" -o "$AUDIO/background_$part.aiff"
    printf '  %-20s %s s\n' "background_$part.aiff" "$(seconds_of "$AUDIO/background_$part.aiff")"
    part=$((part + 1))
  done
}

detect() {
  local volume="${1:-1}" hits=0 total=0
  loud
  [ -d "$AUDIO" ] || { echo "no audio yet — run: scripts/wake-test.sh make" >&2; exit 1; }
  : > "$AUDIO/positives.txt"
  for file in "$AUDIO"/[0-9]*.aiff; do
    wait_listening
    local before; before=$(log_lines)
    afplay -v "$volume" "$file"
    sleep 4
    local new; new=$(since "$before")
    total=$((total + 1))
    # One line per utterance, even when nothing was heard: that is a miss to account for.
    printf '%s\t%s\n' "$(basename "$file")" "$(grep -o 'WAKE_HEARD.*' <<<"$new" | head -1)" \
      >> "$AUDIO/positives.txt"
    if grep -q "WAKE_OK" <<<"$new"; then
      hits=$((hits + 1))
      printf '  hit  %-30s %s\n' "$(basename "$file")" "$(grep -o 'WAKE_OK.*' <<<"$new" | head -1)"
    else
      printf '  MISS %-30s %s\n' "$(basename "$file")" "$(grep -o 'WAKE_REJECT.*' <<<"$new" | head -1)"
    fi
  done
  echo "detection: $hits/$total at volume $volume (what was heard: $AUDIO/positives.txt)"
}

noise() {
  local files=("$@")
  loud
  [ ${#files[@]} -gt 0 ] || files=("$AUDIO"/background_[0-9].aiff)
  local total=0 wakes=0
  : > "$AUDIO/negatives.raw"
  for file in "${files[@]}"; do
    wait_listening
    local before; before=$(log_lines)
    local seconds; seconds=$(seconds_of "$file")
    echo "playing $(basename "$file") — ${seconds}s of speech; wakes should be rare"
    afplay "$file"
    sleep 4
    local new; new=$(since "$before")
    grep -o 'WAKE_HEARD.*' <<<"$new" >> "$AUDIO/negatives.raw" || true
    local n; n=$(grep -c "WAKE_OK" <<<"$new" || true)
    grep -o "WAKE_OK.*" <<<"$new" || true
    wakes=$((wakes + n)); total=$((total + seconds))
  done
  { echo "# seconds=$total"; cat "$AUDIO/negatives.raw"; } > "$AUDIO/negatives.txt"
  rm -f "$AUDIO/negatives.raw"
  awk -v w="$wakes" -v s="$total" 'BEGIN {
    printf "false wakes: %d in %.1f min = %.1f / hour\n", w, s / 60, w * 3600 / s }'
  echo "what was heard: $AUDIO/negatives.txt"
}

score() {
  python3 "$ROOT/scripts/tools/wake_score.py" "$AUDIO/positives.txt" "$AUDIO/negatives.txt"
}

case "${1:-}" in
  make) make_audio ;;
  background) shift; background "$@" ;;
  detect) shift; detect "$@" ;;
  noise) shift; noise "$@" ;;
  score) score ;;
  *) sed -n '2,9p' "$0"; exit 1 ;;
esac
