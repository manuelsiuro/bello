#!/usr/bin/env python3
"""Print a few thousand words of French the wake word has never met: today's headlines and
random Wikipedia articles. Used to make background speech for scripts/wake-test.sh noise.

    french_text.py [words]
"""
import html
import json
import re
import subprocess
import sys

FEEDS = [
    "https://www.lemonde.fr/rss/une.xml",
    "https://www.francetvinfo.fr/titres.rss",
    "https://www.francetvinfo.fr/culture.rss",
]
WIKI = ("https://fr.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1"
        "&generator=random&grnnamespace=0&grnlimit=8&format=json")
TAGS = re.compile(r"<[^>]+>")
CDATA = re.compile(r"<!\[CDATA\[(.*?)]]>", re.S)


def get(url):
    # curl, not urllib: this Mac's Python has no certificate bundle of its own.
    done = subprocess.run(["curl", "-sSfL", "--max-time", "25", "-A", "bello-wake-test", url],
                          capture_output=True)
    if done.returncode != 0:
        raise RuntimeError(done.stderr.decode("utf-8", "replace").strip()[:200])
    return done.stdout.decode("utf-8", "replace")


def headlines():
    out = []
    for feed in FEEDS:
        try:
            xml = get(feed)
        except Exception as e:  # a feed being down is not a reason to stop
            print(f"({feed}: {e})", file=sys.stderr)
            continue
        for item in re.findall(r"<item[\s>].*?</item>", xml, re.S):
            for tag in ("title", "description"):
                found = re.search(rf"<{tag}[^>]*>(.*?)</{tag}>", item, re.S)
                if found:
                    # Feeds arrive escaped ("&#xE9;"); a speech synthesiser reads that literally.
                    text = html.unescape(CDATA.sub(r"\1", found.group(1)))
                    out.append(html.unescape(TAGS.sub(" ", text)).strip())
    return out


def articles():
    try:
        pages = json.loads(get(WIKI))["query"]["pages"]
    except Exception as e:
        print(f"(wikipedia: {e})", file=sys.stderr)
        return []
    return [p.get("extract", "") for p in pages.values()]


if __name__ == "__main__":
    wanted = int(sys.argv[1]) if len(sys.argv) > 1 else 2500
    text, words = [], 0
    for piece in headlines():
        text.append(piece)
        words += len(piece.split())
    while words < wanted:
        got = articles()
        if not got:
            break
        for piece in got:
            text.append(piece)
            words += len(piece.split())
    body = re.sub(r"\s+", " ", " ".join(text))
    print(body)
    print(f"{len(body.split())} words", file=sys.stderr)
