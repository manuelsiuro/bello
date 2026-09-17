#!/usr/bin/env python3
"""Offline evaluation of Vosk wake-word grammars and decision rules on the Mac.

Positives: results/audio/wake/*.wav   Negatives: background WAVs (French speech).
Same model as the tablet (vosk-model-small-fr-0.22), so grammar/rule comparisons carry over.
"""
import glob
import json
import os
import sys
import wave

from vosk import KaldiRecognizer, Model, SetLogLevel

ROOT = os.path.join(os.path.dirname(__file__), "..")
AUDIO = os.path.join(ROOT, "results", "audio")
MODEL = os.path.join(ROOT, "assets-download", "vosk-model-small-fr-0.22")
KEYWORDS = set(os.environ.get("WAKE_KEYWORDS", "bello").split(","))

DECOYS = """belle bel beau beaux bella bellot belote bateau vélo vélos hello allo bébé bol boulot ballot billet bulle
mélo bouleau bureau barreau berceau pelle pelot hublot bello""".split()

COMMON = """le la les un une des du de d l et ou mais donc car ni que qui quoi dont où ce cet cette ces mon ma mes ton ta tes son sa ses
notre nos votre vos leur leurs je tu il elle on nous vous ils elles me te se moi toi lui eux y en ne pas plus rien jamais
est sont était être avoir a ai as avons avez ont fait faire faut va vais vas allons allez vont aller dit dire peut pouvoir
veux veut voulez vouloir sais savoir voir vu venir vient prendre pris mettre mis donner donne passer parler aimer trouver
bien très trop peu beaucoup aussi encore déjà toujours après avant pendant depuis avec sans pour par sur sous dans chez vers entre
aujourd'hui hier demain maintenant ici là oui non merci bonjour bonsoir salut au aux à comme si quand comment pourquoi combien
temps heure heures jour jours an ans année semaine mois matin soir nuit midi minute minutes seconde fois
homme femme enfant enfants gens monde pays ville maison travail vie eau soleil pluie vent chaud froid nouveau nouvelle grand grande
petit petite bon bonne mauvais premier première deuxième dernier autre même tout tous toute toutes chaque quelque plusieurs
france français paris gouvernement président ministre état politique économie prix euros millions milliards police guerre
un deux trois quatre cinq six sept huit neuf dix vingt trente cent mille""".split()


def run(path, grammar):
    wf = wave.open(path, "rb")
    rec = KaldiRecognizer(MODEL_OBJ, wf.getframerate(), json.dumps(grammar, ensure_ascii=False)) if grammar else KaldiRecognizer(MODEL_OBJ, wf.getframerate())
    rec.SetWords(True)
    finals = []
    while True:
        data = wf.readframes(4000)
        if not data:
            break
        if rec.AcceptWaveform(data):
            finals.append(json.loads(rec.Result()))
    finals.append(json.loads(rec.FinalResult()))
    return [f for f in finals if f.get("text")]


def fires(final, rule, kw="bello"):
    words = final.get("result", [])
    for i, w in enumerate(words):
        if w["word"] not in KEYWORDS:
            continue
        if rule["minConf"] and w["conf"] < rule["minConf"]:
            continue
        if rule["first"]:
            real_before = [x for x in words[:i] if x["word"] != "[unk]"]
            if real_before or i > rule.get("maxUnkBefore", 0):
                continue
        return True
    return False


RULES = [
    {"name": "any", "minConf": 0, "first": False},
    {"name": "conf>=0.95", "minConf": 0.95, "first": False},
    {"name": "first", "minConf": 0, "first": True},
    {"name": "first+conf>=0.9", "minConf": 0.9, "first": True},
    {"name": "first+conf>=0.95", "minConf": 0.95, "first": True},
    {"name": "first+conf>=1.0", "minConf": 1.0, "first": True},
]


if __name__ == "__main__":
    SetLogLevel(-1)
    MODEL_OBJ = Model(MODEL)
    grammars = {
        "G0 bello|unk": ["bello", "[unk]"],
        "G1 decoys": sorted(set(DECOYS)) + ["[unk]"],
        "G2 decoys+common": sorted(set(DECOYS + COMMON)) + ["[unk]"],
    }
    only = sys.argv[1:]
    positives = sorted(glob.glob(os.path.join(AUDIO, "wake", "*.wav")))
    negatives = {
        "background(own text)": [os.path.join(AUDIO, f) for f in ("background_fr_long.wav", "background_fr_long2.wav")],
        "news(live RSS)": [os.path.join(AUDIO, "news_fr.wav")],
    }
    minutes = {k: sum(wave.open(p).getnframes() / 16000 / 60 for p in v) for k, v in negatives.items()}
    for gname, grammar in grammars.items():
        if only and not any(o in gname for o in only):
            continue
        pos = {p: run(p, grammar) for p in positives}
        neg = {k: [f for p in v for f in run(p, grammar)] for k, v in negatives.items()}
        print(f"\n### {gname} ({len(grammar)} entries)")
        for rule in RULES:
            tp = sum(1 for fs in pos.values() if any(fires(f, rule) for f in fs))
            fp = {k: sum(1 for f in fs if fires(f, rule)) for k, fs in neg.items()}
            fp_txt = "  ".join(f"{k}: {n} ({n / minutes[k] * 60:.0f}/h)" for k, n in fp.items())
            print(f"  {rule['name']:<18} recall={tp}/{len(positives)}  FP {fp_txt}")
        missed = [os.path.basename(p) + " -> " + " | ".join(f["text"] for f in fs)
                  for p, fs in pos.items() if not any(fires(f, RULES[-2]) for f in fs)]
        if missed:
            print("  missed (first+conf>=0.95):", *missed[:8], sep="\n    ")
