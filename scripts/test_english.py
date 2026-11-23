"""Tries the English-detection rule (same logic as the apps) on mixed sentences.

  python -I scripts/test_english.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCORES = {}
for line in (ROOT / "windows" / "Assets" / "english.tsv").read_text(encoding="utf-8").splitlines():
    w, s = line.split("\t")
    SCORES[w] = float(s)

STRONG, WEAK, NEED = 3.0, -1.5, 2.0


def score(word):
    w = re.sub(r"[^a-z']", "", word.lower())
    if not w or re.search(r"\d", word):
        return -6.0
    return SCORES.get(w, -6.0)


def decide(words):
    """Returns per word: True = keep English. Mirrors EnglishDetector in the apps, including
    turning the previous ambiguous word back to English when the next word is clearly English."""
    out, ctx = [], []
    for w in words:
        s = score(w)
        recent = ctx[-3:]
        c = sum(max(-3, min(3, x)) for x in recent) / len(recent) if recent else 0.0
        eng = s >= STRONG or (s > WEAK and s + c >= NEED)
        # next word clearly English: the previous one, if it could be English, becomes English too
        if s >= STRONG and out and not out[-1] and ctx and ctx[-1] > WEAK:
            out[-1] = True
        out.append(eng)
        ctx.append(s)
        if re.search(r"[.!?;]$", w):
            ctx.clear()
    return out


TESTS = [
    ("do i have to press double space ?", "EEEEEEE?"),
    ("kalimera ti kaneis", "GGG"),
    ("to kserw oti eisai kala", "GGGGG"),
    ("pame gia kafe meta", "GGGG"),
    ("an thes ela", "GGG"),
    ("me poion eisai", "GGG"),
    ("file ti leei", "GGG"),
    ("o giorgos einai edw", "GGGG"),
    ("as pame", "GG"),
    ("8a se dw avrio sto gym", "GGGGGE"),
    ("nai re file", "GGG"),
    ("ela re mama", "GGG"),
    ("i maria ir8e", "GGG"),
    ("a kala", "GG"),
    ("do you speak greek", "EEEE"),
    ("this is the same", "EEEE"),
    ("yes ok", "EE"),
    ("pou eisai ? i am at home", "GG?EEEE"),
    ("eimai sto meeting meta", "GGEG"),
    ("to kanw sorry", "GGE"),
    ("i have to go", "EEEE"),
    ("se agapaw", "GG"),
    ("to email mou", "GEG"),
]

ok = 0
for sent, want in TESTS:
    words = sent.split()
    got = "".join("E" if e else "G" for e in decide(words))
    want = want.replace("?", "")
    words_np = [w for w in words if w != "?"]
    got = "".join(g for w, g in zip(words, got) if w != "?")
    good = got == want
    ok += good
    marks = " ".join(f"{w}:{g}" for w, g in zip(words_np, got))
    print(("OK  " if good else "XX  ") + marks + ("" if good else f"   (want {want})"))
print(f"{ok}/{len(TESTS)} sentences right")
