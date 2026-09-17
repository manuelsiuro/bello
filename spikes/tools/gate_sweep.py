#!/usr/bin/env python3
"""Sweep gate pre-roll / onset settings on the tablet using all wake samples."""
import glob, os, sys, time
sys.path.insert(0, os.path.dirname(__file__))
from spike import cmd, logs, wait_for, clear, play, AUDIO_DIR

files = sorted(glob.glob(os.path.join(AUDIO_DIR, "wake", "*.wav")))
for spec in sys.argv[1:]:
    pre, onset = spec.split(":")
    clear()
    cmd("sp03", "start", grammar='["bello", "[unk]"]', keyword="bello", minConf="0.0", gated=True,
        preRoll=int(pre), onsetDb=onset)
    wait_for("sp03", "LISTENING|START_ERROR", 120)
    time.sleep(3)
    confs = []
    for f in files:
        k = len(logs("sp03"))
        play(f)
        r = wait_for("sp03", r"^(WAKE|CANDIDATE)", 4, k)
        c = 0.0
        if r and r.startswith("WAKE"):
            c = float(r.split("bello:")[1][:4].replace(",", "."))
        confs.append((c, os.path.basename(f), r))
        time.sleep(1)
    for th in (0.9, 0.95):
        print(f"preRoll={pre}00ms onset={onset}dB th={th}: detected={sum(1 for c, _, _ in confs if c >= th)}/{len(files)}", flush=True)
    print("   low:", [(n, c) for c, n, _ in confs if c < 0.95], flush=True)
