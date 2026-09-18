#!/usr/bin/env bash
# The seven-day unattended run (NFR-REL-01, acceptance criterion 12).
#
#   soak.sh start [days]   note where the log stands and when the run began
#   soak.sh report         what has happened since: crashes, restarts, network, CPU, memory, heat
#   soak.sh stop           end the run and print the final report
#
# Nothing here keeps the tablet busy: the point is to leave it alone and read its own log
# afterwards. Run `report` whenever you are curious; it does not disturb anything.
source "$(dirname "$0")/common.sh"
STATE="$ROOT/.cache/soak.json"
LOG="$DEVICE_FILES/logs/bello.log"

case "${1:-report}" in
  start)
    mkdir -p "$ROOT/.cache"
    days="${2:-7}"
    started=$(date +%s)
    lines=$(adb_ shell cat "$LOG" | wc -l | tr -d ' ')
    uptime=$(adb_ shell cat /proc/uptime | awk '{print int($1)}')
    printf '{"started":%s,"days":%s,"lines":%s,"deviceUptime":%s}\n' "$started" "$days" "$lines" "$uptime" > "$STATE"
    echo "soak started $(date '+%Y-%m-%d %H:%M') for $days days; device up ${uptime}s, log at $lines lines"
    echo "leave the tablet where it lives; run scripts/soak.sh report whenever you like"
    ;;
  report|stop)
    [ -f "$STATE" ] || { echo "no soak running — scripts/soak.sh start" >&2; exit 1; }
    started=$(python3 -c "import json;print(json.load(open('$STATE'))['started'])")
    lines=$(python3 -c "import json;print(json.load(open('$STATE'))['lines'])")
    days=$(python3 -c "import json;print(json.load(open('$STATE'))['days'])")
    now=$(date +%s)
    hours=$(( (now - started) / 3600 ))
    since=$(adb_ shell cat "$LOG" | awk -v n="$lines" 'NR > n')
    count() { grep -c "$1" <<<"$since" | tr -d ' '; }
    echo "=== soak: ${hours} h of ${days} days ($(( hours * 100 / (days * 24) ))%) ==="
    echo "device uptime now: $(adb_ shell cat /proc/uptime | awk '{printf "%.1f h", $1/3600}')"
    printf 'crashes: %s · activity restarts: %s · service starts: %s\n' \
      "$(count 'CRASH')" "$(count 'created reason=')" "$(count 'service created')"
    printf 'network lost: %s · back: %s · provider failures: %s · answers: %s\n' \
      "$(count 'NETWORK_LOST')" "$(count 'NETWORK_BACK')" "$(count 'LLM_FAIL')" "$(count 'LLM_OK')"
    printf 'wakes: %s · alarms rung: %s · nights: %s\n' \
      "$(count 'WAKE_OK')" "$(count 'ringing:')" "$(count 'NIGHT=true')"
    grep "PERF " <<<"$since" | awk '{ line=$0; gsub(",",".",line); split(line,f," ")
        for (i in f) { split(f[i],kv,"=")
          if (kv[1]=="cpuProc") { c+=kv[2]; if (kv[2]+0>cm) cm=kv[2]+0 }
          if (kv[1]=="tempC")   { t+=kv[2]; if (kv[2]+0>tm) tm=kv[2]+0 }
          if (kv[1]=="pssMB")   { p+=kv[2]; if (kv[2]+0>pm) pm=kv[2]+0 } }
        n++ }
      END { if (n) printf "cpu avg %.1f%% (max %.0f%%) · temp avg %.1f C (max %.1f) · memory avg %.0f MB (max %.0f) over %d samples\n",
        c/n, cm, t/n, tm, p/n, pm, n }'
    grep -o "battery=[0-9]*[+-]" <<<"$since" | sort -u | tr '\n' ' ' | sed 's/^/battery levels seen: /; s/$/\n/'
    warn=$(grep -E " W/| E/" <<<"$since" | grep -vE "WAKE_REJECT|http=|request failed" | tail -5)
    [ -n "$warn" ] && { echo "--- last warnings ---"; echo "$warn"; }
    if [ "${1}" = "stop" ]; then
      rm -f "$STATE"
      echo "soak ended after ${hours} h"
    fi
    ;;
  *) sed -n '2,8p' "$0"; exit 1 ;;
esac
