#!/usr/bin/env python3
"""Score wake-word thresholds against what the tablet actually heard.

Input: the WAKE_HEARD lines collected by scripts/wake-test.sh — every "bello" the recogniser
found, with its confidence, its start relative to the onset of speech, and the silence after it.
One trial per line for the positives; the negatives file carries the length of the recording.

    wake_score.py positives.txt negatives.txt

Because the numbers come from the tablet's own microphone in the real room, a rule chosen here is
a rule that would have fired there.
"""
import re
import sys

CANDIDATE = re.compile(r"conf=([\d.]+) start=(-?[\d.]+) gap=([\d.]+)")


def read(path):
    """-> (list of trials, each a list of (conf, start, gap)), seconds of recording if stated."""
    trials, seconds = [], 0.0
    for line in open(path, encoding="utf-8", errors="replace"):
        if line.startswith("# seconds="):
            seconds = float(line.split("=")[1])
            continue
        if line.startswith("#"):
            continue
        trials.append([(float(c), float(s), float(g)) for c, s, g in CANDIDATE.findall(line)])
    return trials, seconds


def fires(trial, min_conf, lo, hi, gap):
    return any(c >= min_conf and lo <= s <= hi and g >= gap for c, s, g in trial)


if __name__ == "__main__":
    positives, _ = read(sys.argv[1])
    negatives, seconds = read(sys.argv[2]) if len(sys.argv) > 2 else ([], 0.0)
    hours = seconds / 3600 if seconds else 0
    heard = sum(1 for t in positives if t)
    print(f"{len(positives)} utterances ({heard} where 'bello' was heard at all), "
          f"{sum(len(t) for t in negatives)} candidates in {seconds / 60:.1f} min of speech\n")
    print(f"{'conf':>6} {'window':>14} {'gap':>5} {'detected':>10} {'false':>7} {'per hour':>9}")
    for min_conf in (0.60, 0.70, 0.80, 0.85, 0.90, 0.95, 0.99):
        for lo, hi in ((0.10, 0.35), (0.05, 0.45), (0.00, 0.60)):
            for gap in (0.50, 0.35):
                det = sum(1 for t in positives if fires(t, min_conf, lo, hi, gap))
                fp = sum(1 for t in negatives if fires(t, min_conf, lo, hi, gap))
                rate = f"{fp / hours:8.1f}" if hours else "       ?"
                print(f"{min_conf:>6.2f} {f'[{lo:.2f},{hi:.2f}]':>14} {gap:>5.2f} "
                      f"{f'{det}/{len(positives)}':>10} {fp:>7} {rate:>9}")
    if positives:
        print("\nheard per utterance (conf@start, gap):")
        for trial in positives:
            print("  " + (", ".join(f"{c:.2f}@{s:.2f} gap {g:.2f}" for c, s, g in trial) or "— nothing"))
