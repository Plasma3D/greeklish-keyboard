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
# English sentence mode: when 2 of the last 3 words are clearly English, any real English word stays English
# (rotate, file, mega...) unless it is mostly a Greeklish word (se, na, kai: below FLOOR).
FLOOR = -4.0


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
        english_mode = sum(x >= STRONG for x in recent) >= 2 or (len(recent) >= 2 and c >= NEED)
        eng = s >= STRONG or (s > WEAK and s + c >= NEED) or (english_mode and s > FLOOR)
        # next word clearly English: the previous one, if it could be English, becomes English too,
        # unless the sentence before it was Greek ("pes mou gia to game")
        if s >= STRONG and out and not out[-1] and ctx and ctx[-1] > WEAK:
            before = ctx[:-1][-3:]
            b = sum(max(-3, min(3, x)) for x in before) / len(before) if before else 0.0
            if ctx[-1] + b >= 0:
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
    # English sentence mode
    ("when i do rotate on the phone the game closes", "EEEEEEEEEE"),
    ("can you rotate the screen", "EEEEE"),
    ("send me the file please", "EEEEE"),
    ("that was a mega game", "EEEEE"),
    ("i love you mama", "EEEE"),
    # English words inside Greek still work, and Greek continues after them
    ("pes mou gia to game", "GGGGE"),
    ("to game einai kalo", "GEGG"),
    ("me too", "GE"),  # trade-off: "me"/"to" first in a message stay Greek so "to game" works
    ("pame na paixoume game meta", "GGGEG"),
    ("ok see you ta leme", "EEEGG"),
    ("sorry file den to eida", "EGGGG"),
    ("rotate to kinito", "GGG"),
    ("ti rotate", "GG"),
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
