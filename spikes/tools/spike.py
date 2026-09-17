#!/usr/bin/env python3
"""Host-side orchestration for Bello feasibility spikes (runs on the Mac)."""
import os
import re
import subprocess
import sys
import time
import unicodedata

SERIAL = "e3572b180497ec75"
PKG = "com.bello.spikes/.MainActivity"
VOICE = "Thomas"  # macOS French (France) voice


def adb(*args, check=False):
    return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, errors="replace", check=check).stdout


def cmd(spike, action, **extras):
    args = ["shell", "am", "start", "-n", PKG, "--es", "spike", spike, "--es", "action", action]
    for k, v in extras.items():
        if isinstance(v, bool):
            args += ["--ez", k, "true" if v else "false"]
        elif isinstance(v, int):
            args += ["--ei", k, str(v)]
        else:
            args += ["--es", k, "'" + str(v).replace("'", "'\\''") + "'"]
    adb(*args)


def logs(spike):
    out = adb("logcat", "-d", "-v", "raw", "-s", "BelloSpike:I")
    return [l.split("] ", 1)[1] for l in out.splitlines() if l.startswith(f"[{spike}]")]


def clear():
    adb("logcat", "-c")


def wait_for(spike, pattern, timeout, since=0):
    rx = re.compile(pattern)
    end = time.time() + timeout
    while time.time() < end:
        lines = logs(spike)[since:]
        for l in lines:
            if rx.search(l):
                return l
        time.sleep(0.3)
    return None


AUDIO_DIR = os.path.join(os.path.dirname(__file__), "..", "results", "audio")


def say(text, rate=None):
    """Pre-render with `say` then play with afplay so playback starts instantly."""
    os.makedirs(AUDIO_DIR, exist_ok=True)
    name = re.sub(r"[^a-z0-9]+", "_", " ".join(norm(text)))[:60] + (f"_{rate}" if rate else "") + ".aiff"
    path = os.path.join(AUDIO_DIR, name)
    if not os.path.exists(path):
        subprocess.run(["say", "-v", VOICE, "-o", path] + (["-r", str(rate)] if rate else []) + [text])
    subprocess.run(["afplay", path])


def play(path, timeout=30):
    """afplay with a timeout (macOS CoreAudio occasionally hangs afplay)."""
    try:
        subprocess.run(["afplay", path], timeout=timeout)
    except subprocess.TimeoutExpired:
        print(f"WARN afplay timeout on {os.path.basename(path)}", flush=True)


def norm(s):
    s = unicodedata.normalize("NFD", s.lower())
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    return re.sub(r"[^a-z0-9 ]+", " ", s.replace("'", " ")).split()


def wer(ref, hyp):
    r, h = norm(ref), norm(hyp)
    d = [[0] * (len(h) + 1) for _ in range(len(r) + 1)]
    for i in range(len(r) + 1):
        d[i][0] = i
    for j in range(len(h) + 1):
        d[0][j] = j
    for i in range(1, len(r) + 1):
        for j in range(1, len(h) + 1):
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (r[i - 1] != h[j - 1]))
    return d[len(r)][len(h)] / max(1, len(r))


PHRASES = [
    "Quel temps fera-t-il demain à Grasse ?",
    "Mets un minuteur de dix minutes.",
    "Quelle heure est-il ?",
    "Raconte-moi une blague sur les bananes.",
    "Quelles sont les dernières nouvelles en France ?",
    "Souviens-toi que mon café préféré est l'espresso.",
    "Réveille-moi demain à sept heures trente.",
    "Combien font douze fois quinze ?",
    "Qui a écrit Les Misérables ?",
    "Arrête le minuteur s'il te plaît.",
]


def sp01(offline=False):
    clear()
    cmd("sp01", "info")
    time.sleep(1)
    for l in logs("sp01"):
        print(l)
    rows = []
    for phrase in PHRASES:
        n = len(logs("sp01"))
        cmd("sp01", "listen", offline=offline)
        ready = wait_for("sp01", r"READY|ERROR", 8, n)
        if not ready or ready.startswith("ERROR"):
            rows.append((phrase, None, ready or "no READY"))
            print(f"✗ {phrase} -> {ready}")
            continue
        time.sleep(0.4)
        say(phrase)
        res = wait_for("sp01", r"RESULT|ERROR", 15, n)
        if res and res.startswith("RESULT"):
            text = res.split("text=", 1)[1].split(" alts=")[0]
            lat = re.search(r"latencyMs=(-?\d+)", res).group(1)
            w = wer(phrase, text)
            rows.append((phrase, text, f"wer={w:.2f} latencyMs={lat}"))
            print(f"{'✓' if w <= 0.25 else '~'} wer={w:.2f} lat={lat}ms | {phrase} -> {text}")
        else:
            rows.append((phrase, None, res))
            print(f"✗ {phrase} -> {res}")
        time.sleep(1)
    ok = [r for r in rows if r[1] is not None]
    avg = sum(wer(p, t) for p, t, _ in ok) / max(1, len(ok))
    print(f"SUMMARY recognized={len(ok)}/{len(rows)} avgWER={avg:.2f}")


def _voice_file(text, voice):
    os.makedirs(AUDIO_DIR, exist_ok=True)
    path = os.path.join(AUDIO_DIR, re.sub(r"[^a-z0-9]+", "_", " ".join(norm(text)))[:50] + f"_{norm(voice)[0]}.aiff")
    if not os.path.exists(path):
        subprocess.run(["say", "-v", voice, "-o", path, text])
    return path


def say_voice(text, voice):
    subprocess.run(["afplay", _voice_file(text, voice)])


WAKE_UTTERANCES = [
    ("Bello", "Thomas"), ("Bello !", "Amélie"), ("Bello", "Jacques"), ("Bello, quelle heure est-il ?", "Thomas"),
    ("Bello", "Flo (French (France))"), ("Bello, mets un minuteur.", "Amélie"), ("Bello", "Sandy (French (France))"),
    ("Bello, quel temps fait-il ?", "Jacques"), ("Bello", "Eddy (French (France))"), ("Bello", "Thomas"),
]


def sp03_wake(gap=5.0):
    """Play every wake sample (results/audio/wake/*.wav); count WAKE events per sample."""
    import glob
    files = sorted(glob.glob(os.path.join(AUDIO_DIR, "wake", "*.wav")))
    hits, delays = 0, []
    for f in files:
        n = len(logs("sp03"))
        play(f)
        t_end = time.time()
        r = wait_for("sp03", r"^WAKE", gap, n)
        if r:
            hits += 1
            delays.append((time.time() - t_end) * 1000)
        other = [l for l in logs("sp03")[n:] if l.startswith(("CANDIDATE", "FINAL"))]
        print(f"{'✓' if r else '✗'} {os.path.basename(f)} -> {r or other}", flush=True)
        time.sleep(1.0)
    delays.sort()
    print(f"SUMMARY wake detected={hits}/{len(files)} medianDetectAfterAudioEndMs={delays[len(delays)//2] if delays else -1:.0f} "
          f"maxMs={max(delays) if delays else -1:.0f}", flush=True)


def sp03_background(files):
    """Play background French speech; any WAKE is a false trigger."""
    n = len(logs("sp03"))
    t0 = time.time()
    for f in files:
        play(os.path.join(AUDIO_DIR, f), timeout=900)
    minutes = (time.time() - t0) / 60
    lines = logs("sp03")[n:]
    wakes = [l for l in lines if l.startswith("WAKE")]
    for l in wakes:
        print(l)
    print(f"SUMMARY background minutes={minutes:.1f} falseWakes={len(wakes)} "
          f"rate={len(wakes) / minutes * 60:.1f}/hour finals={sum(1 for l in lines if l.startswith('FINAL'))}", flush=True)


if __name__ == "__main__":
    fn = sys.argv[1]
    args = sys.argv[2:]
    if fn == "sp01":
        sp01(offline="offline" in args)
    elif fn == "sp03_wake":
        sp03_wake()
    elif fn == "sp03_background":
        sp03_background(args)


GEMINI_PROMPTS = [
    "Quel temps fait-il à Grasse aujourd'hui ?",
    "Donne-moi les trois principaux titres de l'actualité en France aujourd'hui.",
    "Raconte-moi une blague courte sur les bananes.",
    "Combien font 12 fois 15 ?",
    "Qui a écrit Les Misérables ? Une phrase.",
    "En quelle année Les Misérables de Victor Hugo a-t-il été publié ?",
    "Donne une liste de 3 conseils pour bien dormir.",
    "Traduis 'good morning' en espagnol.",
    "Quelle heure est-il à Tokyo maintenant ?",
    "Explique en deux phrases ce qu'est un trou noir.",
]


def sp02(fresh=True, new_chat=False):
    clear()
    cmd("sp02", "load")
    wait_for("sp02", "PAGE_FINISHED", 60)
    time.sleep(3)
    ok, times = 0, []
    for i, p in enumerate(GEMINI_PROMPTS, 1):
        n = len(logs("sp02"))
        cmd("sp02", "ask", prompt=p, fresh=fresh, newChat=new_chat)
        r = wait_for("sp02", r"ANSWER_", 90, n)
        if r and r.startswith("ANSWER_OK"):
            ok += 1
            ms = int(re.search(r"ms=(\d+)", r).group(1))
            times.append(ms)
            print(f"✓ {i} {ms}ms | {p}\n    -> {r.split('text=', 1)[1][:500]}", flush=True)
        else:
            js = [l for l in logs("sp02")[n:] if l.startswith("JS")]
            print(f"✗ {i} | {p} -> {r} {js}", flush=True)
        time.sleep(2)
    times.sort()
    print(f"SUMMARY fresh={fresh} newChat={new_chat} ok={ok}/{len(GEMINI_PROMPTS)} medianMs={times[len(times) // 2] if times else -1} "
          f"maxMs={max(times) if times else -1}", flush=True)


if __name__ == "__main__" and sys.argv[1] == "sp02":
    sp02(fresh="reload" in sys.argv, new_chat="newchat" in sys.argv)
