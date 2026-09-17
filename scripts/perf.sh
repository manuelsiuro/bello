#!/usr/bin/env bash
# Summarise PERF lines from the app log: last N samples (default 20).
source "$(dirname "$0")/common.sh"
n="${1:-20}"
adb_ shell cat "$DEVICE_FILES/logs/bello.log" | grep "PERF " | tail -n "$n" | awk '
  { for (i = 1; i <= NF; i++) { split($i, kv, "="); if (kv[2] != "") v[kv[1]] += kv[2]; if (kv[1] == "cpuProc" && kv[2] + 0 > max) max = kv[2] + 0 } c++ ; print }
  END { if (c) printf "samples=%d avg cpuProc=%.1f%% (max %.1f%%) cpuSys=%.1f%% tempC=%.1f pssMB=%.0f\n", c, v["cpuProc"]/c, max, v["cpuSys"]/c, v["tempC"]/c, v["pssMB"]/c }'
