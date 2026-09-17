#!/usr/bin/env python3
"""Run the 26 wake samples against a gated config; print per-trial bello conf@relativeStart."""
import glob, os, re, sys, time
sys.path.insert(0, os.path.dirname(__file__))
from spike import cmd, logs, wait_for, clear, play, AUDIO_DIR

reset = "noreset" not in sys.argv
pre = int(next((a.split("=")[1] for a in sys.argv if a.startswith("pre=")), "2"))
clear()
cmd("sp03", "start", grammar='["bello", "[unk]"]', keyword="bello", minConf="0.0", gated=True, preRoll=pre,
    onsetDb="9.0", maxStartSec="999", reset=reset)
wait_for("sp03", "LISTENING|START_ERROR", 120)
time.sleep(3)
rows = []
for f in sorted(glob.glob(os.path.join(AUDIO_DIR, "wake", "*.wav"))):
    k = len(logs("sp03"))
    play(f)
    wait_for("sp03", r"^(WAKE|CANDIDATE)", 4, k)
    time.sleep(1.5)
    new = logs("sp03")[k:]
    hits = [(float(c.replace(",", ".")), float(s.replace(",", "."))) for l in new if l.startswith(("WAKE", "CANDIDATE"))
            for c, s in re.findall(r"bello:([\d,\.]+)@(-?[\d,\.]+)", l)]
    rows.append(hits)
    print(os.path.basename(f), hits, [l for l in new if l.startswith("ONSET")][:2], flush=True)
for th in (0.8, 0.9, 0.95):
    for ms in (0.8, 1.2, 99):
        print(f"reset={reset} pre={pre} conf>={th} relStart<={ms}: {sum(1 for h in rows if any(c >= th and s <= ms for c, s in h))}/{len(rows)}")
