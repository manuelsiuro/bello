#!/usr/bin/env python3
"""Score wake-word decision thresholds from the device's sp03 results log (gated run with permissive settings).

Usage: adb pull /sdcard/Android/data/com.bello.spikes/files/results/sp03.log results/sp03-app.log
       python3 tools/wake_score.py results/sp03-app.log
"""
import re
import sys

text = open(sys.argv[1], encoding="utf-8", errors="replace").read().splitlines()
# Use the last run only.
start = max(i for i, l in enumerate(text) if "CMD action=mark_trials" in l)
lines = text[start:]

WORD = re.compile(r"(\S+):([\d,\.]+)@([\d,\.]+)")


def hits(line):
    m = re.search(r"conf=\[(.*)\]", line)
    if not m:
        return []
    return [(w, float(c.replace(",", ".")), float(s.replace(",", "."))) for w, c, s in WORD.findall(m.group(1))]


trials, background, phase, current = [], [], None, []
for l in lines:
    if "CMD action=mark_background" in l:
        phase = "bg"
        continue
    if "CMD action=mark_end" in l:
        break
    if "CMD action=mark_file_done" in l:
        trials.append(current)
        current = []
        continue
    if " WAKE " in l or " CANDIDATE " in l or " FINAL " in l:
        ws = [h for h in hits(l) if h[0] == "bello"]
        if phase == "bg":
            background.extend(ws)
        else:
            current.extend(ws)

minutes = 15.4
cpu = [float(m.replace(",", ".")) for l in lines for m in re.findall(r"cpuProc=([\d,\.]+)%", l)]
bg_cpu = []
in_bg = False
for l in lines:
    if "mark_background" in l:
        in_bg = True
    if "mark_end" in l:
        break
    if in_bg:
        bg_cpu += [float(m.replace(",", ".")) for m in re.findall(r"cpuProc=([\d,\.]+)%", l)]

print(f"trials={len(trials)} backgroundBelloHits={len(background)} bgMinutes≈{minutes}")
print(f"{'minConf':>8} {'maxStart':>8} {'detected':>9} {'falseWakes':>11} {'FP/hour':>8}")
for min_conf in (0.8, 0.9, 0.95, 0.99):
    for max_start in (0.6, 0.8, 1.0, 1.5, 99):
        det = sum(1 for t in trials if any(c >= min_conf and s <= max_start for _, c, s in t))
        fp = sum(1 for _, c, s in background if c >= min_conf and s <= max_start)
        print(f"{min_conf:>8} {max_start:>8} {det:>5}/{len(trials):<3} {fp:>11} {fp / minutes * 60:>8.1f}")
if bg_cpu:
    print(f"CPU during background speech: avg={sum(bg_cpu) / len(bg_cpu):.1f}% max={max(bg_cpu):.1f}%")
print("trial bello hits (conf@start):")
for i, t in enumerate(trials):
    print(f"  {i + 1:>2}: {[(round(c, 2), round(s, 2)) for _, c, s in t]}")
print("background bello hits (conf@start):", sorted(((round(c, 2), round(s, 2)) for _, c, s in background), reverse=True)[:40])
